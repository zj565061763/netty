package com.sd.demo.netty.server

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sd.demo.netty.theme.AppTheme
import com.sd.lib.netty.server.NettyServer

class SampleServer : ComponentActivity() {
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
  modifier: Modifier = Modifier,
  vm: SampleServerViewModel = viewModel(),
) {
  val state by vm.serverStateFlow.collectAsStateWithLifecycle()
  val clients by vm.clientsFlow.collectAsStateWithLifecycle()

  Column(
    modifier = modifier
      .fillMaxSize()
      .safeContentPadding()
  ) {
    Text(
      text = "Server State: $state",
      modifier = Modifier.padding(8.dp)
    )
    Text(
      text = "Port: ${vm.port}",
      modifier = Modifier.padding(8.dp)
    )

    HorizontalDivider()

    Text(
      text = "Connected Clients: ${clients.size}",
      modifier = Modifier.padding(8.dp)
    )

    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
    ) {
      items(clients) { client ->
        ClientItem(
          client = client,
          onSendMessage = { vm.sendMessage(client, it) }
        )
        HorizontalDivider()
      }
    }
  }
}

@Composable
private fun ClientItem(
  client: NettyServer.Client,
  onSendMessage: (String) -> Unit,
) {
  var text by remember { mutableStateOf("") }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(8.dp)
  ) {
    Text(text = "Client ID: ${client.id}")
    Text(text = "Address: ${client.remoteAddress}")

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("Enter message") }
      )
      Button(
        onClick = {
          onSendMessage(text)
          text = ""
        },
        modifier = Modifier.padding(start = 8.dp)
      ) {
        Text("Send")
      }
    }
  }
}
