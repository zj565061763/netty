package com.sd.lib.netty.server

open class NettyServerException internal constructor(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** 启动异常 */
class NettyServerStartException internal constructor(cause: Throwable?) : NettyServerException(null, cause)

/** 客户端未就绪或不处于激活状态 */
class NettyServerClientNotReadyException internal constructor(message: String) : NettyServerException(message)

/** 客户端不存在 */
class NettyServerClientNotFoundException internal constructor(message: String) : NettyServerException(message)

/** 发送异常 */
class NettyServerSendException internal constructor(cause: Throwable?) : NettyServerException(null, cause)

/** 发送超时异常 */
class NettyServerSendTimeoutException internal constructor(cause: Throwable?) : NettyServerException(null, cause)
