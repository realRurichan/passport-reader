package io.github.realrurichan.passportreader.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class OcrText(val text: String, val blocks: List<OcrBlock>)
data class OcrBlock(val text: String, val confidence: Float?)

class OfflineTextRecognizer {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognize(bitmap: Bitmap): OcrText {
        val image = InputImage.fromBitmap(bitmap, 0)
        val results = listOf(latin, chinese).map { recognizer ->
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        }
        val lines = results.flatMap { it.textBlocks }.distinctBy { it.text }
        return OcrText(lines.joinToString("\n") { it.text }, lines.map { OcrBlock(it.text, null) })
    }

    fun close() { latin.close(); chinese.close() }
}
