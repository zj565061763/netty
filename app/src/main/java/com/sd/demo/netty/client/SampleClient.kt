package com.sd.demo.netty.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sd.demo.netty.theme.AppTheme
import com.sd.lib.netty.client.NettyClient

class SampleClient : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      AppTheme {
        Content()
      }
    }
  }
}

@Composable
private fun Content(
  vm: SampleClientViewModel = viewModel(),
) {
  val state by vm.stateFlow.collectAsStateWithLifecycle()
  val error = vm.error

  Scaffold(
    modifier = Modifier
      .fillMaxSize()
      .safeContentPadding(),
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .padding(innerPadding)
        .fillMaxSize()
    ) {
      if (error != null) {
        ErrorView(error = error, onBack = { vm.clearError() })
      } else {
        when (state) {
          NettyClient.ConnectionState.DISCONNECTED -> {
            DisconnectedView(
              host = vm.host,
              onHostChange = { vm.host = it },
              port = vm.port,
              onPortChange = { vm.port = it },
              onConnect = { vm.connect() }
            )
          }

          NettyClient.ConnectionState.CONNECTING -> {
            LoadingView()
          }

          NettyClient.ConnectionState.CONNECTED -> {
            ConnectedView(
              host = vm.host,
              port = vm.port,
              messages = vm.messagesFlow.collectAsStateWithLifecycle().value,
              onSendMessage = { vm.sendMessage(it) },
              onDisconnect = { vm.disconnect() }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DisconnectedView(
  host: String,
  onHostChange: (String) -> Unit,
  port: String,
  onPortChange: (String) -> Unit,
  onConnect: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    TextField(
      value = host,
      onValueChange = { input ->
        if (input.all { it.isDigit() || it == '.' }) {
          onHostChange(input)
        }
      },
      label = { Text("Server IP") },
      modifier = Modifier.fillMaxWidth(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Spacer(modifier = Modifier.height(8.dp))
    TextField(
      value = port,
      onValueChange = { input ->
        if (input.all { it.isDigit() }) {
          onPortChange(input)
        }
      },
      label = { Text("Port") },
      modifier = Modifier.fillMaxWidth(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onConnect) {
      Text("Connect")
    }
  }
}

@Composable
private fun LoadingView() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
  }
}

@Composable
private fun ErrorView(error: String, onBack: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Text(text = error, color = MaterialTheme.colorScheme.error)
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onBack) {
      Text("Back")
    }
  }
}

@Composable
private fun ConnectedView(
  host: String,
  port: String,
  messages: List<String>,
  onSendMessage: (String) -> Unit,
  onDisconnect: () -> Unit,
) {
  var inputText by remember { mutableStateOf("") }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(text = "Connected: $host:$port", style = MaterialTheme.typography.titleMedium)
      IconButton(onClick = onDisconnect) {
        Icon(Icons.Default.Close, contentDescription = "Disconnect")
      }
    }

    HorizontalDivider()

    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .padding(8.dp),
      reverseLayout = true
    ) {
      items(messages.reversed()) { msg ->
        Text(text = msg, modifier = Modifier.padding(vertical = 4.dp))
      }
    }

    HorizontalDivider()

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextField(
        value = inputText,
        onValueChange = { inputText = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("Message") }
      )
      Spacer(modifier = Modifier.width(8.dp))
      Button(onClick = {
        if (inputText.isNotBlank()) {
          onSendMessage(inputText)
          inputText = ""
        }
      }) {
        Text("Send")
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun PreviewDisconnectedView() {
  AppTheme {
    DisconnectedView(
      host = "192.168.1.100",
      onHostChange = {},
      port = "8888",
      onPortChange = {},
      onConnect = {}
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun PreviewLoadingView() {
  AppTheme {
    LoadingView()
  }
}

@Preview(showBackground = true)
@Composable
private fun PreviewErrorView() {
  AppTheme {
    ErrorView(error = "Connection timeout", onBack = {})
  }
}

@Preview(showBackground = true)
@Composable
private fun PreviewConnectedView() {
  AppTheme {
    ConnectedView(
      host = "192.168.1.100",
      port = "8888",
      messages = listOf("Hello", "Hi there!", "How are you?"),
      onSendMessage = {},
      onDisconnect = {}
    )
  }
}
