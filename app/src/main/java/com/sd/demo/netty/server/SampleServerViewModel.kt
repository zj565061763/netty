package com.sd.demo.netty.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sd.demo.netty.logMsg
import com.sd.lib.netty.server.NettyServer
import kotlinx.coroutines.launch

class SampleServerViewModel : ViewModel() {
  private val _server = NettyServer(8888)

  val serverStateFlow = _server.stateFlow
  val clientsFlow = _server.clientsFlow
  val port = _server.port

  init {
    viewModelScope.launch {
      runCatching {
        _server.start()
      }.onFailure {
        logMsg { "server start error:${it.stackTraceToString()}" }
      }
    }

    viewModelScope.launch {
      _server.messageFlow.collect {
        logMsg { "server received message from ${it.client.id}: ${it.message}" }
      }
    }
  }

  fun sendMessage(client: NettyServer.Client, message: String) {
    viewModelScope.launch {
      runCatching {
        _server.send(client.id, message)
      }.onFailure {
        logMsg { "server send message error:${it.stackTraceToString()}" }
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    _server.stop()
  }
}
