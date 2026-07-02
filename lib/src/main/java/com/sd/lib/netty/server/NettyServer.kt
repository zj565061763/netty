package com.sd.lib.netty.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
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

  private var _bossGroup: EventLoopGroup? = null
  private var _workerGroup: EventLoopGroup? = null
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

  /** 启动服务 */
  @Throws(NettyServerException::class)
  suspend fun start() {
    synchronized(_lock) {
      when (_stateFlow.value) {
        ServerState.STOPPED -> doStart()
        ServerState.STARTING -> _startDeferred
        ServerState.STARTED -> null
      }
    }?.await()
  }

  /** 停止服务 */
  fun stop() {
    synchronized(_lock) {
      if (_stateFlow.value == ServerState.STOPPED) return
      _stateFlow.value = ServerState.STOPPED

      _isLineBasedDecoder = false
      _clients.clear()
      _clientsFlow.value = emptyList()

      _pendingJobs.forEach { it.cancel() }
      _coroutineScope?.cancel()
      _coroutineScope = null
      _startDeferred?.cancel()
      _startDeferred = null

      _channel?.close()
      _channel = null

      _bossGroup?.shutdownGracefully()
      _bossGroup = null
      _workerGroup?.shutdownGracefully()
      _workerGroup = null
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
        if (channel != null && channel.isActive) {
          val finalMessage = if (_isLineBasedDecoder && !message.endsWith('\n') && !message.endsWith("\r\n")) {
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
        } else {
          deferred.completeExceptionally(NettyServerNotReadyException("Client $clientId not found or not active"))
        }
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
        val bossGroup = NioEventLoopGroup(1).also { _bossGroup = it }
        val workerGroup = NioEventLoopGroup().also { _workerGroup = it }

        ServerBootstrap()
          .group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel::class.java)
          .childHandler(object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
              val decoder = getFrameDecoder().also { _isLineBasedDecoder = it is LineBasedFrameDecoder }
              ch.pipeline()
                .addLast(decoder)
                .addLast(StringDecoder(CharsetUtil.UTF_8))
                .addLast(StringEncoder(CharsetUtil.UTF_8))
                .addLast(ServerHandler())
            }
          })
          .bind(port).addListener(ChannelFutureListener { future ->
            synchronized(_lock) {
              if (_stateFlow.value == ServerState.STOPPED) {
                future.channel().close()
              } else {
                if (future.isSuccess) {
                  _channel = future.channel()
                  _stateFlow.value = ServerState.STARTED
                  deferred.complete(Unit)
                  _startDeferred = null
                } else {
                  deferred.completeExceptionally(NettyServerStartException(future.cause()))
                  stop()
                }
              }
            }
          })
      } catch (e: Throwable) {
        stop()
        throw NettyServerStartException(e)
      }
    }
  }

  private inner class ServerHandler : SimpleChannelInboundHandler<String>() {
    override fun channelActive(ctx: ChannelHandlerContext) {
      val channel = ctx.channel()
      val clientId = channel.id().asLongText()
      synchronized(_lock) {
        if (_stateFlow.value != ServerState.STOPPED) {
          _clients[clientId] = channel
          _clientsFlow.value = _clients.keys.toList()
        } else {
          channel.close()
        }
      }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
      val clientId = ctx.channel().id().asLongText()
      synchronized(_lock) {
        _clients.remove(clientId)
        _clientsFlow.value = _clients.keys.toList()
      }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
      val clientId = ctx.channel().id().asLongText()
      getCoroutineScope()?.launch {
        _messageFlow.emit(ServerMessage(clientId, msg))
      }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
      onNettyError(cause)
      ctx.close()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun getCoroutineScope(): CoroutineScope? {
    _coroutineScope?.also { return it }
    synchronized(_lock) {
      _coroutineScope?.also { return it }
      if (_stateFlow.value == ServerState.STOPPED) return null
      return CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        .also { _coroutineScope = it }
    }
  }

  enum class ServerState { STOPPED, STARTING, STARTED }

  data class ServerMessage(val clientId: String, val message: String)
}
