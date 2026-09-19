package com.tattoostencil.pro.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Fast, deterministic image processing intended for tattoo-stencil preparation. */
object ImageProcessor {
    enum class FilterType(val displayName: String, val description: String) {
        CLEAN_LINES("Líneas limpias", "Contornos y detalles internos, fondo blanco"),
        FINE_DETAIL("Fine detail", "Más información interna y líneas finas"),
        THRESHOLD("Umbral", "Convierte zonas oscuras en líneas/masas de guía"),
        SKETCH("Sketch", "Boceto de referencia"),
        HIGH_CONTRAST("Alto contraste", "Contraste fuerte para transferencia"),
        INVERT("Invertido", "Invierte el resultado")
    }

    enum class LineColor(val displayName: String, val color: Int) {
        PURPLE("Morado", Color.rgb(115, 35, 180)),
        BLACK("Negro", Color.BLACK),
        BLUE("Azul", Color.rgb(30, 90, 210)),
        RED("Rojo", Color.rgb(190, 35, 45))
    }

    data class ProcessingSettings(
        val brightness: Float = 0f,
        val contrast: Float = 1.12f,
        val threshold: Int = 42,
        val blurRadius: Int = 1,
        val edgeStrength: Int = 45,
        val invertColors: Boolean = false,
        val smoothEdges: Boolean = true,
        val lineWidth: Int = 1,
        val removeNoise: Boolean = true,
        val preserveDetail: Boolean = true,
        val backgroundWhite: Boolean = true,
        val lineColor: LineColor = LineColor.PURPLE,
        val maxOutputSize: Int = 2400
    )

    fun processImage(bitmap: Bitmap, filterType: FilterType, settings: ProcessingSettings): Bitmap {
        val source = normalize(bitmap, settings.maxOutputSize)
        var gray = toGrayscale(source)
        if (settings.brightness != 0f || settings.contrast != 1f) {
            gray = adjustBrightnessContrast(gray, settings.brightness, settings.contrast)
        }
        if (settings.smoothEdges && settings.blurRadius > 0) {
            gray = gaussianBlur(gray, settings.blurRadius.coerceIn(1, 3))
        }

        val binary: BooleanArray
        val width = gray.width
        val height = gray.height
        binary = when (filterType) {
            FilterType.CLEAN_LINES -> edgeMask(gray, settings, fine = false)
            FilterType.FINE_DETAIL -> edgeMask(gray, settings, fine = true)
            FilterType.THRESHOLD -> thresholdMask(gray, settings.threshold)
            FilterType.SKETCH -> sketchMask(gray, settings.threshold)
            FilterType.HIGH_CONTRAST -> thresholdMask(gray, settings.threshold.coerceIn(1, 254))
            FilterType.INVERT -> invertMask(gray)
        }

        var mask = binary
        if (settings.removeNoise) mask = cleanMask(mask, width, height, if (settings.preserveDetail) 1 else 2)
        if (settings.lineWidth > 1) mask = thicken(mask, width, height, settings.lineWidth - 1)

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val line = settings.lineColor.color
        for (i in pixels.indices) {
            val on = mask[i]
            pixels[i] = if (on xor settings.invertColors) line else Color.WHITE
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    fun normalize(bitmap: Bitmap, maxSize: Int): Bitmap {
        if (bitmap.width <= maxSize && bitmap.height <= maxSize) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val scale = min(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)
    }

    fun toGrayscale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h); bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val g = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt().coerceIn(0, 255)
            dst[i] = Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(dst, 0, w, 0, 0, w, h) }
    }

    fun adjustBrightnessContrast(bitmap: Bitmap, brightness: Float, contrast: Float): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h); bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(src.size)
        val factor = contrast.coerceIn(0.1f, 3f)
        for (i in src.indices) {
            val v = ((Color.red(src[i]) - 128f) * factor + 128f + brightness).toInt().coerceIn(0, 255)
            dst[i] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(dst, 0, w, 0, 0, w, h) }
    }

    fun gaussianBlur(bitmap: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return bitmap
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h); bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val tmp = IntArray(src.size); val dst = IntArray(src.size)
        val size = radius * 2 + 1
        val kernel = IntArray(size)
        var sum = 0
        for (i in 0 until size) { val x = i - radius; kernel[i] = max(1, (1000 * kotlin.math.exp(-(x * x) / (2.0 * radius * radius))).toInt()); sum += kernel[i] }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (k in -radius..radius) s += Color.red(src[y * w + (x + k).coerceIn(0, w - 1)]) * kernel[k + radius]
            tmp[y * w + x] = s / sum
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (k in -radius..radius) s += tmp[(y + k).coerceIn(0, h - 1) * w + x] * kernel[k + radius]
            dst[y * w + x] = Color.rgb(s / sum, s / sum, s / sum)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(dst, 0, w, 0, 0, w, h) }
    }

    private fun edgeMask(bitmap: Bitmap, s: ProcessingSettings, fine: Boolean): BooleanArray {
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h); bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val mag = IntArray(w * h)
        var maxMag = 1
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val a = Color.red(src[i - w - 1]); val b = Color.red(src[i - w]); val c = Color.red(src[i - w + 1])
            val d = Color.red(src[i - 1]); val f = Color.red(src[i + 1])
            val g = Color.red(src[i + w - 1]); val hh = Color.red(src[i + w]); val j = Color.red(src[i + w + 1])
            val gx = -a - 2*d - g + c + 2*f + j
            val gy = -a - 2*b - c + g + 2*hh + j
            val m = sqrt((gx * gx + gy * gy).toDouble()).toInt()
            mag[i] = m; if (m > maxMag) maxMag = m
        }
        val threshold = max(8, (maxMag * (100 - s.edgeStrength.coerceIn(1, 95)) / 100))
        val out = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x; val m = mag[i]
            if (m < threshold) continue
            // Keep local maxima. Fine-detail accepts slightly weaker edges.
            val neighbor = max(max(mag[i-1], mag[i+1]), max(mag[i-w], mag[i+w]))
            out[i] = m >= neighbor - if (fine) 18 else 4
        }
        return out
    }

    private fun thresholdMask(bitmap: Bitmap, threshold: Int): BooleanArray {
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h); bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val out = BooleanArray(src.size)
        for (i in src.indices) out[i] = Color.red(src[i]) < threshold.coerceIn(1, 254)
        return out
    }

    private fun sketchMask(bitmap: Bitmap, threshold: Int): BooleanArray {
        return edgeMask(bitmap, ProcessingSettings(threshold = threshold, edgeStrength = 35, smoothEdges = false), fine = true)
    }

    private fun invertMask(bitmap: Bitmap): BooleanArray {
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h); bitmap.getPixels(src, 0, w, 0, 0, w, h)
        return BooleanArray(src.size) { Color.red(src[it]) > 180 }
    }

    private fun cleanMask(mask: BooleanArray, w: Int, h: Int, minNeighbors: Int): BooleanArray {
        val out = mask.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!mask[i]) continue
            var n = 0
            for (dy in -1..1) for (dx in -1..1) if (dx != 0 || dy != 0) if (mask[(y + dy) * w + x + dx]) n++
            if (n < minNeighbors) out[i] = false
        }
        return out
    }

    private fun thicken(mask: BooleanArray, w: Int, h: Int, amount: Int): BooleanArray {
        var current = mask
        repeat(amount.coerceIn(1, 3)) {
            val next = current.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (current[i]) continue
                var hit = false
                for (dy in -1..1) for (dx in -1..1) if (current[(y + dy) * w + x + dx]) hit = true
                if (hit) next[i] = true
            }
            current = next
        }
        return current
    }
}
