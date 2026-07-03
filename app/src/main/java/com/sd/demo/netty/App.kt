package com.sd.demo.netty

import android.app.Application
import com.sd.lib.netty.server.NettyServer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    @OptIn(DelicateCoroutinesApi::class)
    GlobalScope.launch {
      server.start()

      // 监听服务端状态
      launch {
        server.stateFlow.collect {
          logMsg { "server state:$it" }
        }
      }

      // 监听服务端的客户端列表
      launch {
        server.clientsFlow.collect {
          logMsg { "server clients:$it" }
        }
      }

      // 监听服务端的消息
      launch {
        server.messageFlow.collect {
          logMsg { "server message client:${it.client}|message:${it.message}" }
          runCatching {
            server.send(clientId = it.client.id, message = it.message)
          }.onFailure {
            logMsg { "server response message error:${it.stackTraceToString()}" }
          }
        }
      }
    }
  }

  companion object {
    /** 服务端 */
    val server = NettyServer(port = 8888)
  }
}