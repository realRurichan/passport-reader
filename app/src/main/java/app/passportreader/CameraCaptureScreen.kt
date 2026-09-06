package io.github.realrurichan.passportreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.realrurichan.passportreader.ocr.OfflineTextRecognizer
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun CameraCaptureScreen(singleLineMrz: Boolean, onRecognized: (String) -> Unit, onCancel: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { OfflineTextRecognizer() }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetResolution(Size(2560, 1440))
            .build()
    }
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
        Box(
            Modifier
                .fillMaxWidth(0.9f)
                .aspectRatio(1.586f)
                .align(Alignment.Center)
                .border(3.dp, Color(0xFF70E1B1), RoundedCornerShape(14.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(if (singleLineMrz) 0.16f else 0.34f)
                    .align(Alignment.BottomCenter)
                    .border(2.dp, Color(0xFFFFD166), RectangleShape)
            )
            Text("将证件边缘对齐绿色框\n${if (singleLineMrz) "单行机读码" else "机读码"}放入黄色区域", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(10.dp))
        }
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
                try {
                    val upright = loadUprightBitmap(temporary)
                    val document = cropDocumentFrame(upright)
                    onRecognized(recognizer.recognize(document).text)
                    if (document !== upright) document.recycle()
                    upright.recycle()
                }
                catch (error: Exception) { onError("识别失败：${error.message}") }
                finally { temporary.delete() }
            }
        }
        override fun onError(error: ImageCaptureException) { temporary.delete(); onError("拍摄失败：${error.message}") }
    })
}

private fun cropDocumentFrame(bitmap: Bitmap): Bitmap {
    val width = (bitmap.width * 0.9f).toInt()
    val height = (width / 1.586f).toInt().coerceAtMost((bitmap.height * 0.9f).toInt())
    val left = (bitmap.width - width) / 2
    val top = (bitmap.height - height) / 2
    return Bitmap.createBitmap(bitmap, left, top, width, height)
}

private fun loadUprightBitmap(file: File): Bitmap {
    val source = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
    val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return source
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees) }, true)
        .also { if (it !== source) source.recycle() }
}
