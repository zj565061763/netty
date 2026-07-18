package com.sd.lib.netty.client

open class NettyClientException internal constructor(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** 还未就绪 */
class NettyClientNotReadyException internal constructor() : NettyClientException()

/** 出站缓冲区已超过高水位线（服务端接收数据过慢），暂时不可写 */
class NettyClientNotWritableException internal constructor() : NettyClientException()

/** 连接断开（被动掉线，例如服务端主动断开或者网络异常） */
class NettyClientConnectionLostException internal constructor() : NettyClientException()

/** 超时异常 */
class NettyClientTimeoutException internal constructor() : NettyClientException()
