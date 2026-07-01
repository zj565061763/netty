package com.sd.lib.netty.server

open class NettyServerException internal constructor(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** 启动异常 */
class NettyServerStartException internal constructor(cause: Throwable?) : NettyServerException(null, cause)

/** 服务未就绪或客户端不存在 */
class NettyServerNotReadyException internal constructor(message: String) : NettyServerException(message)

/** 发送异常 */
class NettyServerSendException internal constructor(cause: Throwable?) : NettyServerException(null, cause)

/** 发送超时异常 */
class NettyServerSendTimeoutException internal constructor(cause: Throwable?) : NettyServerException(null, cause)
