package dev.handypvz.wear

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

object QrRenderer {
    /** A white bitmap with integer-pixel modules and at least four quiet-zone modules. */
    fun render(text: String, size: Int): Bitmap? {
        val matrix = matrix(text, size) ?: return null
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    internal fun matrix(text: String, size: Int): BitMatrix? {
        if (text.isEmpty() || size <= 0 || size > MAX_SIZE) return null
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 4
            )
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
                .takeIf { it.width == size && it.height == size }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: com.google.zxing.WriterException) {
            null
        }
    }

    private const val MAX_SIZE = 2048
}
