package io.github.realrurichan.twowaypermitreader.ocr

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
    var debugDump: ((Bitmap, String) -> Unit)? = null
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognize(bitmap: Bitmap, mrzBandFraction: Float = 0.34f): OcrText =
        recognizeBand(bitmap, mrzBandFraction)

    private suspend fun recognizeBand(bitmap: Bitmap, bandFraction: Float): OcrText {
        val bandTop = (bitmap.height * (1f - bandFraction)).toInt()
        val band = Bitmap.createBitmap(bitmap, 0, bandTop, bitmap.width, bitmap.height - bandTop)
        debugDump?.invoke(band, "band-${String.format("%.2f", bandFraction).replace('.', '_')}.png")
        val bandScale = (1800f / band.width).coerceAtLeast(1f).coerceAtMost(2f)
        val enlargedBand = if (bandScale > 1f) Bitmap.createScaledBitmap(band, (band.width * bandScale).toInt(), (band.height * bandScale).toInt(), true) else band
        val results = listOf(latin to InputImage.fromBitmap(enlargedBand, 0), chinese to InputImage.fromBitmap(enlargedBand, 0))
            .map { (recognizer, image) ->
                suspendCancellableCoroutine { continuation ->
                    recognizer.process(image)
                        .addOnSuccessListener { continuation.resume(it) }
                        .addOnFailureListener { continuation.resumeWithException(it) }
                }
            }
        val texts = results.map { it.text.trim() }.filter(String::isNotBlank).distinct()
        if (enlargedBand !== band) enlargedBand.recycle()
        band.recycle()
        return OcrText(texts.joinToString("\n"), texts.map { OcrBlock(it, null) })
    }

    fun close() { latin.close(); chinese.close() }
}
