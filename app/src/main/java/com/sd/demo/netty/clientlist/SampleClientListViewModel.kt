package com.sd.demo.netty.clientlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sd.demo.netty.logMsg
import com.sd.demo.netty.safeRunCatching
import com.sd.lib.netty.client.NettyClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

class SampleClientListViewModel : ViewModel() {
  private val _clients = MutableStateFlow<List<NettyClient>>(emptyList())
  val clients = _clients.asStateFlow()

  val serverIPInputState = TextFieldState("127.0.0.1")
  val serverPortInputState = TextFieldState("8888")

  fun addClient() {
    viewModelScope.launch {
      safeRunCatching {
        val client = NettyClient(
          host = serverIPInputState.text.toString(),
          port = serverPortInputState.text.toString().toInt()
        )
        client.connect()
        _clients.update { it + client }
        initClient(client)
      }.onFailure {
        logMsg { "client addClient error:${it.stackTraceToString()}" }
      }
    }
  }

  fun sendMessage(client: NettyClient, message: String) {
    viewModelScope.launch {
      safeRunCatching {
        client.send(message)
      }.onFailure {
        logMsg { "client sendMessage error:${it.stackTraceToString()}" }
      }
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
