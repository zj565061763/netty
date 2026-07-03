package com.sd.demo.netty

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.sd.lib.netty.client.NettyClient

class SampleActivity : ComponentActivity() {
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
  vm: SampleViewModel = viewModel(),
) {
  val clients by vm.clients.collectAsStateWithLifecycle()

  Column(
    modifier = modifier
      .fillMaxSize()
      .safeContentPadding()
  ) {
    Row(modifier = Modifier.fillMaxWidth()) {
      BasicTextField(
        state = vm.serverIPInputState,
        modifier = Modifier.weight(1f),
      )
      BasicTextField(
        state = vm.serverPortInputState,
        modifier = Modifier.weight(1f),
      )
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = "Clients: ${clients.size}", modifier = Modifier.weight(1f))
      IconButton(onClick = { vm.addClient() }) {
        Icon(Icons.Default.Add, contentDescription = "Add Client")
      }
    }

    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
    ) {
      items(clients) { client ->
        ClientItem(
          client = client,
          onSendMessage = { vm.sendMessage(client, it) },
          onDisconnect = { client.disconnect() }
        )
        HorizontalDivider()
      }
    }
  }
}

@Composable
private fun ClientItem(
  client: NettyClient,
  onSendMessage: (String) -> Unit,
  onDisconnect: () -> Unit,
) {
  var text by remember { mutableStateOf("") }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(8.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text = "Client: ${client.hashCode()}", modifier = Modifier.weight(1f))
      IconButton(onClick = onDisconnect) {
        Icon(Icons.Default.Close, contentDescription = "Disconnect")
      }
    }

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