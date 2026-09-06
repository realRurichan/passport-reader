package io.github.realrurichan.passportreader

import android.content.Context
import android.graphics.BitmapFactory
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.realrurichan.passportreader.ocr.OfflineTextRecognizer
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun CameraCaptureScreen(onRecognized: (String) -> Unit, onCancel: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { OfflineTextRecognizer() }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { recognizer.close() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx ->
            PreviewView(ctx).also { view ->
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({ runCatching {
                    val provider = future.get(); val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    provider.unbindAll(); provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }.onFailure { onError("无法启动相机：${it.message}") } }, ContextCompat.getMainExecutor(ctx))
            }
        }, modifier = Modifier.fillMaxSize())
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) { Text("取消", color = Color.White) }
        Card(Modifier.align(Alignment.BottomCenter).padding(20.dp)) { Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (busy) "正在离线识别…" else "保持证件平整，避免反光"); Spacer(Modifier.height(10.dp))
            Button(enabled = !busy, onClick = {
                busy = true; captureAndRecognize(context, capture, recognizer, onRecognized, onError)
            }) { Text("拍摄") }
        } }
    }
}

private fun captureAndRecognize(context: Context, capture: ImageCapture, recognizer: OfflineTextRecognizer, onRecognized: (String) -> Unit, onError: (String) -> Unit) {
    val temporary = File.createTempFile("document-", ".jpg", context.cacheDir)
    capture.takePicture(ImageCapture.OutputFileOptions.Builder(temporary).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
            kotlinx.coroutines.MainScope().launch {
                try { onRecognized(recognizer.recognize(requireNotNull(BitmapFactory.decodeFile(temporary.absolutePath))).text) }
                catch (error: Exception) { onError("识别失败：${error.message}") }
                finally { temporary.delete() }
            }
        }
        override fun onError(error: ImageCaptureException) { temporary.delete(); onError("拍摄失败：${error.message}") }
    })
}
