package app.gyrolet.mpvrx.ui.browser.localwatch

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.URLConnection
import java.util.concurrent.TimeUnit

data class LocalWatchVideo(
  val id: String,
  val name: String,
  val size: Long,
  val mimeType: String,
  val modifiedAt: Long,
)

data class LocalWatchUiState(
  val address: String,
  val serverBase: String? = null,
  val videos: List<LocalWatchVideo> = emptyList(),
  val connecting: Boolean = false,
  val loading: Boolean = false,
  val pinRequired: Boolean = false,
  val pin: String = "",
  val error: String? = null,
)

class LocalWatchViewModel(application: Application) : AndroidViewModel(application) {
  private val preferences = application.getSharedPreferences("local_watch", Application.MODE_PRIVATE)
  private val _state = MutableStateFlow(LocalWatchUiState(address = preferences.getString("address", "") ?: ""))
  val state: StateFlow<LocalWatchUiState> = _state.asStateFlow()
  private var api: LocalWatchApi? = null

  fun updateAddress(value: String) = _state.update { it.copy(address = value, error = null) }
  fun updatePin(value: String) = _state.update { it.copy(pin = value.filter(Char::isDigit).take(12), error = null) }
  fun showError(message: String) = _state.update { it.copy(error = message) }

  fun connect(address: String = _state.value.address) {
    viewModelScope.launch {
      val base = normalizeLocalWatchUrl(address)
      if (base == null) {
        _state.update { it.copy(address = address, error = "Enter a valid LocalWatch link, such as http://192.168.1.20:8080") }
        return@launch
      }
      val client = LocalWatchApi(base)
      api = client
      _state.update {
        it.copy(address = base, serverBase = base, connecting = true, loading = false, pinRequired = false, pin = "", videos = emptyList(), error = null)
      }
      preferences.edit().putString("address", base).apply()
      try {
        val needsPin = withContext(Dispatchers.IO) { client.checkStatusNeedsPin() }
        if (needsPin) {
          _state.update { it.copy(connecting = false, pinRequired = true) }
        } else {
          loadVideos(client)
        }
      } catch (error: Exception) {
        api = null
        _state.update {
          it.copy(
            connecting = false,
            loading = false,
            error = error.message ?: "Can't reach LocalWatch. Connect both phones to the same Wi-Fi or hotspot."
          )
        }
      }
    }
  }

  fun authenticate() {
    val client = api ?: return connect()
    val pin = _state.value.pin
    if (pin.isBlank()) {
      _state.update { it.copy(error = "Enter the PIN shown by the LocalWatch host.") }
      return
    }
    viewModelScope.launch {
      _state.update { it.copy(connecting = true, error = null) }
      try {
        withContext(Dispatchers.IO) { client.authenticate(pin) }
        loadVideos(client)
      } catch (error: Exception) {
        _state.update { it.copy(connecting = false, error = error.message ?: "LocalWatch sign-in failed.") }
      }
    }
  }

  fun refresh() {
    val client = api ?: return connect()
    viewModelScope.launch {
      _state.update { it.copy(loading = true, error = null) }
      try {
        val videos = withContext(Dispatchers.IO) { client.fetchVideos() }
        _state.update { it.copy(videos = videos, loading = false, pinRequired = false) }
      } catch (error: Exception) {
        _state.update {
          it.copy(loading = false, error = error.message ?: "Unable to refresh the LocalWatch library.")
        }
      }
    }
  }

  private suspend fun loadVideos(client: LocalWatchApi) {
    _state.update { it.copy(connecting = false, loading = true, pinRequired = false, error = null) }
    try {
      val videos = withContext(Dispatchers.IO) { client.fetchVideos() }
      _state.update { it.copy(connecting = false, loading = false, pinRequired = false, videos = videos, pin = "", error = null) }
    } catch (error: Exception) {
      _state.update {
        it.copy(connecting = false, loading = false, error = error.message ?: "Unable to read the LocalWatch library.")
      }
    }
  }

  fun playbackIntent(video: LocalWatchVideo): Intent? {
    val base = _state.value.serverBase ?: return null
    val url = Uri.parse("$base/stream")
      .buildUpon()
      .appendQueryParameter("id", video.id)
      .build()
    // LocalWatch serves every item from the extensionless /stream endpoint. Pass its MIME type
    // through the Intent so Rx's player can classify the item before libmpv probes the response.
    val mimeType =
      video.mimeType.takeIf { it.startsWith("video/", ignoreCase = true) }
        ?: URLConnection.guessContentTypeFromName(video.name)
          ?.takeIf { it.startsWith("video/", ignoreCase = true) }
        ?: "video/*"
    val cookie = api?.cookieHeader().orEmpty()
    return Intent(Intent.ACTION_VIEW).apply {
      setClass(getApplication(), app.gyrolet.mpvrx.ui.player.PlayerActivity::class.java)
      setDataAndType(url, mimeType)
      addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
      putExtra("title", video.name)
      putExtra("launch_source", "local_watch")
      putExtra("headers", if (cookie.isBlank()) {
        arrayOf("User-Agent", "mpvRx LocalWatch")
      } else {
        arrayOf("User-Agent", "mpvRx LocalWatch", "Cookie", cookie)
      })
    }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          if (modelClass.isAssignableFrom(LocalWatchViewModel::class.java)) {
            return LocalWatchViewModel(application) as T
          }
          throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
      }
  }
}

internal fun normalizeLocalWatchUrl(input: String): String? {
  var candidate = input.trim()
  if (candidate.isEmpty()) return null
  if (!candidate.contains("://")) candidate = "http://$candidate"
  val parsed = candidate.toHttpUrlOrNull() ?: return null
  if (parsed.scheme != "http" && parsed.scheme != "https") return null
  if (parsed.host.isBlank()) return null
  return parsed.newBuilder()
    .username("")
    .password("")
    .encodedPath("/")
    .query(null)
    .fragment(null)
    .build()
    .toString()
    .trimEnd('/')
}

private class LocalWatchApi(private val baseUrl: String) {
  private val cookies = object : CookieJar {
    @Volatile private var stored: List<Cookie> = emptyList()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
      synchronized(this) {
        stored = (stored.filterNot { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } } + cookies)
      }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = stored.filter { it.matches(url) }
    fun header(url: HttpUrl): String = loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }
  }

  private val http = OkHttpClient.Builder()
    .cookieJar(cookies)
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(25, TimeUnit.SECONDS)
    .build()

  fun checkStatusNeedsPin(): Boolean {
    val response = http.newCall(Request.Builder().url("$baseUrl/api/status").get().build()).execute()
    response.use {
      if (it.code == 401) return true
      if (!it.isSuccessful) throw IOException("LocalWatch returned HTTP ${it.code}. Check the link and try again.")
      val json = JSONObject(it.body?.string().orEmpty())
      if (!json.optBoolean("running", false)) throw IOException("The LocalWatch server is not running.")
      return false
    }
  }

  fun authenticate(pin: String) {
    val body = FormBody.Builder().add("pin", pin).build()
    val response = http.newCall(Request.Builder().url("$baseUrl/auth").post(body).build()).execute()
    response.use {
      if (!it.isSuccessful) throw IOException("That PIN was not accepted. Check it on the LocalWatch host and try again.")
    }
  }

  fun fetchVideos(): List<LocalWatchVideo> {
    val response = http.newCall(Request.Builder().url("$baseUrl/api/videos").get().build()).execute()
    response.use {
      if (it.code == 401) throw IOException("LocalWatch needs its PIN again. Reconnect to the server.")
      if (!it.isSuccessful) throw IOException("Couldn't load videos (HTTP ${it.code}).")
      val items = JSONObject(it.body?.string().orEmpty()).optJSONArray("videos") ?: return emptyList()
      return buildList(items.length()) {
        for (index in 0 until items.length()) {
          val item = items.optJSONObject(index) ?: continue
          val id = item.optString("id")
          if (id.isNotBlank()) {
            add(
              LocalWatchVideo(
                id = id,
                name = item.optString("name", "Untitled video"),
                size = item.optLong("size", 0L),
                mimeType = item.optString("mimeType", "video/mp4"),
                modifiedAt = item.optLong("modifiedAt", 0L),
              )
            )
          }
        }
      }.sortedBy { it.name.lowercase() }
    }
  }

  fun cookieHeader(): String {
    val url = "$baseUrl/stream".toHttpUrlOrNull() ?: return ""
    return cookies.header(url)
  }
}

private class IOException(message: String) : Exception(message)
