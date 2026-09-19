package com.tattoostencil.pro.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic image processing for tattoo stencil preparation.
 * CLEAN_LINES is intentionally conservative: it suppresses photographic texture,
 * keeps strong structural edges, thins them to one-pixel contours and removes
 * small disconnected marks before the final line colour is applied.
 */
object ImageProcessor {
    enum class FilterType(val displayName: String, val description: String) {
        CLEAN_LINES("Líneas limpias", "Contornos principales y detalles útiles, fondo blanco"),
        FINE_DETAIL("Detalle fino", "Más información interna, con limpieza moderada"),
        THRESHOLD("Umbral", "Guía binaria; puede producir masas"),
        SKETCH("Sketch", "Boceto de referencia"),
        HIGH_CONTRAST("Alto contraste", "Guía de alto contraste"),
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
        val contrast: Float = 1.05f,
        val threshold: Int = 48,
        val blurRadius: Int = 2,
        val edgeStrength: Int = 62,
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
        // Keep the imported image/output reasonably large, but do the expensive edge analysis
        // on a smaller working copy. This prevents 2400px photos from exhausting Android RAM.
        val outputSource = normalize(bitmap, settings.maxOutputSize)
        val analysisSize = min(settings.maxOutputSize, 1600)
        var gray = toGrayscale(normalize(outputSource, analysisSize))
        if (settings.brightness != 0f || settings.contrast != 1f) {
            gray = adjustBrightnessContrast(gray, settings.brightness, settings.contrast)
        }

        val width = gray.width
        val height = gray.height
        val mask = when (filterType) {
            FilterType.CLEAN_LINES -> professionalStencilMask(gray, settings)
            FilterType.FINE_DETAIL -> fineDetailMask(gray, settings)
            FilterType.THRESHOLD -> thresholdMask(gray, settings.threshold)
            FilterType.SKETCH -> sketchMask(gray, settings)
            FilterType.HIGH_CONTRAST -> thresholdMask(gray, settings.threshold.coerceIn(1, 254))
            FilterType.INVERT -> invertMask(gray)
        }

        var finalMask = mask
        if (settings.removeNoise && filterType != FilterType.THRESHOLD && filterType != FilterType.HIGH_CONTRAST) {
            finalMask = removeSmallComponents(finalMask, width, height, settings.preserveDetail)
            finalMask = cleanMask(finalMask, width, height, if (filterType == FilterType.CLEAN_LINES) 3 else if (settings.preserveDetail) 2 else 3)
        }
        if (settings.lineWidth > 1 && filterType != FilterType.THRESHOLD && filterType != FilterType.HIGH_CONTRAST) {
            finalMask = thicken(finalMask, width, height, settings.lineWidth - 1)
        }

        val analysisOut = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val line = settings.lineColor.color
        for (i in pixels.indices) {
            val on = finalMask[i]
            pixels[i] = if (on xor settings.invertColors) line else Color.WHITE
        }
        analysisOut.setPixels(pixels, 0, width, 0, 0, width, height)
        return if (analysisOut.width == outputSource.width && analysisOut.height == outputSource.height) {
            analysisOut
        } else {
            Bitmap.createScaledBitmap(analysisOut, outputSource.width, outputSource.height, false).also {
                analysisOut.recycle()
            }
        }
    }

    fun normalize(bitmap: Bitmap, maxSize: Int): Bitmap {
        if (bitmap.width <= maxSize && bitmap.height <= maxSize) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val scale = min(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true
        )
    }

    fun toGrayscale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val g = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt().coerceIn(0, 255)
            dst[i] = Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(dst, 0, w, 0, 0, w, h) }
    }

    fun adjustBrightnessContrast(bitmap: Bitmap, brightness: Float, contrast: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
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
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val tmp = IntArray(src.size)
        val dst = IntArray(src.size)
        val size = radius * 2 + 1
        val kernel = IntArray(size)
        var sum = 0
        for (i in 0 until size) {
            val x = i - radius
            kernel[i] = max(1, (1000 * kotlin.math.exp(-(x * x) / (2.0 * radius * radius))).toInt())
            sum += kernel[i]
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (k in -radius..radius) {
                s += Color.red(src[y * w + (x + k).coerceIn(0, w - 1)]) * kernel[k + radius]
            }
            tmp[y * w + x] = s / sum
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (k in -radius..radius) {
                s += tmp[(y + k).coerceIn(0, h - 1) * w + x] * kernel[k + radius]
            }
            val v = s / sum
            dst[y * w + x] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(dst, 0, w, 0, 0, w, h) }
    }

    /**
     * Professional stencil path. A single Canny pass is too sensitive to photographic
     * texture. We therefore extract a stable coarse structure first, then allow only
     * stronger fine edges that sit close to that structure. This deliberately favours
     * continuous tattoo-readable contours over photographic micro-texture.
     */
    private fun professionalStencilMask(bitmap: Bitmap, s: ProcessingSettings): BooleanArray {
        val baseRadius = if (s.smoothEdges) max(2, s.blurRadius.coerceIn(2, 4)) else 1
        val coarse = cannyLikeMask(gaussianBlur(bitmap, baseRadius + 1),
            (s.edgeStrength + 10).coerceIn(35, 95), keepFine = false)
        val fine = cannyLikeMask(gaussianBlur(bitmap, baseRadius),
            s.edgeStrength.coerceIn(30, 90), keepFine = true)

        val w = bitmap.width
        val h = bitmap.height
        val out = BooleanArray(w * h)
        for (i in out.indices) if (coarse[i]) out[i] = true

        // Fine detail is retained only when it is connected/near a structural edge.
        // This is the key step that prevents fur/skin/photo grain from becoming thousands
        // of isolated purple dots.
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                if (!fine[i] || out[i]) continue
                var nearStructure = false
                loop@ for (dy in -2..2) for (dx in -2..2) {
                    if (dx == 0 && dy == 0) continue
                    if (coarse[(y + dy) * w + x + dx]) { nearStructure = true; break@loop }
                }
                if (nearStructure) out[i] = true
            }
        }
        return out
    }

    /** Legacy clean path kept for the other processing modes. */
    private fun cleanLinesMask(bitmap: Bitmap, s: ProcessingSettings): BooleanArray {
        val radius = if (s.smoothEdges) max(2, s.blurRadius.coerceIn(1, 3)) else 1
        return cannyLikeMask(gaussianBlur(bitmap, radius), s.edgeStrength, keepFine = false)
    }

    private fun fineDetailMask(bitmap: Bitmap, s: ProcessingSettings): BooleanArray {
        val radius = if (s.smoothEdges) s.blurRadius.coerceIn(1, 2) else 0
        val softened = if (radius > 0) gaussianBlur(bitmap, radius) else bitmap
        return cannyLikeMask(softened, (s.edgeStrength - 12).coerceIn(20, 85), keepFine = true)
    }

    /** Sobel magnitude + non-maximum suppression + hysteresis, rather than raw pixel edges. */
    private fun cannyLikeMask(bitmap: Bitmap, strength: Int, keepFine: Boolean): BooleanArray {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val mag = IntArray(w * h)
        val angle = ByteArray(w * h)
        var maxMag = 1

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val a = Color.red(src[i - w - 1]); val b = Color.red(src[i - w]); val c = Color.red(src[i - w + 1])
                val d = Color.red(src[i - 1]); val f = Color.red(src[i + 1])
                val g = Color.red(src[i + w - 1]); val hh = Color.red(src[i + w]); val j = Color.red(src[i + w + 1])
                val gx = -a - 2 * d - g + c + 2 * f + j
                val gy = -a - 2 * b - c + g + 2 * hh + j
                val m = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                mag[i] = m
                if (m > maxMag) maxMag = m
                val deg = Math.toDegrees(kotlin.math.atan2(gy.toDouble(), gx.toDouble()))
                angle[i] = when {
                    deg < 0.0 -> (((deg + 180.0) / 22.5).toInt() and 7).toByte()
                    else -> ((deg / 22.5).toInt() and 7).toByte()
                }
            }
        }

        val nms = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mag[i]
            if (m == 0) continue
            val dir = angle[i].toInt() and 7
            val (p, q) = when (dir) {
                0, 4 -> mag[i - 1] to mag[i + 1]
                1, 5 -> mag[i - w + 1] to mag[i + w - 1]
                2, 6 -> mag[i - w] to mag[i + w]
                else -> mag[i - w - 1] to mag[i + w + 1]
            }
            if (m >= p && m >= q) nms[i] = m
        }

        // Use a percentile instead of a percentage of the single strongest pixel.
        // This prevents one hard photographic edge from making thousands of weak texture edges pass.
        val histogram = IntArray(maxMag + 1)
        var count = 0
        for (v in nms) if (v > 0) { histogram[v]++; count++ }
        if (count == 0) return BooleanArray(w * h)
        val percentile = if (keepFine) {
            // Fine pass: still selective, but allowed to recover useful internal detail.
            0.90f - (strength - 50).coerceIn(-30, 30) * 0.0015f
        } else {
            // Structural pass: keep only the strongest few percent of stable edges.
            0.975f - (strength - 50).coerceIn(-30, 30) * 0.0015f
        }
        val highRank = (count * percentile.coerceIn(0.72f, 0.97f)).toInt().coerceIn(1, count)
        var seen = 0
        var high = 1
        for (v in histogram.indices.reversed()) {
            seen += histogram[v]
            if (seen >= highRank) { high = v; break }
        }
        high = high.coerceAtLeast(if (keepFine) 18 else 24)
        val low = max(8, (high * if (keepFine) 0.42f else 0.50f).toInt())

        // Hysteresis: weak edges survive only if connected to a strong edge.
        val state = ByteArray(w * h) // 0 none, 1 weak, 2 strong
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        for (i in nms.indices) {
            when {
                nms[i] >= high -> { state[i] = 2; queue[tail++] = i }
                nms[i] >= low -> state[i] = 1
            }
        }
        val result = BooleanArray(w * h)
        while (head < tail) {
            val i = queue[head++]
            result[i] = true
            val y = i / w
            val x = i - y * w
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 1 until w - 1 || ny !in 1 until h - 1) continue
                val ni = ny * w + nx
                if (state[ni] == 1) {
                    state[ni] = 2
                    queue[tail++] = ni
                }
            }
        }
        return result
    }

    private fun thresholdMask(bitmap: Bitmap, threshold: Int): BooleanArray {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        return BooleanArray(src.size) { Color.red(src[it]) < threshold.coerceIn(1, 254) }
    }

    private fun sketchMask(bitmap: Bitmap, s: ProcessingSettings): BooleanArray =
        cannyLikeMask(bitmap, (s.edgeStrength - 5).coerceIn(15, 90), keepFine = true)

    private fun invertMask(bitmap: Bitmap): BooleanArray {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        return BooleanArray(src.size) { Color.red(src[it]) > 180 }
    }

    /** Remove isolated specks/short texture fragments with a small connected-component pass. */
    private fun removeSmallComponents(mask: BooleanArray, w: Int, h: Int, preserveDetail: Boolean): BooleanArray {
        val visited = BooleanArray(mask.size)
        val out = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val component = IntArray(mask.size)
        val minSize = if (preserveDetail) max(42, (w * h) / 110000) else max(65, (w * h) / 80000)

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var top = 0
            var size = 0
            stack[top++] = start
            visited[start] = true
            while (top > 0) {
                val i = stack[--top]
                component[size++] = i
                val y = i / w
                val x = i - y * w
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (mask[ni] && !visited[ni]) {
                        visited[ni] = true
                        stack[top++] = ni
                    }
                }
            }
            if (size >= minSize) {
                var minX = w; var maxX = 0; var minY = h; var maxY = 0
                for (j in 0 until size) {
                    val p = component[j]; val cy = p / w; val cx = p - cy * w
                    if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                }
                val span = max(maxX - minX, maxY - minY)
                // Tiny compact blobs are photographic noise; long/connected strokes survive.
                if (span >= 7 || size >= minSize * 3) {
                    for (j in 0 until size) out[component[j]] = true
                }
            }
        }
        return out
    }

    private fun cleanMask(mask: BooleanArray, w: Int, h: Int, minNeighbors: Int): BooleanArray {
        val out = mask.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!mask[i]) continue
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx != 0 || dy != 0) if (mask[(y + dy) * w + x + dx]) n++
            }
            if (n < minNeighbors) out[i] = false
        }
        return out
    }

    private fun thicken(mask: BooleanArray, w: Int, h: Int, amount: Int): BooleanArray {
        var current = mask
        repeat(amount.coerceIn(1, 2)) {
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
