package com.sd.lib.netty.server

import io.netty.bootstrap.ServerBootstrap
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
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.AttributeKey
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
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

class NettyServer(
  val port: Int,

  /**
   * 读超时时间（秒），如果在此时间内未收到客户端消息，则主动断开连接。
   * 小于等于0，表示不检测。
   */
  private val readIdleTimeSeconds: Int = 60,

  /**
   * 帧解码，默认为[LineBasedFrameDecoder]，根据换行符分割，
   * 默认[LineBasedFrameDecoder]的情况下，[NettyServer.send]会自动带上分隔符。
   */
  private val getFrameDecoder: () -> ChannelHandler = { LineBasedFrameDecoder(8192) },

  /**
   * 异常回调，
   * 注意：此回调中只应该做日志记录等简单操作，不应该调用[NettyServer.start]等操作。
   * 注意：回调中抛出的异常会被静默捕获。
   */
  private val onChannelError: (Client?, Throwable) -> Unit = { _, e -> e.printStackTrace() },

  /**
   * 读超时回调，超时时间由[readIdleTimeSeconds]控制。
   * 注意：回调中抛出的异常会被静默捕获。
   */
  private val onReadIdle: (Client?) -> Unit = {},
) {
  private val _lock = Any()

  private var _connection: NettyConnection? = null
  private var _parentGroup: EventLoopGroup? = null
  private var _childGroup: EventLoopGroup? = null

  @Volatile
  private var _messageScope: CoroutineScope? = null
  private var _startDeferred: CompletableDeferred<Unit>? = null
  private val _sendingJobs: MutableSet<CompletableDeferred<*>> = Collections.newSetFromMap(ConcurrentHashMap())

  private val _clientsInfo: MutableMap<String, ClientInfo> = mutableMapOf()

  private val _clientsFlow = MutableStateFlow<List<Client>>(emptyList())
  private val _messageFlow = MutableSharedFlow<ServerMessage>()
  private val _stateFlow = MutableStateFlow(ServerState.STOPPED)

  /** 客户端列表 */
  val clientsFlow: StateFlow<List<Client>> = _clientsFlow.asStateFlow()

  /** 所有客户端消息 */
  val messageFlow: Flow<ServerMessage> = _messageFlow.asSharedFlow()

  /** 服务器状态 */
  val stateFlow: StateFlow<ServerState> = _stateFlow.asStateFlow()

  /** 服务器状态 */
  fun getState(): ServerState {
    return _stateFlow.value
  }

  /**
   * 启动服务，挂起直到启动成功，如果抛异常则表示启动失败或者取消。
   * 如果正在启动时，[stop]被触发，可能抛出[CancellationException]，
   * 如果正在启动时，有其他协程调用此方法，则该协程会挂起。
   */
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
    stopWithException(null)
  }

  /**
   * 发送消息给指定客户端，挂起直到发送成功，如果抛异常则表示发送失败或者取消。
   * 如果正在发送时，[stop]被触发，可能抛出[CancellationException],
   * 如果发送超时则抛出[NettyServerTimeoutException]，超时或取消不代表消息一定没发出去。
   */
  @Throws(NettyServerException::class)
  suspend fun send(
    clientId: String,
    message: String,
    timeoutMillis: Long = 10000L,
  ) {
    val deferred = CompletableDeferred<Unit>()
    try {
      _sendingJobs.add(deferred)
      val future = sendMessage(clientId, message, deferred)
      try {
        withTimeout(timeoutMillis.milliseconds) { deferred.await() }
      } catch (_: TimeoutCancellationException) {
        future.cancel(true)
        throw NettyServerTimeoutException()
      } catch (e: CancellationException) {
        future.cancel(true)
        throw e
      }
    } finally {
      _sendingJobs.remove(deferred)
    }
  }

  /** 发送消息 */
  @Throws(NettyServerException::class)
  private fun sendMessage(
    clientId: String,
    message: String,
    deferred: CompletableDeferred<Unit>,
  ): ChannelFuture {
    return synchronized(_lock) {
      val clientInfo = _clientsInfo[clientId] ?: throw NettyServerClientNotFoundException()

      val channel = clientInfo.channel
      if (!channel.isActive) throw NettyServerClientNotReadyException()

      val finalMessage = if (clientInfo.isLineBasedDecoder && !message.endsWith('\n')) {
        message + "\n"
      } else {
        message
      }

      channel to finalMessage
    }.let { (channel, msg) ->
      try {
        channel.writeAndFlush(msg)
      } catch (e: Throwable) {
        throw NettyServerException(cause = e)
      }.addListener(ChannelFutureListener { future ->
        if (future.isSuccess) {
          deferred.complete(Unit)
        } else {
          deferred.completeExceptionally(NettyServerException(cause = future.cause()))
        }
      })
    }
  }

  private fun stopWithException(exception: Throwable?) {
    synchronized(_lock) {
      _connection?.destroy()
      _connection = null

      _parentGroup?.shutdownGracefully()
      _parentGroup = null
      _childGroup?.shutdownGracefully()
      _childGroup = null

      _clientsInfo.clear()
      _clientsFlow.value = emptyList()

      _messageScope?.cancel()
      _messageScope = null

      if (exception != null) {
        _startDeferred?.completeExceptionally(exception)
      } else {
        _startDeferred?.cancel()
      }
      _startDeferred = null

      if (exception != null) {
        _sendingJobs.forEach { it.completeExceptionally(exception) }
      } else {
        _sendingJobs.forEach { it.cancel() }
      }

      _stateFlow.value = ServerState.STOPPED
    }
  }

  private fun doStart(): CompletableDeferred<Unit> {
    return CompletableDeferred<Unit>().also { deferred ->
      _stateFlow.value = ServerState.STARTING
      _startDeferred = deferred
      try {
        val parentGroup = NioEventLoopGroup(1).also { _parentGroup = it }
        val childGroup = NioEventLoopGroup().also { _childGroup = it }
        NettyConnection(_lock).also { _connection = it }.start(
          parentGroup = parentGroup,
          childGroup = childGroup,
          port = port,
          readIdleTimeSeconds = readIdleTimeSeconds,
          getFrameDecoder = { getFrameDecoder() },
          onBind = { future ->
            if (future.isSuccess) {
              _stateFlow.value = ServerState.STARTED
              deferred.complete(Unit)
              _startDeferred = null
            } else {
              val exception = NettyServerException(cause = future.cause())
              stopWithException(exception)
            }
          },
          onChannelActive = { channel, isLineBasedDecoder ->
            val clientId = channel.id().asLongText()
            val remoteAddress = channel.remoteAddress()?.toString() ?: ""
            val socketAddress = channel.remoteAddress() as? InetSocketAddress

            val client = Client(
              id = clientId,
              remoteAddress = remoteAddress,
              ip = socketAddress?.address?.hostAddress ?: "",
              port = socketAddress?.port ?: 0,
            )

            val clientInfo = ClientInfo(
              client = client,
              channel = channel,
              isLineBasedDecoder = isLineBasedDecoder,
            )

            channel.attr(CLIENT_KEY).set(client)
            _clientsInfo[clientId] = clientInfo
            syncClientsFlowLocked()
          },
          onChannelInactive = { channel ->
            channel.attr(CLIENT_KEY).set(null)
            val clientId = channel.id().asLongText()
            if (_clientsInfo.remove(clientId) != null) {
              syncClientsFlowLocked()
            }
          },
          onChannelRead = { channel, msg ->
            val client = channel.attr(CLIENT_KEY).get()
            if (client != null) {
              getMessageScope()?.launch {
                _messageFlow.emit(ServerMessage(client, msg))
              }
            }
          },
          onChannelIdle = { ctx, event ->
            if (event.state() == IdleState.READER_IDLE) {
              val clientId = ctx.channel()?.id()?.asLongText()
              val client = synchronized(_lock) { _clientsInfo[clientId]?.client }
              runCatching { onReadIdle(client) }
              ctx.close()
            }
          },
          onExceptionCaught = { ctx, e ->
            val clientId = ctx.channel()?.id()?.asLongText()
            val client = synchronized(_lock) { _clientsInfo[clientId]?.client }
            runCatching { onChannelError(client, e) }
          },
        )
      } catch (e: Throwable) {
        val exception = NettyServerException(cause = e)
        stopWithException(exception)
        throw exception
      }
    }
  }

  private fun syncClientsFlowLocked() {
    _clientsFlow.value = _clientsInfo.values.map { it.client }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun getMessageScope(): CoroutineScope? {
    _messageScope?.also { return it }
    synchronized(_lock) {
      _messageScope?.also { return it }
      if (_connection == null) return null
      return CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        .also { _messageScope = it }
    }
  }

  enum class ServerState {
    /** 停止 */
    STOPPED,

    /** 启动中 */
    STARTING,

    /** 已启动 */
    STARTED
  }

  data class ServerMessage(
    val client: Client,
    val message: String,
  )

  data class Client(
    val id: String,
    val remoteAddress: String,
    val ip: String,
    val port: Int,
  )

  private class ClientInfo(
    val client: Client,
    val channel: Channel,
    val isLineBasedDecoder: Boolean,
  )

  private companion object {
    val CLIENT_KEY = AttributeKey.valueOf<Client>("NettyServer.Client")
  }
}

private class NettyConnection(private val lock: Any) {
  @Volatile
  private var _destroyed = false
  private var _channel: Channel? = null

  fun start(
    parentGroup: EventLoopGroup,
    childGroup: EventLoopGroup,
    port: Int,
    readIdleTimeSeconds: Int,
    getFrameDecoder: () -> ChannelHandler,
    onBind: (ChannelFuture) -> Unit,
    onChannelActive: (Channel, Boolean) -> Unit,
    onChannelInactive: (Channel) -> Unit,
    onChannelRead: (Channel, String) -> Unit,
    onChannelIdle: (ChannelHandlerContext, IdleStateEvent) -> Unit,
    onExceptionCaught: (ChannelHandlerContext, Throwable) -> Unit,
  ) {
    if (_destroyed) return
    ServerBootstrap()
      .group(parentGroup, childGroup)
      .channel(NioServerSocketChannel::class.java)
      .option(ChannelOption.SO_REUSEADDR, true)
      .childHandler(object : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
          val frameDecoder = getFrameDecoder()
          val isLineBasedDecoder = frameDecoder is LineBasedFrameDecoder

          var pipeline = ch.pipeline()
          if (readIdleTimeSeconds > 0) {
            pipeline = pipeline.addLast(
              IdleStateHandler(
                readIdleTimeSeconds.toLong(),
                0,
                0,
                TimeUnit.SECONDS,
              )
            )
          }

          pipeline.addLast(frameDecoder)
            .addLast(StringDecoder(CharsetUtil.UTF_8))
            .addLast(StringEncoder(CharsetUtil.UTF_8))
            .addLast(object : SimpleChannelInboundHandler<String>() {
              override fun channelActive(ctx: ChannelHandlerContext) {
                synchronized(lock) {
                  if (!_destroyed) {
                    onChannelActive(ctx.channel(), isLineBasedDecoder)
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

              override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                if (evt is IdleStateEvent) {
                  onChannelIdle(ctx, evt)
                } else {
                  super.userEventTriggered(ctx, evt)
                }
              }

              override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                try {
                  if (!_destroyed) {
                    onExceptionCaught(ctx, cause)
                  }
                } finally {
                  runCatching { ctx.close() }
                }
              }
            })
        }
      })
      .bind(port).addListener(ChannelFutureListener { future ->
        synchronized(lock) {
          if (_destroyed) {
            runCatching { future.channel().close() }
          } else {
            if (future.isSuccess) _channel = future.channel()
            onBind(future)
          }
        }
      })
  }

  fun destroy() {
    _destroyed = true
    _channel?.close()
    _channel = null
  }
}
