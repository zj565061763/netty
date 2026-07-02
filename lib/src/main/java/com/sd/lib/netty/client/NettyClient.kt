package com.sd.lib.netty.client

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.EventExecutorGroup
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

class NettyClient(
  val host: String,
  val port: Int,
  private val connectTimeoutMillis: Int = 5000,
  private val getFrameDecoder: () -> ChannelHandler = { LineBasedFrameDecoder(8192) },
  private val onNettyError: (Throwable) -> Unit = { it.printStackTrace() },
) {
  private val _lock = Any()
  private var _isLineBasedDecoder = false

  private var _connection: NettyConnection? = null
  private var _group: EventExecutorGroup? = null
  private var _channel: Channel? = null

  @Volatile
  private var _coroutineScope: CoroutineScope? = null
  private var _connectDeferred: CompletableDeferred<Unit>? = null
  private val _pendingJobs: MutableSet<CompletableDeferred<*>> = Collections.newSetFromMap(ConcurrentHashMap())

  private val _messageFlow = MutableSharedFlow<String>()
  private val _connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)

  /** 监听消息 */
  val messageFlow: Flow<String> = _messageFlow.asSharedFlow()
  /** 监听连接状态 */
  val connectionStateFlow: StateFlow<ConnectionState> = _connectionStateFlow.asStateFlow()

  /** 连接状态 */
  fun getConnectionState(): ConnectionState {
    return _connectionStateFlow.value
  }

  /** 连接 */
  @Throws(NettyClientException::class)
  suspend fun connect() {
    synchronized(_lock) {
      when (getConnectionState()) {
        ConnectionState.DISCONNECTED -> doConnect()
        ConnectionState.CONNECTING -> _connectDeferred
        ConnectionState.CONNECTED -> null
      }
    }?.await()
  }

  /** 断开连接 */
  fun disconnect() {
    synchronized(_lock) {
      if (getConnectionState() == ConnectionState.DISCONNECTED) return
      _connectionStateFlow.value = ConnectionState.DISCONNECTED

      _isLineBasedDecoder = false
      _connection?.destroy()
      _connection = null

      _pendingJobs.forEach { it.cancel() }
      _coroutineScope?.cancel()
      _coroutineScope = null
      _connectDeferred?.cancel()
      _connectDeferred = null

      _channel?.close()
      _channel = null

      _group?.shutdownGracefully()
      _group = null
    }
  }

  /** 发送消息 */
  @Throws(NettyClientException::class)
  suspend fun send(message: String, timeoutMillis: Long = 10000L) {
    try {
      withTimeout(timeoutMillis) {
        send(message)
      }
    } catch (e: TimeoutCancellationException) {
      throw NettyClientSendTimeoutException(cause = e)
    }
  }

  private suspend fun send(message: String) {
    pendingJob { deferred ->
      synchronized(_lock) {
        val channel = _channel
        if (channel != null && channel.isActive) {
          val finalMessage = if (_isLineBasedDecoder && !message.endsWith('\n')) {
            message + "\n"
          } else {
            message
          }
          channel.writeAndFlush(finalMessage).addListener(ChannelFutureListener { future ->
            if (future.isSuccess) {
              deferred.complete(Unit)
            } else {
              deferred.completeExceptionally(NettyClientSendException(cause = future.cause()))
            }
          })
        } else {
          deferred.completeExceptionally(NettyClientNotReadyException())
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

  private fun doConnect(): CompletableDeferred<Unit> {
    return CompletableDeferred<Unit>().also { deferred ->
      _connectionStateFlow.value = ConnectionState.CONNECTING
      _connectDeferred = deferred
      try {
        val group = NioEventLoopGroup(1).also { _group = it }
        NettyConnection(_lock).also { _connection = it }.connect(
          group = group,
          host = host,
          port = port,
          connectTimeoutMillis = connectTimeoutMillis,
          frameDecoder = getFrameDecoder().also { _isLineBasedDecoder = it is LineBasedFrameDecoder },
          onConnect = { future ->
            if (future.isSuccess) {
              _channel = future.channel()
              setConnected()
            } else {
              deferred.completeExceptionally(NettyClientConnectException(future.cause()))
              disconnect()
            }
          },
          onChannelActive = {
            setConnected()
          },
          onChannelInactive = {
            disconnect()
          },
          onChannelRead0 = { msg ->
            getCoroutineScope()?.launch { _messageFlow.emit(msg) }
          },
          onExceptionCaught = { e ->
            onNettyError(e)
          }
        )
      } catch (e: Throwable) {
        disconnect()
        throw NettyClientConnectException(e)
      }
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun getCoroutineScope(): CoroutineScope? {
    _coroutineScope?.also { return it }
    synchronized(_lock) {
      _coroutineScope?.also { return it }
      if (getConnectionState() == ConnectionState.DISCONNECTED) return null
      return CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        .also { _coroutineScope = it }
    }
  }

  private fun setConnected() {
    if (_group == null) return
    if (_channel == null) return
    if (getConnectionState() == ConnectionState.CONNECTING) {
      _connectionStateFlow.value = ConnectionState.CONNECTED
      _connectDeferred?.complete(Unit)
      _connectDeferred = null
    }
  }

  enum class ConnectionState {
    /** 断开连接 */
    DISCONNECTED,
    /** 连接中 */
    CONNECTING,
    /** 已连接 */
    CONNECTED
  }
}

private class NettyConnection(private val lock: Any) {
  @Volatile
  private var _destroyed = false

  fun connect(
    group: EventLoopGroup,
    host: String,
    port: Int,
    connectTimeoutMillis: Int,
    frameDecoder: ChannelHandler,
    onConnect: (ChannelFuture) -> Unit,
    onChannelActive: () -> Unit,
    onChannelInactive: () -> Unit,
    onChannelRead0: (String) -> Unit,
    onExceptionCaught: (Throwable) -> Unit,
  ) {
    if (_destroyed) return
    Bootstrap()
      .group(group)
      .channel(NioSocketChannel::class.java)
      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
      .handler(object : ChannelInitializer<SocketChannel>() {
        override fun initChannel(channel: SocketChannel) {
          channel.pipeline()
            .addLast(frameDecoder)
            .addLast(StringDecoder(CharsetUtil.UTF_8))
            .addLast(StringEncoder(CharsetUtil.UTF_8))
            .addLast(object : SimpleChannelInboundHandler<String>() {
              override fun channelActive(ctx: ChannelHandlerContext) {
                synchronized(lock) {
                  if (!_destroyed) {
                    onChannelActive()
                  }
                }
              }

              override fun channelInactive(ctx: ChannelHandlerContext) {
                synchronized(lock) {
                  if (!_destroyed) {
                    onChannelInactive()
                  }
                }
              }

              override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
                if (!_destroyed) {
                  onChannelRead0(msg)
                }
              }

              override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                synchronized(lock) {
                  if (!_destroyed) {
                    onExceptionCaught(cause)
                  }
                }
                ctx.close()
              }
            })
        }
      })
      .connect(host, port).addListener(ChannelFutureListener { future ->
        synchronized(lock) {
          if (_destroyed) {
            runCatching { future.channel().close() }
          } else {
            onConnect(future)
          }
        }
      })
  }

  fun destroy() {
    _destroyed = true
  }
}
