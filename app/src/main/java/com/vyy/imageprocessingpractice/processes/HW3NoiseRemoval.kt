package com.vyy.imageprocessingpractice.processes

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toDrawable
import com.vyy.imageprocessingpractice.utils.lastProcessTime
import kotlin.math.pow

// Contra-Harmonic Mean Filter
fun contraHarmonicFilter(
    bitmap: Bitmap, resources: Resources, Q: Double, filterSize: Int
): BitmapDrawable {
    lastProcessTime = System.currentTimeMillis()

    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val newPixels = IntArray(width * height)
    val half = filterSize / 2
    val epsilon = 1e-10

    for (y in 0 until height) {
        for (x in 0 until width) {
            var numerator = 0.0
            var denominator = 0.0

            for (i in -half..half) {
                for (j in -half..half) {
                    val ny = y + i
                    val nx = x + j
                    if (ny < 0 || ny >= height || nx < 0 || nx >= width) continue

                    val pixel = pixels[ny * width + nx]
                    val g = (pixel shr 16 and 0xff).toDouble()

                    if (Q < 0 && g == 0.0) {
                        numerator += (g + epsilon).pow(Q + 1)
                        denominator += (g + epsilon).pow(Q)
                    } else {
                        numerator += g.pow(Q + 1)
                        denominator += g.pow(Q)
                    }
                }
            }

            val result = if (denominator != 0.0) (numerator / denominator) else 0.0
            val gray = result.toInt().coerceIn(0, 255)

            newPixels[y * width + x] =
                0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
        }
    }

    val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    newBitmap.setPixels(newPixels, 0, width, 0, 0, width, height)
    return newBitmap.toDrawable(resources)
}

// Parameterized Median Filter (NxN)
fun medianFilterNxN(
    bitmap: Bitmap, resources: Resources, filterSize: Int
): BitmapDrawable {
    lastProcessTime = System.currentTimeMillis()

    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val newPixels = IntArray(width * height)
    val half = filterSize / 2

    for (y in 0 until height) {
        for (x in 0 until width) {
            val values = mutableListOf<Int>()

            for (i in -half..half) {
                for (j in -half..half) {
                    val ny = y + i
                    val nx = x + j
                    if (ny < 0 || ny >= height || nx < 0 || nx >= width) continue
                    values.add(pixels[ny * width + nx] shr 16 and 0xff)
                }
            }

            values.sort()
            val gray = values[values.size / 2]

            newPixels[y * width + x] =
                0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
        }
    }

    val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    newBitmap.setPixels(newPixels, 0, width, 0, 0, width, height)
    return newBitmap.toDrawable(resources)
}

// Adaptive Median Filter
fun adaptiveMedianFilter(
    bitmap: Bitmap, resources: Resources, sMax: Int
): BitmapDrawable {
    lastProcessTime = System.currentTimeMillis()

    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val newPixels = IntArray(width * height)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val zxy = pixels[y * width + x] shr 16 and 0xff
            var windowSize = 3
            var outputGray = zxy

            while (windowSize <= sMax) {
                val half = windowSize / 2
                val values = mutableListOf<Int>()

                for (i in -half..half) {
                    for (j in -half..half) {
                        val ny = y + i
                        val nx = x + j
                        if (ny < 0 || ny >= height || nx < 0 || nx >= width) continue
                        values.add(pixels[ny * width + nx] shr 16 and 0xff)
                    }
                }

                values.sort()
                val zmin = values.first()
                val zmax = values.last()
                val zmed = values[values.size / 2]

                val a1 = zmed - zmin
                val a2 = zmed - zmax

                if (a1 > 0 && a2 < 0) {
                    // Level B
                    val b1 = zxy - zmin
                    val b2 = zxy - zmax
                    outputGray = if (b1 > 0 && b2 < 0) zxy else zmed
                    break
                } else {
                    windowSize += 2
                    if (windowSize > sMax) {
                        outputGray = zmed
                        break
                    }
                }
            }

            newPixels[y * width + x] =
                0xff000000.toInt() or (outputGray shl 16) or (outputGray shl 8) or outputGray
        }
    }

    val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    newBitmap.setPixels(newPixels, 0, width, 0, 0, width, height)
    return newBitmap.toDrawable(resources)
}

// HW3 Part 1 convenience functions

fun hw3_1_1(bitmap: Bitmap, resources: Resources): BitmapDrawable =
    contraHarmonicFilter(bitmap, resources, Q = 1.5, filterSize = 3)

fun hw3_1_2(bitmap: Bitmap, resources: Resources): BitmapDrawable =
    contraHarmonicFilter(bitmap, resources, Q = -1.5, filterSize = 3)

fun hw3_1_3Median(bitmap: Bitmap, resources: Resources): BitmapDrawable =
    medianFilterNxN(bitmap, resources, filterSize = 7)

fun hw3_1_3Adaptive(bitmap: Bitmap, resources: Resources): BitmapDrawable =
    adaptiveMedianFilter(bitmap, resources, sMax = 7)

fun hw3_1_4(bitmap: Bitmap, resources: Resources): BitmapDrawable {
    // Apply 3x3 median filter 3 times
    val first = medianFilterNxN(bitmap, resources, filterSize = 3)
    val second = medianFilterNxN(first.bitmap, resources, filterSize = 3)
    return medianFilterNxN(second.bitmap, resources, filterSize = 3)
}
