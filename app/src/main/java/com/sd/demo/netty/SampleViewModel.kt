package com.sd.demo.netty

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sd.lib.netty.client.NettyClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SampleViewModel : ViewModel() {
  private val _clients = MutableStateFlow<List<NettyClient>>(emptyList())
  val clients = _clients.asStateFlow()

  private val _clientJobs = ConcurrentHashMap<NettyClient, Job>()

  fun addClient() {
    viewModelScope.launch {
      try {
        val client = NettyClient(host = "127.0.0.1", port = App.server.port)
        client.connect()
        _clients.update { it + client }
        _clientJobs[client] = client.connectionStateFlow.onEach { state ->
          if (state == NettyClient.ConnectionState.DISCONNECTED) {
            _clients.update { it - client }
            _clientJobs.remove(client)?.cancel()
          }
        }.launchIn(viewModelScope)
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  fun sendMessage(client: NettyClient, message: String) {
    viewModelScope.launch {
      try {
        client.send(message)
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    _clients.value.forEach { it.disconnect() }
  }
}
