package app.marlboroadvance.mpvex.ui.browser.localwatch

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun LocalWatchQrScanner(
  onQrCode: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val previewView = remember {
    PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
  }

  AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())

  DisposableEffect(previewView, lifecycleOwner) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    val mainExecutor = ContextCompat.getMainExecutor(context)
    val analyzerExecutor = Executors.newSingleThreadExecutor()
    val providerDelivered = AtomicBoolean(false)
    var provider: ProcessCameraProvider? = null

    val reader = MultiFormatReader().apply {
      setHints(
        mapOf(
          DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
          DecodeHintType.TRY_HARDER to true,
        )
      )
    }
    val analysis = ImageAnalysis.Builder()
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build()

    analysis.setAnalyzer(analyzerExecutor) { image ->
      var decoded: String? = null
      try {
        val plane = image.planes.firstOrNull() ?: return@setAnalyzer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val bufferStart = buffer.position()
        val bytes = ByteArray(width * height)
        for (row in 0 until height) {
          for (column in 0 until width) {
            bytes[row * width + column] = buffer.get(bufferStart + row * rowStride + column * pixelStride)
          }
        }
        val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
        decoded = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
      } catch (_: Exception) {
        // Most camera frames do not contain a readable QR code; keep scanning.
      } finally {
        image.close()
        reader.reset()
      }
      val value = decoded
      if (!value.isNullOrBlank() && providerDelivered.compareAndSet(false, true)) {
        mainExecutor.execute { onQrCode(value) }
      }
    }

    val bindCamera = Runnable {
      runCatching {
        val cameraProvider = providerFuture.get()
        provider = cameraProvider
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
          lifecycleOwner,
          CameraSelector.DEFAULT_BACK_CAMERA,
          preview,
          analysis,
        )
      }
    }
    providerFuture.addListener(bindCamera, mainExecutor)

    onDispose {
      providerDelivered.set(true)
      provider?.unbindAll()
      analyzerExecutor.shutdownNow()
    }
  }
}
