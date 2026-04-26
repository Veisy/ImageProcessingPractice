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

private const val HW4_1_DISK_RADIUS = 5
private const val HW4_1_KERNEL_SIZE = 2 * HW4_1_DISK_RADIUS + 1
private const val HW4_2_SE_SIZE = 3

fun hw4_1(bitmap: Bitmap, resources: Resources): BitmapDrawable {
    lastProcessTime = System.currentTimeMillis()

    val gray = Mat()
    Utils.bitmapToMat(bitmap, gray)
    Imgproc.cvtColor(gray, gray, Imgproc.COLOR_BGR2GRAY)

    val disk = Imgproc.getStructuringElement(
        Imgproc.MORPH_ELLIPSE,
        Size(HW4_1_KERNEL_SIZE.toDouble(), HW4_1_KERNEL_SIZE.toDouble())
    )

    val opened = Mat()
    Imgproc.morphologyEx(gray, opened, Imgproc.MORPH_OPEN, disk)

    val smoothed = Mat()
    Imgproc.morphologyEx(opened, smoothed, Imgproc.MORPH_CLOSE, disk)

    val rgba = Mat()
    Imgproc.cvtColor(smoothed, rgba, Imgproc.COLOR_GRAY2RGBA)

    val result = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(rgba, result)
    return result.toDrawable(resources)
}

fun hw4_2(bitmap: Bitmap, resources: Resources): BitmapDrawable {
    lastProcessTime = System.currentTimeMillis()

    val src = Mat()
    Utils.bitmapToMat(bitmap, src)
    Imgproc.cvtColor(src, src, Imgproc.COLOR_BGR2GRAY)

    val kernel = Imgproc.getStructuringElement(
        Imgproc.MORPH_RECT,
        Size(HW4_2_SE_SIZE.toDouble(), HW4_2_SE_SIZE.toDouble())
    )

    val gradient = Mat()
    Imgproc.morphologyEx(src, gradient, Imgproc.MORPH_GRADIENT, kernel)

    val rgba = Mat()
    Imgproc.cvtColor(gradient, rgba, Imgproc.COLOR_GRAY2RGBA)

    val result = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(rgba, result)
    return result.toDrawable(resources)
}
