package com.sd.lib.netty.client

open class NettyClientException internal constructor(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** 还未就绪 */
class NettyClientNotReadyException internal constructor() : NettyClientException()

/** 超时异常 */
class NettyClientTimeoutException internal constructor() : NettyClientException()
