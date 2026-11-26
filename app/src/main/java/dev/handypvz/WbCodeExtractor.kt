package dev.handypvz

import android.graphics.BitmapFactory
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONTokener

/** Extracts the QR image shown on the delivery page; never logs the image or its contents. */
object WbCodeExtractor {
    const val JS = """
        (function() {
            var image = document.querySelector('.delivery-qr__code-wrap img');
            var src = image ? image.src : '';
            return src.length <= 262144 ? src : '';
        })();
    """

    /** The callback from evaluateJavascript contains a JSON-encoded string. */
    fun parseImage(raw: String): String? = try {
        val value = JSONTokener(raw).nextValue() as? String
        value?.takeIf { it.startsWith(PNG_PREFIX) && it.length <= MAX_DATA_LENGTH }
    } catch (_: Exception) {
        null
    }

    /** Decode off the UI thread. An absent or invalid image is not evidence of no orders. */
    fun decode(image: String): String? {
        if (!image.startsWith(PNG_PREFIX) || image.length > MAX_DATA_LENGTH) return null
        return try {
            val bytes = Base64.decode(image.substring(PNG_PREFIX.length), Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (bounds.outMimeType != "image/png" || width <= 0 || height <= 0 ||
                width.toLong() * height > MAX_PIXELS
            ) return null

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            try {
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val source = RGBLuminanceSource(width, height, pixels)
                val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
                MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)
                    .text.takeIf { it.isNotEmpty() }
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    private const val PNG_PREFIX = "data:image/png;base64,"
    private const val MAX_DATA_LENGTH = 262_144
    private const val MAX_PIXELS = 4_000_000L
}
