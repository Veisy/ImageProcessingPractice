package com.vyy.imageprocessingpractice.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.util.DisplayMetrics

private var lastRotationTime: Long = 0
private const val TIME_BETWEEN_TASKS = 400

fun checkIfShouldRotate() =
    (System.currentTimeMillis() - lastRotationTime) > TIME_BETWEEN_TASKS

fun reflectOnXAxis(bitmap: Bitmap, resources: Resources) =
    scale(bitmap, 1f, -1f, resources)

fun reflectOnYAxis(bitmap: Bitmap, resources: Resources) =
    scale(bitmap, -1f, 1f, resources)

// Reflect bitmap image and return as BitmapDrawable
private fun scale(
    bitmap: Bitmap,
    scaleX: Float,
    scaleY: Float,
    resources: Resources
): BitmapDrawable {
    // We use this variable to check if enough time has passed for a new operation.
    lastRotationTime = System.currentTimeMillis()

    val matrix = Matrix()
    matrix.preScale(scaleX, scaleY)
    val reflectedBitmap = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false
    )

    reflectedBitmap.density = DisplayMetrics.DENSITY_DEFAULT
    return BitmapDrawable(resources, reflectedBitmap)
}