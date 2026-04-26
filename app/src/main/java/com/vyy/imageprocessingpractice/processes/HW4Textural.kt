package com.vyy.imageprocessingpractice.processes

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toDrawable
import com.vyy.imageprocessingpractice.utils.lastProcessTime
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

private const val HW4_4_CLOSE_RADIUS = 30
private const val HW4_4_OPEN_RADIUS = 60
private const val HW4_4_GRADIENT_SIZE = 3

fun hw4_4(bitmap: Bitmap, resources: Resources): List<BitmapDrawable> {
    lastProcessTime = System.currentTimeMillis()

    val src = Mat()
    Utils.bitmapToMat(bitmap, src)
    val gray = Mat()
    Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

    val closeKernel = diskKernel(HW4_4_CLOSE_RADIUS)
    val closed = Mat()
    Imgproc.morphologyEx(gray, closed, Imgproc.MORPH_CLOSE, closeKernel)

    val openKernel = diskKernel(HW4_4_OPEN_RADIUS)
    val opened = Mat()
    Imgproc.morphologyEx(closed, opened, Imgproc.MORPH_OPEN, openKernel)

    val gradientKernel = Mat.ones(
        Size(HW4_4_GRADIENT_SIZE.toDouble(), HW4_4_GRADIENT_SIZE.toDouble()),
        CvType.CV_8U
    )
    val boundary = Mat()
    Imgproc.morphologyEx(opened, boundary, Imgproc.MORPH_GRADIENT, gradientKernel)

    val overlay = Mat()
    Imgproc.cvtColor(gray, overlay, Imgproc.COLOR_GRAY2RGBA)
    overlay.setTo(Scalar(255.0, 0.0, 0.0, 255.0), boundary)

    return listOf(
        toDrawable(closed, resources),
        toDrawable(opened, resources),
        toDrawable(boundary, resources),
        rgbaMatToDrawable(overlay, resources)
    )
}

private fun diskKernel(radius: Int): Mat {
    val size = (2 * radius + 1).toDouble()
    return Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(size, size))
}

private fun toDrawable(grayMat: Mat, resources: Resources): BitmapDrawable {
    val rgba = Mat()
    Imgproc.cvtColor(grayMat, rgba, Imgproc.COLOR_GRAY2RGBA)
    return rgbaMatToDrawable(rgba, resources)
}

private fun rgbaMatToDrawable(rgbaMat: Mat, resources: Resources): BitmapDrawable {
    val result = Bitmap.createBitmap(rgbaMat.cols(), rgbaMat.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(rgbaMat, result)
    return result.toDrawable(resources)
}
