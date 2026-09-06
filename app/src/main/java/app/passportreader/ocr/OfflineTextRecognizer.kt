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
        val lowerStart = (bitmap.height * 0.62f).toInt()
        val lower = Bitmap.createBitmap(bitmap, 0, lowerStart, bitmap.width, bitmap.height - lowerStart)
        val lowerScale = (1800f / lower.width).coerceAtLeast(1f).coerceAtMost(2f)
        val enlargedLower = if (lowerScale > 1f) Bitmap.createScaledBitmap(lower, (lower.width * lowerScale).toInt(), (lower.height * lowerScale).toInt(), true) else lower
        val jobs = listOf(
            latin to InputImage.fromBitmap(enlargedLower, 0),
            latin to InputImage.fromBitmap(bitmap, 0),
            chinese to InputImage.fromBitmap(bitmap, 0),
        )
        val results = jobs.map { (recognizer, image) ->
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        }
        val texts = results.map { it.text.trim() }.filter(String::isNotBlank).distinct()
        if (enlargedLower !== lower) enlargedLower.recycle()
        lower.recycle()
        return OcrText(texts.joinToString("\n"), texts.map { OcrBlock(it, null) })
    }

    fun close() { latin.close(); chinese.close() }
}
