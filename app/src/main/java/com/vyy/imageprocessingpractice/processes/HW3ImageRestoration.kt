package com.vyy.imageprocessingpractice.processes

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.drawable.toDrawable
import com.vyy.imageprocessingpractice.utils.lastProcessTime
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val MOTION_A = 0.1
private const val MOTION_B = 0.1
private const val MOTION_T = 1.0
// Per-pipeline Wiener K: balance between noise suppression and detail recovery
private const val WIENER_K_NOISY = 0.01    // HW3_2_1/2_2: denoised but residual noise
private const val WIENER_K_CLEAN = 0.005   // HW3_2_3: motion-only, less regularization for sharper result
private const val INVERSE_RADIUS_RATIO = 0.3
private const val INVERSE_TRANSFER_THRESHOLD = 1e-3
// Larger kernel for heavy S&P noise in HW3_2_1/2_2
private const val DENOISE_FILTER_SIZE = 5

// Build the motion degradation function H(u,v) as a 2-channel complex Mat.
private fun buildMotionDegradationH(rows: Int, cols: Int, a: Double, b: Double, T: Double): Mat {
    val realMat = Mat(rows, cols, CvType.CV_32F)
    val imagMat = Mat(rows, cols, CvType.CV_32F)

    val centerU = rows / 2.0
    val centerV = cols / 2.0

    for (u in 0 until rows) {
        for (v in 0 until cols) {
            val uc = u - centerU
            val vc = v - centerV
            val alpha = PI * (uc * a + vc * b)

            val hReal: Float
            val hImag: Float
            if (abs(alpha) < 1e-10) {
                hReal = T.toFloat()
                hImag = 0f
            } else {
                val sinAlpha = sin(alpha)
                val cosAlpha = cos(alpha)
                hReal = ((T * sinAlpha * cosAlpha) / alpha).toFloat()
                hImag = (-(T * sinAlpha * sinAlpha) / alpha).toFloat()
            }

            realMat.put(u, v, hReal.toDouble())
            imagMat.put(u, v, hImag.toDouble())
        }
    }

    val complexH = Mat()
    Core.merge(listOf(realMat, imagMat), complexH)
    return complexH
}

// Apply inverse filter: G/H with radius limitation to prevent noise amplification.
private fun applyInverseFilter(complexG: Mat, complexH: Mat, rows: Int, cols: Int): Mat {
    val planesG = ArrayList<Mat>()
    Core.split(complexG, planesG)
    val gR = planesG[0]
    val gI = planesG[1]

    val planesH = ArrayList<Mat>()
    Core.split(complexH, planesH)
    val hR = planesH[0]
    val hI = planesH[1]

    // |H|^2 = H_r^2 + H_i^2
    val hR2 = Mat()
    val hI2 = Mat()
    Core.multiply(hR, hR, hR2)
    Core.multiply(hI, hI, hI2)
    val hMagSq = Mat()
    Core.add(hR2, hI2, hMagSq)

    // Guard against near-zero |H|^2: zero out frequencies where |H|^2 < threshold
    val validMask = Mat()
    Imgproc.threshold(hMagSq, validMask, INVERSE_TRANSFER_THRESHOLD, 1.0, Imgproc.THRESH_BINARY)

    // Clamp denominator to avoid Inf/NaN
    val denominator = Mat()
    Core.max(hMagSq, Scalar(INVERSE_TRANSFER_THRESHOLD), denominator)

    // Complex division: G/H = (G_r*H_r + G_i*H_i + j*(G_i*H_r - G_r*H_i)) / (H_r^2 + H_i^2)
    val numReal = Mat()
    val numImag = Mat()
    val tmp1 = Mat()
    val tmp2 = Mat()

    Core.multiply(gR, hR, tmp1)
    Core.multiply(gI, hI, tmp2)
    Core.add(tmp1, tmp2, numReal)

    Core.multiply(gI, hR, tmp1)
    Core.multiply(gR, hI, tmp2)
    Core.subtract(tmp1, tmp2, numImag)

    val resultReal = Mat()
    val resultImag = Mat()
    Core.divide(numReal, denominator, resultReal)
    Core.divide(numImag, denominator, resultImag)

    // Zero out results at frequencies where |H|^2 was below threshold
    Core.multiply(resultReal, validMask, resultReal)
    Core.multiply(resultImag, validMask, resultImag)

    // Apply radius limitation: only keep values within the allowed radius
    val maxRadius = INVERSE_RADIUS_RATIO * min(rows, cols) / 2.0
    val centerY = rows / 2.0
    val centerX = cols / 2.0

    val mask = Mat.zeros(rows, cols, CvType.CV_32F)
    for (u in 0 until rows) {
        for (v in 0 until cols) {
            val dist = sqrt((u - centerY) * (u - centerY) + (v - centerX) * (v - centerX))
            if (dist < maxRadius) {
                mask.put(u, v, 1.0)
            }
        }
    }

    Core.multiply(resultReal, mask, resultReal)
    Core.multiply(resultImag, mask, resultImag)

    val result = Mat()
    Core.merge(listOf(resultReal, resultImag), result)
    return result
}

// Apply Wiener filter: F_hat = [H* / (|H|^2 + K)] * G
private fun applyWienerFilter(complexG: Mat, complexH: Mat, K: Double): Mat {
    val planesG = ArrayList<Mat>()
    Core.split(complexG, planesG)
    val gR = planesG[0]
    val gI = planesG[1]

    val planesH = ArrayList<Mat>()
    Core.split(complexH, planesH)
    val hR = planesH[0]
    val hI = planesH[1]

    // |H|^2 = H_r^2 + H_i^2
    val hR2 = Mat()
    val hI2 = Mat()
    Core.multiply(hR, hR, hR2)
    Core.multiply(hI, hI, hI2)
    val hMagSq = Mat()
    Core.add(hR2, hI2, hMagSq)

    // denominator = |H|^2 + K
    val denominator = Mat()
    Core.add(hMagSq, Scalar(K), denominator)

    // Result_real = (H_r * G_r + H_i * G_i) / denominator
    // Result_imag = (H_r * G_i - H_i * G_r) / denominator
    val tmp1 = Mat()
    val tmp2 = Mat()

    Core.multiply(hR, gR, tmp1)
    Core.multiply(hI, gI, tmp2)
    val numReal = Mat()
    Core.add(tmp1, tmp2, numReal)

    Core.multiply(hR, gI, tmp1)
    Core.multiply(hI, gR, tmp2)
    val numImag = Mat()
    Core.subtract(tmp1, tmp2, numImag)

    val resultReal = Mat()
    val resultImag = Mat()
    Core.divide(numReal, denominator, resultReal)
    Core.divide(numImag, denominator, resultImag)

    val result = Mat()
    Core.merge(listOf(resultReal, resultImag), result)
    return result
}

// Convert bitmap to complex DFT with zero-frequency shifted to center.
// No zero-padding: avoids border artifacts in restoration output.
private fun bitmapToDftComplex(bitmap: Bitmap): Pair<Mat, Pair<Int, Int>> {
    val image = Mat()
    Utils.bitmapToMat(bitmap, image)
    Imgproc.cvtColor(image, image, Imgproc.COLOR_BGR2GRAY)

    val origRows = image.rows()
    val origCols = image.cols()

    // Convert to float and create complex Mat (no padding)
    image.convertTo(image, CvType.CV_32F)
    val planes = ArrayList<Mat>()
    planes.add(image)
    planes.add(Mat.zeros(image.size(), CvType.CV_32F))
    val complexImage = Mat()
    Core.merge(planes, complexImage)

    // Compute DFT
    Core.dft(complexImage, complexImage)

    // Shift zero-frequency to center
    shiftDFT(complexImage)

    return Pair(complexImage, Pair(origRows, origCols))
}

// Convert complex DFT result back to a result BitmapDrawable.
private fun complexToResultBitmap(complex: Mat, origRows: Int, origCols: Int, resources: Resources): BitmapDrawable {
    // Inverse DFT with scaling (divide by M*N to get proper pixel values)
    val result = Mat()
    Core.idft(complex, result, Core.DFT_SCALE)

    // Split to get real part
    val planes = ArrayList<Mat>()
    Core.split(result, planes)
    val realPart = planes[0]

    // Convert to 8-bit with saturating cast (clips to [0, 255]).
    // Do NOT use NORM_MINMAX here: restoration outliers from near-zero H
    // would stretch the range and compress the actual image to flat gray.
    val output = Mat()
    realPart.convertTo(output, CvType.CV_8UC1)

    val resultBitmap = Bitmap.createBitmap(output.cols(), output.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(output, resultBitmap)
    return resultBitmap.toDrawable(resources)
}

// Shift the zero-frequency component to/from the center of the spectrum.
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

// Simple median denoise using OpenCV's built-in medianBlur.
private fun denoiseMedian(bitmap: Bitmap, ksize: Int, resources: Resources): BitmapDrawable {
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY)
    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_GRAY2BGR)
    val denoised = Mat()
    Imgproc.medianBlur(mat, denoised, ksize)
    val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(denoised, result)
    return result.toDrawable(resources)
}

// HW3 Part 2-1: Denoise (heavy salt & pepper + motion) then Wiener filter.
fun hw3_2_1(bitmap: Bitmap, resources: Resources): List<BitmapDrawable> {
    lastProcessTime = System.currentTimeMillis()

    // Step 1: Denoise heavy S&P noise with larger median kernel
    val denoised = denoiseMedian(bitmap, DENOISE_FILTER_SIZE, resources)

    // Step 2: Wiener filter with noisy-image K
    val (complexG, origSize) = bitmapToDftComplex(denoised.bitmap)
    val H = buildMotionDegradationH(complexG.rows(), complexG.cols(), MOTION_A, MOTION_B, MOTION_T)
    val restored = applyWienerFilter(complexG, H, WIENER_K_NOISY)
    shiftDFT(restored)
    val restoredBmp = complexToResultBitmap(restored, origSize.first, origSize.second, resources)

    return listOf(denoised, restoredBmp)
}

// HW3 Part 2-2: Same pipeline as 2-1, slightly less severe noise.
fun hw3_2_2(bitmap: Bitmap, resources: Resources): List<BitmapDrawable> {
    lastProcessTime = System.currentTimeMillis()

    // Step 1: Denoise S&P noise
    val denoised = denoiseMedian(bitmap, DENOISE_FILTER_SIZE, resources)

    // Step 2: Wiener filter with noisy-image K
    val (complexG, origSize) = bitmapToDftComplex(denoised.bitmap)
    val H = buildMotionDegradationH(complexG.rows(), complexG.cols(), MOTION_A, MOTION_B, MOTION_T)
    val restored = applyWienerFilter(complexG, H, WIENER_K_NOISY)
    shiftDFT(restored)
    val restoredBmp = complexToResultBitmap(restored, origSize.first, origSize.second, resources)

    return listOf(denoised, restoredBmp)
}

// HW3 Part 2-3: Only motion noise, no salt & pepper. Apply both inverse and Wiener filters.
fun hw3_2_3(bitmap: Bitmap, resources: Resources): List<BitmapDrawable> {
    lastProcessTime = System.currentTimeMillis()

    // Only motion noise, no salt & pepper
    val (complexG, origSize) = bitmapToDftComplex(bitmap)
    val H = buildMotionDegradationH(complexG.rows(), complexG.cols(), MOTION_A, MOTION_B, MOTION_T)

    // Step 1: Inverse filter (radius-limited, threshold-guarded)
    val inversed = applyInverseFilter(complexG, H, complexG.rows(), complexG.cols())
    shiftDFT(inversed)
    val inverseBmp = complexToResultBitmap(inversed, origSize.first, origSize.second, resources)

    // Step 2: Wiener filter with clean-image K (lower regularization for motion-only)
    val (complexG2, _) = bitmapToDftComplex(bitmap)
    val H2 = buildMotionDegradationH(complexG2.rows(), complexG2.cols(), MOTION_A, MOTION_B, MOTION_T)
    val wienered = applyWienerFilter(complexG2, H2, WIENER_K_CLEAN)
    shiftDFT(wienered)
    val wienerBmp = complexToResultBitmap(wienered, origSize.first, origSize.second, resources)

    return listOf(inverseBmp, wienerBmp)
}
