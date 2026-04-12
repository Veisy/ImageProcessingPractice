package com.vyy.imageprocessingpractice.processes

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toDrawable
import com.vyy.imageprocessingpractice.utils.lastProcessTime
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.pow
import kotlin.math.sqrt

private const val BHPF_ORDER = 4
private const val BHPF_CUTOFF = 50.0

fun hw3_3Process(bitmap: Bitmap, resources: Resources): List<BitmapDrawable> {
    lastProcessTime = System.currentTimeMillis()

    // Step 1: Apply Butterworth High-Pass Filter
    val highPassed = butterworthHighPassFilter(bitmap, resources, BHPF_ORDER, BHPF_CUTOFF)

    // Step 2: Apply thresholding to the high-pass result
    val thresholded = thresholdImage(highPassed.bitmap, resources)

    return listOf(highPassed, thresholded)
}

private fun butterworthHighPassFilter(
    bitmap: Bitmap,
    resources: Resources,
    order: Int,
    cutoff: Double
): BitmapDrawable {
    val image = Mat()
    Utils.bitmapToMat(bitmap, image)
    Imgproc.cvtColor(image, image, Imgproc.COLOR_BGR2GRAY)

    // Pad to optimal DFT size
    val padded = Mat()
    val m = Core.getOptimalDFTSize(image.rows())
    val n = Core.getOptimalDFTSize(image.cols())
    Core.copyMakeBorder(
        image, padded,
        0, m - image.rows(),
        0, n - image.cols(),
        Core.BORDER_CONSTANT, Scalar.all(0.0)
    )

    // Convert to float and create complex image
    padded.convertTo(padded, CvType.CV_32F)
    val planes = ArrayList<Mat>()
    planes.add(padded)
    planes.add(Mat.zeros(padded.size(), CvType.CV_32F))
    val complexImage = Mat()
    Core.merge(planes, complexImage)

    // Compute DFT
    Core.dft(complexImage, complexImage)

    // Shift zero-frequency component to center
    shiftDFT(complexImage)

    // Build Butterworth High-Pass Filter
    val rows = padded.rows()
    val cols = padded.cols()
    val centerY = rows / 2.0
    val centerX = cols / 2.0

    val filterSingleChannel = Mat(rows, cols, CvType.CV_32F)
    for (u in 0 until rows) {
        for (v in 0 until cols) {
            val d = sqrt((u - centerY).pow(2) + (v - centerX).pow(2))
            val h = if (d == 0.0) {
                0.0
            } else {
                1.0 / (1.0 + (cutoff / d).pow(2.0 * order))
            }
            filterSingleChannel.put(u, v, h)
        }
    }

    // Create 2-channel filter for complex multiplication
    val filterPlanes = ArrayList<Mat>()
    filterPlanes.add(filterSingleChannel)
    filterPlanes.add(Mat.zeros(Size(cols.toDouble(), rows.toDouble()), CvType.CV_32F))
    val filterMat = Mat()
    Core.merge(filterPlanes, filterMat)

    // Apply filter via complex multiplication
    val filtered = Mat()
    Core.mulSpectrums(complexImage, filterMat, filtered, 0)

    // Shift back
    shiftDFT(filtered)

    // Compute inverse DFT
    Core.idft(filtered, filtered)

    // Split to get real part
    val resultPlanes = ArrayList<Mat>()
    Core.split(filtered, resultPlanes)

    // Crop back to original size
    val cropped = resultPlanes[0].submat(Rect(0, 0, image.cols(), image.rows()))

    // Normalize to 0-255 and convert to CV_8UC1
    val restoredImage = Mat()
    Core.normalize(cropped, restoredImage, 0.0, 255.0, Core.NORM_MINMAX)
    restoredImage.convertTo(restoredImage, CvType.CV_8UC1)

    val resultBitmap = Bitmap.createBitmap(restoredImage.cols(), restoredImage.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(restoredImage, resultBitmap)
    return resultBitmap.toDrawable(resources)
}

private fun shiftDFT(src: Mat) {
    val cx = src.cols() / 2
    val cy = src.rows() / 2
    val q0 = src.submat(Rect(0, 0, cx, cy))
    val q1 = src.submat(Rect(cx, 0, cx, cy))
    val q2 = src.submat(Rect(0, cy, cx, cy))
    val q3 = src.submat(Rect(cx, cy, cx, cy))
    val tmp = Mat()
    q0.copyTo(tmp); q3.copyTo(q0); tmp.copyTo(q3)
    q1.copyTo(tmp); q2.copyTo(q1); tmp.copyTo(q2)
}

private fun thresholdImage(bitmap: Bitmap, resources: Resources): BitmapDrawable {
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY)

    val binary = Mat()
    Imgproc.threshold(mat, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

    // Convert grayscale result to RGBA for Android Bitmap compatibility
    val rgba = Mat()
    Imgproc.cvtColor(binary, rgba, Imgproc.COLOR_GRAY2RGBA)

    val result = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(rgba, result)
    return result.toDrawable(resources)
}
