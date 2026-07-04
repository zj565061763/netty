package com.sd.lib.netty.client

open class NettyClientException internal constructor(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** 连接异常 */
class NettyClientConnectException internal constructor(cause: Throwable?) : NettyClientException(cause = cause)

/** 还未准备好 */
class NettyClientNotReadyException internal constructor() : NettyClientException()

/** 发送异常 */
class NettyClientSendException internal constructor(cause: Throwable?) : NettyClientException(cause = cause)

/** 发送超时异常 */
class NettyClientSendTimeoutException internal constructor() : NettyClientException()

/** 断开连接异常 */
class NettyClientDisconnectedException internal constructor() : NettyClientException()
