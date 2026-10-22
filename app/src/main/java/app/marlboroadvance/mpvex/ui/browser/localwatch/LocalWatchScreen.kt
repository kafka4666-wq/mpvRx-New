package app.marlboroadvance.mpvex.ui.browser.localwatch

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.presentation.Screen
import kotlinx.serialization.Serializable

@Serializable
object LocalWatchScreen : Screen {
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val model: LocalWatchViewModel = viewModel(
      factory = LocalWatchViewModel.factory(context.applicationContext as android.app.Application)
    )
    val state by model.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var showScanner by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) showScanner = true
      else model.showError("Camera permission is needed to scan a QR code. You can still paste the LocalWatch link.")
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("Local Watch", fontWeight = FontWeight.SemiBold) },
          actions = {
            IconButton(onClick = model::refresh, enabled = !state.loading && !state.connecting) {
              Icon(Icons.Filled.Refresh, contentDescription = "Refresh LocalWatch library")
            }
          },
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(24.dp),
          ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                  Text("Connect to a LocalWatch host", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                  Text("Scan its QR code or paste its local link.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                  value = state.address,
                  onValueChange = model::updateAddress,
                  modifier = Modifier.weight(1f),
                  label = { Text("LocalWatch link") },
                  placeholder = { Text("http://192.168.1.20:8080") },
                  leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                  singleLine = true,
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                  onClick = { clipboard.getText()?.text?.let(model::updateAddress) },
                ) {
                  Icon(Icons.Filled.ContentPaste, contentDescription = "Paste link")
                }
              }
              Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                  onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                      showScanner = true
                    } else {
                      cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                  },
                  modifier = Modifier.weight(1f),
                  enabled = !state.connecting,
                ) {
                  Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                  Spacer(Modifier.width(8.dp))
                  Text("Scan QR")
                }
                Button(
                  onClick = { model.connect() },
                  modifier = Modifier.weight(1f),
                  enabled = state.address.isNotBlank() && !state.connecting,
                ) {
                  if (state.connecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                  }
                  Text(if (state.connecting) "Connecting" else "Connect")
                }
              }
              Text(
                "Local Wi-Fi or hotspot only. Scanning and playback do not use mobile data or the internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        if (state.pinRequired) {
          item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
              Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  Spacer(Modifier.width(8.dp))
                  Text("This LocalWatch host needs a PIN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                  value = state.pin,
                  onValueChange = model::updatePin,
                  modifier = Modifier.fillMaxWidth(),
                  label = { Text("Viewer PIN") },
                  singleLine = true,
                  visualTransformation = PasswordVisualTransformation(),
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Button(onClick = model::authenticate, enabled = state.pin.isNotBlank() && !state.connecting) {
                  Text(if (state.connecting) "Checking PIN…" else "Unlock library")
                }
              }
            }
          }
        }

        state.error?.let { message ->
          item {
            Text(
              text = message,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(horizontal = 4.dp),
            )
          }
        }

        if (state.serverBase != null && !state.pinRequired) {
          item {
            Row(
              modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(Modifier.weight(1f)) {
                Text("Videos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                  "${state.videos.size} available · ${state.serverBase}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
          }
          if (state.videos.isNotEmpty()) {
            item {
              OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Find a video") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
              )
            }
            val filtered = state.videos.filter { it.name.contains(searchText, ignoreCase = true) }
            if (filtered.isEmpty()) {
              item { Text("No videos match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
            } else {
              items(filtered, key = { it.id }) { video ->
                Card(
                  modifier = Modifier.fillMaxWidth().clickable {
                    model.playbackIntent(video)?.let { intent -> context.startActivity(intent) }
                  },
                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                  shape = RoundedCornerShape(18.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Surface(
                      shape = RoundedCornerShape(14.dp),
                      color = MaterialTheme.colorScheme.primaryContainer,
                      modifier = Modifier.size(48.dp),
                    ) {
                      Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                      }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                      Text(video.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                      Spacer(Modifier.height(3.dp))
                      Text(
                        "${Formatter.formatShortFileSize(context, video.size)} · ${video.mimeType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${video.name}", tint = MaterialTheme.colorScheme.primary)
                  }
                }
              }
            }
          } else if (!state.loading) {
            item {
              Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                  Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                  Spacer(Modifier.height(8.dp))
                  Text("No videos are available", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                  Text("Choose or rescan a video folder in the LocalWatch host app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
          }
        }
      }
    }

    if (showScanner) {
      Dialog(
        onDismissRequest = { showScanner = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
      ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(0.92f)) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
              Text("Scan LocalWatch QR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
              TextButton(onClick = { showScanner = false }) { Text("Cancel") }
            }
            Text("Point the camera at the QR code displayed by the LocalWatch host.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().height(420.dp)) {
              LocalWatchQrScanner(
                onQrCode = { value ->
                  showScanner = false
                  model.updateAddress(value)
                  model.connect(value)
                }
              )
            }
          }
        }
      }
    }
  }
}
