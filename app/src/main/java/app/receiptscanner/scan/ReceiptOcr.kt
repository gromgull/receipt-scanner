package app.receiptscanner.scan

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** On-device OCR with ML Kit Text Recognition v2 (Latin script, bundled model). */
object ReceiptOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun readLines(context: Context, image: Uri): List<OcrLine> {
        val input = InputImage.fromFilePath(context, image)
        val text = recognizer.process(input).await()
        return text.textBlocks
            .flatMap { it.lines }
            .map { line ->
                val box = line.boundingBox
                OcrLine(line.text, top = box?.top ?: 0, height = box?.height() ?: 0)
            }
    }
}
