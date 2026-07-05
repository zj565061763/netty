package com.sd.demo.netty.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sd.demo.netty.logMsg
import com.sd.lib.netty.client.NettyClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class SampleClientViewModel : ViewModel() {
  var host by mutableStateOf("127.0.0.1")
  var port by mutableStateOf("8888")

  private var _client: NettyClient? = null

  private val _stateFlow = MutableStateFlow(NettyClient.ConnectionState.DISCONNECTED)
  private val _messagesFlow = MutableStateFlow<List<String>>(emptyList())

  val stateFlow = _stateFlow.asStateFlow()
  val messagesFlow = _messagesFlow.asStateFlow()

  /** 错误信息 */
  var error by mutableStateOf<String?>(null)
    private set

  private var _connectJob: Job? = null

  fun connect() {
    val portInt = port.toIntOrNull() ?: run {
      error = "Invalid port"
      return
    }

    val oldJob = _connectJob
    _connectJob = viewModelScope.launch {
      oldJob?.cancelAndJoin()
      error = null

      val client = NettyClient(host, portInt)
      _client = client

      launch {
        client.connectionStateFlow.collect {
          _stateFlow.value = it
        }
      }

      launch {
        client.messageFlow.collect { msg ->
          _messagesFlow.value += "Received: $msg"
        }
      }

      runCatching {
        client.connect()
      }.onFailure { e ->
        if (e is CancellationException) throw e
        error = e.toString()
      }
    }
  }

  fun disconnect() {
    _client?.disconnect()
    _client = null
    _connectJob?.cancel()
    _connectJob = null
    _messagesFlow.value = emptyList()
    error = null
  }

  fun sendMessage(message: String) {
    val client = _client ?: return
    viewModelScope.launch {
      runCatching {
        client.send(message)
        _messagesFlow.value += "Sent: $message"
      }.onFailure {
        logMsg { "send error: ${it.stackTraceToString()}" }
      }
    }
  }

  fun clearError() {
    error = null
  }

  override fun onCleared() {
    super.onCleared()
    _client?.disconnect()
  }
}
