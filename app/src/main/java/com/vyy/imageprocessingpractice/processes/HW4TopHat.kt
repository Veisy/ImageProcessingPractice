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

private const val HW4_3_DISK_RADIUS = 40
private const val HW4_3_KERNEL_SIZE = 2 * HW4_3_DISK_RADIUS + 1

fun hw4_3(bitmap: Bitmap, resources: Resources): List<BitmapDrawable> {
    lastProcessTime = System.currentTimeMillis()

    val gray = Mat()
    Utils.bitmapToMat(bitmap, gray)
    Imgproc.cvtColor(gray, gray, Imgproc.COLOR_BGR2GRAY)

    val kernel = Imgproc.getStructuringElement(
        Imgproc.MORPH_ELLIPSE,
        Size(HW4_3_KERNEL_SIZE.toDouble(), HW4_3_KERNEL_SIZE.toDouble())
    )

    val topHat = Mat()
    Imgproc.morphologyEx(gray, topHat, Imgproc.MORPH_TOPHAT, kernel)

    val binary = Mat()
    Imgproc.threshold(topHat, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

    return listOf(toDrawable(topHat, resources), toDrawable(binary, resources))
}

private fun toDrawable(grayMat: Mat, resources: Resources): BitmapDrawable {
    val rgba = Mat()
    Imgproc.cvtColor(grayMat, rgba, Imgproc.COLOR_GRAY2RGBA)
    val result = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(rgba, result)
    return result.toDrawable(resources)
}
