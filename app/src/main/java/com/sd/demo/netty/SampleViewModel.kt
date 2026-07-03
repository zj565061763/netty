package com.sd.demo.netty

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sd.lib.netty.client.NettyClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

class SampleViewModel : ViewModel() {
  private val _clients = MutableStateFlow<List<NettyClient>>(emptyList())
  val clients = _clients.asStateFlow()

  fun addClient() {
    viewModelScope.launch {
      runCatching {
        val client = NettyClient(host = "127.0.0.1", port = App.server.port)
        client.connect()
        _clients.update { it + client }
        initClient(client)
      }.onFailure { logMsg { "client addClient error:${it.stackTraceToString()}" } }
    }
  }

  fun sendMessage(client: NettyClient, message: String) {
    viewModelScope.launch {
      runCatching { client.send(message) }
        .onFailure { logMsg { "client sendMessage error:${it.stackTraceToString()}" } }
    }
  }

  private fun initClient(client: NettyClient) {
    viewModelScope.launch {
      val job = coroutineContext.job
      launch {
        client.connectionStateFlow.collect { state ->
          if (state == NettyClient.ConnectionState.DISCONNECTED) {
            _clients.update { it - client }
            job.cancel()
          }
        }
      }
      launch {
        client.messageFlow.collect { message ->
          logMsg { "client message:$message" }
        }
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    _clients.value.forEach { it.disconnect() }
  }
}
