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
import kotlin.time.Duration.Companion.milliseconds

class NettyClient(
  val host: String,
  val port: Int,
  private val connectTimeoutMillis: Int = 5000,
  private val getFrameDecoder: () -> ChannelHandler = { LineBasedFrameDecoder(8192) },
  private val onNettyError: (Throwable) -> Unit = { it.printStackTrace() },
) {
  private val _lock = Any()

  private var _connection: NettyConnection? = null
  private var _channel: Channel? = null
  private var _group: EventExecutorGroup? = null

  @Volatile
  private var _isLineBasedDecoder = false
  @Volatile
  private var _messageScope: CoroutineScope? = null
  private var _connectDeferred: CompletableDeferred<Unit>? = null
  private val _sendingJobs: MutableSet<CompletableDeferred<*>> = Collections.newSetFromMap(ConcurrentHashMap())

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

      _connection?.destroy()
      _connection = null

      _channel?.close()
      _channel = null

      _group?.shutdownGracefully()
      _group = null

      _isLineBasedDecoder = false

      _messageScope?.cancel()
      _messageScope = null

      _connectDeferred?.cancel()
      _connectDeferred = null

      _sendingJobs.forEach { it.cancel() }
    }
  }

  /** 发送消息，如果超时则抛出[NettyClientSendTimeoutException] */
  @Throws(NettyClientException::class)
  suspend fun send(message: String, timeoutMillis: Long = 10000L) {
    val deferred = CompletableDeferred<Unit>().also { _sendingJobs.add(it) }
    try {
      val future = sendMessage(message, deferred)
      try {
        withTimeout(timeoutMillis.milliseconds) { deferred.await() }
      } catch (_: TimeoutCancellationException) {
        future.cancel(true)
        throw NettyClientSendTimeoutException()
      }
    } finally {
      _sendingJobs.remove(deferred)
    }
  }

  @Throws(NettyClientException::class)
  private fun sendMessage(message: String, deferred: CompletableDeferred<Unit>): ChannelFuture {
    return synchronized(_lock) {
      val channel = _channel
      if (channel == null || !channel.isActive) {
        throw NettyClientNotReadyException()
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
          deferred.completeExceptionally(NettyClientSendException(cause = future.cause()))
        }
      })
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
          getFrameDecoder = { getFrameDecoder().also { _isLineBasedDecoder = it is LineBasedFrameDecoder } },
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
            getMessageScope()?.launch { _messageFlow.emit(msg) }
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
  private fun getMessageScope(): CoroutineScope? {
    _messageScope?.also { return it }
    synchronized(_lock) {
      _messageScope?.also { return it }
      if (getConnectionState() == ConnectionState.DISCONNECTED) return null
      return CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        .also { _messageScope = it }
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
    getFrameDecoder: () -> ChannelHandler,
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
      .option(ChannelOption.SO_REUSEADDR, true)
      .handler(object : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
          ch.pipeline()
            .addLast(getFrameDecoder())
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
                if (!_destroyed) {
                  onExceptionCaught(cause)
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
