package dev.handypvz.wear

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrRendererTest {
    @Test
    fun qrRoundTripsAndHasQuietZone() {
        val value = "пример_123456_1"
        val matrix = requireNotNull(QrRenderer.matrix(value, 240))
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        )
        assertEquals(value, decoded.text)

        val bounds = requireNotNull(matrix.enclosingRectangle)
        // The finder pattern's top edge starts with seven consecutive modules.
        var finderWidth = 0
        while (matrix[bounds[0] + finderWidth, bounds[1]]) finderWidth++
        val moduleSize = finderWidth / 7
        assertTrue(moduleSize >= 1)
        assertTrue(bounds[0] >= 4 * moduleSize)
        assertTrue(bounds[1] >= 4 * moduleSize)
        assertTrue(matrix.width - bounds[0] - bounds[2] >= 4 * moduleSize)
        assertTrue(matrix.height - bounds[1] - bounds[3] >= 4 * moduleSize)
    }

    @Test
    fun rejectsInvalidOrTooSmallOutput() {
        assertNull(QrRenderer.matrix("", 240))
        assertNull(QrRenderer.matrix("пример_123456_1", 1))
        assertNull(QrRenderer.matrix("пример_123456_1", 0))
    }
}
