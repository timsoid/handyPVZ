package dev.handypvz

import org.junit.Assert.assertEquals
import org.junit.Test

class OzonCodeExtractorTest {
    @Test
    fun `preserves complete eight and nine digit codes`() {
        assertEquals(OzonCodeExtractor.Extract.Code("12345678*1234"), OzonCodeExtractor.parse("\"12345678*1234\""))
        assertEquals(OzonCodeExtractor.Extract.Code("123456789*1234"), OzonCodeExtractor.parse("\"123456789*1234\""))
    }

    @Test
    fun `accepts grouped digits and nonbreaking spaces`() {
        assertEquals(OzonCodeExtractor.Extract.Code("123456789*1234"), OzonCodeExtractor.parse("123 456 789 * 12\u00a034"))
        assertEquals(OzonCodeExtractor.Extract.Code("12345678*1234"), OzonCodeExtractor.parse("1234\u202f5678*1234"))
    }

    @Test
    fun `does not truncate longer or incomplete numbers`() {
        for (text in listOf("1234567*1234", "1234567890*1234", "12 3456 7890*1234", "12345678*12345", "12345678*123")) {
            assertEquals(text, OzonCodeExtractor.Extract.None, OzonCodeExtractor.parse(text))
        }
    }

    @Test
    fun `finds a valid code after another candidate and distinguishes login`() {
        assertEquals(OzonCodeExtractor.Extract.Code("123456789*1234"),
            OzonCodeExtractor.parse("1234567890*1234\n123456789*1234"))
        assertEquals(OzonCodeExtractor.Extract.Login, OzonCodeExtractor.parse("\"__LOGIN__\""))
    }
}
