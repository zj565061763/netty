package com.sd.lib.netty.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.util.CharsetUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class NettyServer(
  val port: Int,
  private val getFrameDecoder: () -> ChannelHandler = { LineBasedFrameDecoder(8192) },
  private val onNettyError: (Throwable) -> Unit = { it.printStackTrace() },
) {
  private val _lock = Any()
  private var _isLineBasedDecoder = false

  private var _serverConnection: NettyServerConnection? = null
  private var _parentGroup: EventLoopGroup? = null
  private var _childGroup: EventLoopGroup? = null
  private var _channel: Channel? = null

  @Volatile
  private var _coroutineScope: CoroutineScope? = null
  private var _startDeferred: CompletableDeferred<Unit>? = null
  private val _pendingJobs: MutableSet<CompletableDeferred<*>> = Collections.newSetFromMap(ConcurrentHashMap())

  private val _clients: MutableMap<String, Channel> = ConcurrentHashMap()
  private val _clientsFlow = MutableStateFlow<List<String>>(emptyList())
  private val _messageFlow = MutableSharedFlow<ServerMessage>()
  private val _stateFlow = MutableStateFlow(ServerState.STOPPED)

  /** 监听客户端列表 */
  val clientsFlow: StateFlow<List<String>> = _clientsFlow.asStateFlow()

  /** 监听所有客户端消息 */
  val messageFlow: Flow<ServerMessage> = _messageFlow.asSharedFlow()

  /** 监听服务器状态 */
  val stateFlow: StateFlow<ServerState> = _stateFlow.asStateFlow()

  /** 服务器状态 */
  fun getState(): ServerState {
    return _stateFlow.value
  }

  /** 启动服务 */
  @Throws(NettyServerException::class)
  suspend fun start() {
    synchronized(_lock) {
      when (getState()) {
        ServerState.STOPPED -> doStart()
        ServerState.STARTING -> _startDeferred
        ServerState.STARTED -> null
      }
    }?.await()
  }

  /** 停止服务 */
  fun stop() {
    synchronized(_lock) {
      if (getState() == ServerState.STOPPED) return
      _stateFlow.value = ServerState.STOPPED

      _isLineBasedDecoder = false
      _serverConnection?.destroy()
      _serverConnection = null
      _clients.clear()
      _clientsFlow.value = emptyList()

      _pendingJobs.forEach { it.cancel() }
      _coroutineScope?.cancel()
      _coroutineScope = null
      _startDeferred?.cancel()
      _startDeferred = null

      _channel?.close()
      _channel = null

      _parentGroup?.shutdownGracefully()
      _parentGroup = null
      _childGroup?.shutdownGracefully()
      _childGroup = null
    }
  }

  /** 发送消息给指定客户端 */
  @Throws(NettyServerException::class)
  suspend fun send(clientId: String, message: String, timeoutMillis: Long = 10000L) {
    try {
      withTimeout(timeoutMillis) {
        send(clientId, message)
      }
    } catch (e: TimeoutCancellationException) {
      throw NettyServerSendTimeoutException(cause = e)
    }
  }

  private suspend fun send(clientId: String, message: String) {
    pendingJob { deferred ->
      synchronized(_lock) {
        val channel = _clients[clientId]
        if (channel == null) {
          deferred.completeExceptionally(NettyServerClientNotFoundException("Client $clientId not found"))
          return@synchronized
        }

        if (!channel.isActive) {
          deferred.completeExceptionally(NettyServerClientNotReadyException("Client $clientId not active"))
          return@synchronized
        }

        val finalMessage = if (_isLineBasedDecoder && !message.endsWith('\n')) {
          message + "\n"
        } else {
          message
        }

        channel.writeAndFlush(finalMessage).addListener(ChannelFutureListener { future ->
          if (future.isSuccess) {
            deferred.complete(Unit)
          } else {
            deferred.completeExceptionally(NettyServerSendException(cause = future.cause()))
          }
        })
      }
    }
  }

  private suspend inline fun <T> pendingJob(block: (CompletableDeferred<T>) -> Unit) {
    CompletableDeferred<T>()
      .also { _pendingJobs.add(it) }
      .also { deferred ->
        try {
          block(deferred)
          deferred.await()
        } finally {
          _pendingJobs.remove(deferred)
        }
      }
  }

  private fun doStart(): CompletableDeferred<Unit> {
    return CompletableDeferred<Unit>().also { deferred ->
      _stateFlow.value = ServerState.STARTING
      _startDeferred = deferred
      try {
        val parentGroup = NioEventLoopGroup(1).also { _parentGroup = it }
        val childGroup = NioEventLoopGroup().also { _childGroup = it }
        NettyServerConnection(_lock).also { _serverConnection = it }.start(
          parentGroup = parentGroup,
          childGroup = childGroup,
          port = port,
          getFrameDecoder = { getFrameDecoder().also { _isLineBasedDecoder = it is LineBasedFrameDecoder } },
          onBind = { future ->
            if (future.isSuccess) {
              _channel = future.channel()
              _stateFlow.value = ServerState.STARTED
              deferred.complete(Unit)
              _startDeferred = null
            } else {
              deferred.completeExceptionally(NettyServerStartException(future.cause()))
              stop()
            }
          },
          onChannelActive = { channel ->
            val clientId = channel.id().asLongText()
            _clients[clientId] = channel
            _clientsFlow.value = _clients.keys.toList()
          },
          onChannelInactive = { channel ->
            val clientId = channel.id().asLongText()
            _clients.remove(clientId)
            _clientsFlow.value = _clients.keys.toList()
          },
          onChannelRead = { channel, msg ->
            val clientId = channel.id().asLongText()
            getCoroutineScope()?.launch {
              _messageFlow.emit(ServerMessage(clientId, msg))
            }
          },
          onNettyError = { e ->
            onNettyError(e)
          }
        )
      } catch (e: Throwable) {
        stop()
        throw NettyServerStartException(e)
      }
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun getCoroutineScope(): CoroutineScope? {
    _coroutineScope?.also { return it }
    synchronized(_lock) {
      _coroutineScope?.also { return it }
      if (getState() == ServerState.STOPPED) return null
      return CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        .also { _coroutineScope = it }
    }
  }

  enum class ServerState { STOPPED, STARTING, STARTED }

  data class ServerMessage(val clientId: String, val message: String)
}

private class NettyServerConnection(private val lock: Any) {
  @Volatile
  private var _destroyed = false

  fun start(
    parentGroup: EventLoopGroup,
    childGroup: EventLoopGroup,
    port: Int,
    getFrameDecoder: () -> ChannelHandler,
    onBind: (ChannelFuture) -> Unit,
    onChannelActive: (Channel) -> Unit,
    onChannelInactive: (Channel) -> Unit,
    onChannelRead: (Channel, String) -> Unit,
    onNettyError: (Throwable) -> Unit,
  ) {
    if (_destroyed) return
    ServerBootstrap()
      .group(parentGroup, childGroup)
      .channel(NioServerSocketChannel::class.java)
      .childHandler(object : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
          ch.pipeline()
            .addLast(getFrameDecoder())
            .addLast(StringDecoder(CharsetUtil.UTF_8))
            .addLast(StringEncoder(CharsetUtil.UTF_8))
            .addLast(object : SimpleChannelInboundHandler<String>() {
              override fun channelActive(ctx: ChannelHandlerContext) {
                synchronized(lock) {
                  if (!_destroyed) {
                    onChannelActive(ctx.channel())
                  } else {
                    ctx.channel().close()
                  }
                }
              }

              override fun channelInactive(ctx: ChannelHandlerContext) {
                synchronized(lock) {
                  if (!_destroyed) {
                    onChannelInactive(ctx.channel())
                  }
                }
              }

              override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
                if (!_destroyed) {
                  onChannelRead(ctx.channel(), msg)
                }
              }

              override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                if (!_destroyed) {
                  onNettyError(cause)
                }
                ctx.close()
              }
            })
        }
      })
      .bind(port).addListener(ChannelFutureListener { future ->
        synchronized(lock) {
          if (_destroyed) {
            runCatching { future.channel().close() }
          } else {
            onBind(future)
          }
        }
      })
  }

  fun destroy() {
    _destroyed = true
  }
}
