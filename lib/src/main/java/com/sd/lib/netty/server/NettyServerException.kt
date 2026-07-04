package com.sd.lib.netty.server

open class NettyServerException internal constructor(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** 客户端不存在 */
class NettyServerClientNotFoundException internal constructor() : NettyServerException()

/** 客户端未就绪 */
class NettyServerClientNotReadyException internal constructor() : NettyServerException()

/** 超时异常 */
class NettyServerTimeoutException internal constructor() : NettyServerException()
