package com.vyy.imageprocessingpractice.processes

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toDrawable
import com.vyy.imageprocessingpractice.utils.lastProcessTime
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

private const val HW4_4_OPEN_RADIUS = 30
private const val HW4_4_CLOSE_RADIUS = 60
private const val HW4_4_GRADIENT_SIZE = 3

fun hw4_4(bitmap: Bitmap, resources: Resources): List<BitmapDrawable> {
    lastProcessTime = System.currentTimeMillis()

    val gray = Mat()
    Utils.bitmapToMat(bitmap, gray)
    Imgproc.cvtColor(gray, gray, Imgproc.COLOR_BGR2GRAY)

    val openKernel = diskKernel(HW4_4_OPEN_RADIUS)
    val opened = Mat()
    Imgproc.morphologyEx(gray, opened, Imgproc.MORPH_OPEN, openKernel)

    val closeKernel = diskKernel(HW4_4_CLOSE_RADIUS)
    val closed = Mat()
    Imgproc.morphologyEx(opened, closed, Imgproc.MORPH_CLOSE, closeKernel)

    val gradientKernel = Imgproc.getStructuringElement(
        Imgproc.MORPH_ELLIPSE,
        Size(HW4_4_GRADIENT_SIZE.toDouble(), HW4_4_GRADIENT_SIZE.toDouble())
    )
    val boundary = Mat()
    Imgproc.morphologyEx(closed, boundary, Imgproc.MORPH_GRADIENT, gradientKernel)

    return listOf(
        toDrawable(opened, resources),
        toDrawable(closed, resources),
        toDrawable(boundary, resources)
    )
}

private fun diskKernel(radius: Int): Mat {
    val size = (2 * radius + 1).toDouble()
    return Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(size, size))
}

private fun toDrawable(grayMat: Mat, resources: Resources): BitmapDrawable {
    val rgba = Mat()
    Imgproc.cvtColor(grayMat, rgba, Imgproc.COLOR_GRAY2RGBA)
    val result = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(rgba, result)
    return result.toDrawable(resources)
}
