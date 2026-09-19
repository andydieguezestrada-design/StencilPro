package com.tattoostencil.pro.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt
import kotlin.math.abs

object ImageProcessor {
    
    enum class FilterType(val displayName: String) {
        CANNY_EDGE("Detección de Bordes"),
        ADAPTIVE_THRESHOLD("Umbral Adaptativo"),
        SKETCH("Efecto Sketch"),
        HIGH_CONTRAST("Alto Contraste"),
        INVERT("Invertir Colores"),
        CUSTOM("Personalizado")
    }
    
    data class ProcessingSettings(
        val brightness: Float = 0f,
        val contrast: Float = 1f,
        val threshold: Int = 128,
        val blurRadius: Int = 2,
        val edgeStrength: Int = 50,
        val invertColors: Boolean = false,
        val smoothEdges: Boolean = true
    )
    
    // Convertir a escala de grises con fórmula de luminancia
    fun toGrayscale(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                // Fórmula de luminancia perceptual
                val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                grayBitmap.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
        }
        return grayBitmap
    }
    
    // Ajustar brillo y contraste
    fun adjustBrightnessContrast(
        bitmap: Bitmap,
        brightness: Float,
        contrast: Float
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        val contrastFactor = (259 * (contrast + 255)) / (255 * (259 - contrast))
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                val newR = (contrastFactor * (r - 128) + 128 + brightness).toInt().coerceIn(0, 255)
                val newG = (contrastFactor * (g - 128) + 128 + brightness).toInt().coerceIn(0, 255)
                val newB = (contrastFactor * (b - 128) + 128 + brightness).toInt().coerceIn(0, 255)
                
                result.setPixel(x, y, Color.rgb(newR, newG, newB))
            }
        }
        return result
    }
    
    // Gaussian Blur para suavizado
    fun gaussianBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0
                
                for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val ny = (y + dy).coerceIn(0, height - 1)
                        val pixel = bitmap.getPixel(nx, ny)
                        rSum += Color.red(pixel)
                        gSum += Color.green(pixel)
                        bSum += Color.blue(pixel)
                        count++
                    }
                }
                
                val r = rSum / count
                val g = gSum / count
                val b = bSum / count
                result.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return result
    }
    
    // Detección de bordes Canny mejorada
    fun cannyEdgeDetection(bitmap: Bitmap, threshold: Int, smoothEdges: Boolean): Bitmap {
        var grayBitmap = toGrayscale(bitmap)
        
        // Aplicar blur para reducir ruido
        if (smoothEdges) {
            grayBitmap = gaussianBlur(grayBitmap, 2)
        }
        
        val width = grayBitmap.width
        val height = grayBitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // Calcular gradientes con Sobel
        val gradientMagnitude = Array(height) { IntArray(width) }
        val gradientDirection = Array(height) { DoubleArray(width) }
        
        for (x in 1 until width - 1) {
            for (y in 1 until height - 1) {
                val gx = calculateSobelX(grayBitmap, x, y)
                val gy = calculateSobelY(grayBitmap, x, y)
                
                val magnitude = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                val direction = Math.atan2(gy.toDouble(), gx.toDouble())
                
                gradientMagnitude[y][x] = magnitude
                gradientDirection[y][x] = direction
            }
        }
        
        // Non-maximum suppression
        for (x in 1 until width - 1) {
            for (y in 1 until height - 1) {
                val magnitude = gradientMagnitude[y][x]
                val direction = gradientDirection[y][x]
                
                var neighbor1 = 0
                var neighbor2 = 0
                
                when {
                    abs(direction) < Math.PI / 8 || abs(direction) > 7 * Math.PI / 8 -> {
                        neighbor1 = gradientMagnitude[y][x - 1]
                        neighbor2 = gradientMagnitude[y][x + 1]
                    }
                    abs(direction - Math.PI / 4) < Math.PI / 8 || abs(direction + Math.PI / 4) < Math.PI / 8 -> {
                        neighbor1 = gradientMagnitude[y - 1][x - 1]
                        neighbor2 = gradientMagnitude[y + 1][x + 1]
                    }
                    abs(direction - Math.PI / 2) < Math.PI / 8 || abs(direction + Math.PI / 2) < Math.PI / 8 -> {
                        neighbor1 = gradientMagnitude[y - 1][x]
                        neighbor2 = gradientMagnitude[y + 1][x]
                    }
                    else -> {
                        neighbor1 = gradientMagnitude[y - 1][x + 1]
                        neighbor2 = gradientMagnitude[y + 1][x - 1]
                    }
                }
                
                val color = if (magnitude >= neighbor1 && magnitude >= neighbor2 && magnitude > threshold) {
                    Color.BLACK
                } else {
                    Color.WHITE
                }
                result.setPixel(x, y, color)
            }
        }
        
        return result
    }
    
    // Umbral adaptativo
    fun adaptiveThreshold(bitmap: Bitmap, threshold: Int): Bitmap {
        val grayBitmap = toGrayscale(bitmap)
        val width = grayBitmap.width
        val height = grayBitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // Calcular umbral local
        val blockSize = 15
        val halfBlock = blockSize / 2
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sum = 0
                var count = 0
                
                for (dx in -halfBlock..halfBlock) {
                    for (dy in -halfBlock..halfBlock) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val ny = (y + dy).coerceIn(0, height - 1)
                        sum += Color.red(grayBitmap.getPixel(nx, ny))
                        count++
                    }
                }
                
                val localThreshold = sum / count - 10
                val gray = Color.red(grayBitmap.getPixel(x, y))
                val color = if (gray < localThreshold) Color.BLACK else Color.WHITE
                result.setPixel(x, y, color)
            }
        }
        return result
    }
    
    // Efecto sketch mejorado
    fun sketchEffect(bitmap: Bitmap): Bitmap {
        val grayBitmap = toGrayscale(bitmap)
        val inverted = invertColors(grayBitmap)
        val blurred = gaussianBlur(inverted, 5)
        
        val width = grayBitmap.width
        val height = grayBitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                val gray = Color.red(grayBitmap.getPixel(x, y))
                val blur = Color.red(blurred.getPixel(x, y))
                
                val value = if (blur == 255) {
                    255
                } else {
                    ((gray * 256) / (256 - blur)).toInt().coerceIn(0, 255)
                }
                
                result.setPixel(x, y, Color.rgb(value, value, value))
            }
        }
        return result
    }
    
    // Invertir colores
    fun invertColors(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap.getPixel(x, y)
                val r = 255 - Color.red(pixel)
                val g = 255 - Color.green(pixel)
                val b = 255 - Color.blue(pixel)
                result.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return result
    }
    
    // Alto contraste
    fun highContrast(bitmap: Bitmap, threshold: Int): Bitmap {
        val grayBitmap = toGrayscale(bitmap)
        val width = grayBitmap.width
        val height = grayBitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                val gray = Color.red(grayBitmap.getPixel(x, y))
                val color = if (gray < threshold) Color.BLACK else Color.WHITE
                result.setPixel(x, y, color)
            }
        }
        return result
    }
    
    // Función principal de procesamiento
    fun processImage(
        bitmap: Bitmap,
        filterType: FilterType,
        settings: ProcessingSettings
    ): Bitmap {
        var result = bitmap
        
        // Ajustar brillo y contraste primero
        if (settings.brightness != 0f || settings.contrast != 1f) {
            result = adjustBrightnessContrast(result, settings.brightness, settings.contrast)
        }
        
        // Aplicar filtro seleccionado
        result = when (filterType) {
            FilterType.CANNY_EDGE -> cannyEdgeDetection(result, settings.threshold, settings.smoothEdges)
            FilterType.ADAPTIVE_THRESHOLD -> adaptiveThreshold(result, settings.threshold)
            FilterType.SKETCH -> sketchEffect(result)
            FilterType.HIGH_CONTRAST -> highContrast(result, settings.threshold)
            FilterType.INVERT -> invertColors(result)
            FilterType.CUSTOM -> {
                var custom = toGrayscale(result)
                custom = adaptiveThreshold(custom, settings.threshold)
                if (settings.invertColors) invertColors(custom) else custom
            }
        }
        
        // Invertir si está activado
        if (settings.invertColors && filterType != FilterType.INVERT) {
            result = invertColors(result)
        }
        
        return result
    }
    
    private fun calculateSobelX(bitmap: Bitmap, x: Int, y: Int): Int {
        val tl = Color.red(bitmap.getPixel(x - 1, y - 1))
        val bl = Color.red(bitmap.getPixel(x - 1, y + 1))
        val tr = Color.red(bitmap.getPixel(x + 1, y - 1))
        val br = Color.red(bitmap.getPixel(x + 1, y + 1))
        val l = Color.red(bitmap.getPixel(x - 1, y))
        val r = Color.red(bitmap.getPixel(x + 1, y))
        
        return -tl - 2 * l - bl + tr + 2 * r + br
    }
    
    private fun calculateSobelY(bitmap: Bitmap, x: Int, y: Int): Int {
        val tl = Color.red(bitmap.getPixel(x - 1, y - 1))
        val tr = Color.red(bitmap.getPixel(x + 1, y - 1))
        val bl = Color.red(bitmap.getPixel(x - 1, y + 1))
        val br = Color.red(bitmap.getPixel(x + 1, y + 1))
        val t = Color.red(bitmap.getPixel(x, y - 1))
        val b = Color.red(bitmap.getPixel(x, y + 1))
        
        return -tl - 2 * t - tr + bl + 2 * b + br
    }
}
