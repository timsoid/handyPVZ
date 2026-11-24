package dev.handypvz.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.oned.Code128Writer

object BarcodeRenderer {

    /**
     * Белая скруглённая карточка с чёрным штрихкодом Code128 по центру.
     * Штрихи рисуются целыми пикселями (без сглаживания), чтобы сканер видел чёткие границы.
     * Ширина штриха выбирается ZXing как целое число пикселей на модуль, поля вокруг —
     * белые внутри карточки.
     */
    fun renderCard(
        text: String,
        width: Int,
        height: Int,
        cornerRadius: Float,
        barHeight: Int
    ): Bitmap? {
        return try {
            val matrix = Code128Writer().encode(text, BarcodeFormat.CODE_128, width, 1)
            val cardWidth = matrix.width
            val bitmap = Bitmap.createBitmap(cardWidth, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(
                RectF(0f, 0f, cardWidth.toFloat(), height.toFloat()),
                cornerRadius,
                cornerRadius,
                paint
            )

            paint.isAntiAlias = false
            paint.color = Color.BLACK
            val top = ((height - barHeight) / 2).toFloat()
            val bottom = top + barHeight
            var x = 0
            while (x < cardWidth) {
                if (matrix[x, 0]) {
                    var end = x
                    while (end < cardWidth && matrix[end, 0]) end++
                    canvas.drawRect(x.toFloat(), top, end.toFloat(), bottom, paint)
                    x = end
                } else {
                    x++
                }
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
