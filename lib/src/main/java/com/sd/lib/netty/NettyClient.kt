package com.sd.lib.netty

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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

  private var _connection: NettyConnection? = null
  private var _group: EventExecutorGroup? = null
  private var _channel: Channel? = null

  @Volatile
  private var _coroutineScope: CoroutineScope? = null
  private var _connectDeferred: CompletableDeferred<Unit>? = null
  private val _pendingJobs: MutableSet<CompletableDeferred<*>> = Collections.newSetFromMap(ConcurrentHashMap())

  private val _messageFlow = MutableSharedFlow<String>()
  private val _connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)

  val messageFlow: Flow<String> = _messageFlow.asSharedFlow()
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
      _connectionStateFlow.value = ConnectionState.DISCONNECTED
    }
  }

  /** 发送消息 */
  @Throws(NettyClientException::class)
  suspend fun send(message: String) {
    pendingJob { deferred ->
      synchronized(_lock) {
        val channel = _channel
        if (channel != null && channel.isActive) {
          channel.writeAndFlush(message).addListener(ChannelFutureListener { future ->
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

  private fun doConnect(): CompletableDeferred<Unit> {
    check(_connection == null)
    return CompletableDeferred<Unit>().also { deferred ->
      _connectionStateFlow.value = ConnectionState.CONNECTING
      _connectDeferred = deferred
      try {
        NettyConnection(_lock).also { _connection = it }.connect(
          host = host,
          port = port,
          connectTimeoutMillis = connectTimeoutMillis,
          frameDecoder = getFrameDecoder(),
          onConnect = { group, future ->
            _group = group
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

  open class NettyClientException internal constructor(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

  /** 连接异常 */
  class NettyClientConnectException internal constructor(cause: Throwable?) : NettyClientException(null, cause)

  /** 还未准备好 */
  class NettyClientNotReadyException internal constructor() : NettyClientException()

  /** 发送异常 */
  class NettyClientSendException internal constructor(cause: Throwable?) : NettyClientException(null, cause)
}

private class NettyConnection(private val lock: Any) {
  @Volatile
  private var _destroyed = false

  fun connect(
    host: String,
    port: Int,
    connectTimeoutMillis: Int,
    frameDecoder: ChannelHandler,
    onConnect: (EventLoopGroup, ChannelFuture) -> Unit,
    onChannelActive: () -> Unit,
    onChannelInactive: () -> Unit,
    onChannelRead0: (String) -> Unit,
    onExceptionCaught: (Throwable) -> Unit,
  ) {
    if (_destroyed) return
    val group = NioEventLoopGroup(1)
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
            runCatching {
              future.channel().close()
              group.shutdownGracefully()
            }
          } else {
            onConnect(group, future)
          }
        }
      })
  }

  fun destroy() {
    _destroyed = true
  }
}