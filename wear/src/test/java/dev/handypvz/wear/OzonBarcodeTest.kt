package dev.handypvz.wear

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.oned.Code128Reader
import com.google.zxing.oned.Code128Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OzonBarcodeTest {
    @Test
    fun `accepts and formats both code lengths without dropping digits`() {
        assertTrue(OzonBarcode.isValid("12345678*1234"))
        assertTrue(OzonBarcode.isValid("123456789*1234"))
        assertEquals("1234 5678 * 1234", OzonBarcode.format("12345678*1234"))
        assertEquals("12345 6789 * 1234", OzonBarcode.format("123456789*1234"))
    }

    @Test
    fun `rejects incomplete or oversized codes`() {
        for (code in listOf("1234567*1234", "1234567890*1234", "12345678*123", "12345678*12345")) {
            assertFalse(code, OzonBarcode.isValid(code))
        }
    }

    @Test
    fun `nine digit code survives Code 128 encoding and decoding`() {
        val code = "123456789*1234"
        val matrix = Code128Writer().encode(code, BarcodeFormat.CODE_128, 440, 100)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        assertEquals(code, Code128Reader().decode(bitmap).text)
    }
}
