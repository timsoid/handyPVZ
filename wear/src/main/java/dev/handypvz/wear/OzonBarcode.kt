package dev.handypvz.wear

internal object OzonBarcode {
    private val pattern = Regex("""(\d{4,5})(\d{4})\*(\d{4})""")

    fun isValid(code: String): Boolean = pattern.matches(code)

    fun format(code: String): String =
        pattern.matchEntire(code)?.destructured?.let { (a, b, c) -> "$a $b * $c" } ?: code
}
