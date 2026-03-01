package com.vyy.imageprocessingpractice

import android.Manifest.permission.CAMERA
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.vyy.imagemosaicing.R
import com.vyy.imagemosaicing.databinding.ActivityMainBinding
import com.vyy.imageprocessingpractice.processes.*
import com.vyy.imageprocessingpractice.utils.Constants.AVERAGE_FILTER
import com.vyy.imageprocessingpractice.utils.Constants.AVERAGE_THRESHOLD
import com.vyy.imageprocessingpractice.utils.Constants.FILENAME_FORMAT
import com.vyy.imageprocessingpractice.utils.Constants.GAMMA_TRANSFORMATION
import com.vyy.imageprocessingpractice.utils.Constants.HIGH_PASS_FILTER
import com.vyy.imageprocessingpractice.utils.Constants.IMAGE_STACK_SIZE_MAX
import com.vyy.imageprocessingpractice.utils.Constants.IMAGE_STACK_SIZE_MIN
import com.vyy.imageprocessingpractice.utils.Constants.LAPLACIAN_FILTER
import com.vyy.imageprocessingpractice.utils.Constants.LUNG_SEGMENTATION
import com.vyy.imageprocessingpractice.utils.Constants.MAX_FILTER
import com.vyy.imageprocessingpractice.utils.Constants.MAX_HEIGHT
import com.vyy.imageprocessingpractice.utils.Constants.MAX_WIDTH
import com.vyy.imageprocessingpractice.utils.Constants.MEDIAN_FILTER
import com.vyy.imageprocessingpractice.utils.Constants.MIN_FILTER
import com.vyy.imageprocessingpractice.utils.Constants.OTSU_THRESHOLD
import com.vyy.imageprocessingpractice.utils.Constants.REQUEST_CODE_PERMISSIONS
import com.vyy.imageprocessingpractice.utils.Constants.RGB_TO_GRAY
import com.vyy.imageprocessingpractice.utils.Constants.RGB_TO_HSI
import com.vyy.imageprocessingpractice.utils.Constants.RGB_TO_HSV
import com.vyy.imageprocessingpractice.utils.Constants.SOBEL_GRADIENT
import com.vyy.imageprocessingpractice.utils.Constants.SPECIAL_OPERATION_1
import com.vyy.imageprocessingpractice.utils.Constants.SPECIAL_OPERATION_2
import com.vyy.imageprocessingpractice.utils.Constants.SPECIAL_THRESHOLD
import com.vyy.imageprocessingpractice.utils.Constants.WIENER_FILTER
import com.vyy.imageprocessingpractice.utils.InputFilterMinMax
import com.vyy.imageprocessingpractice.utils.checkEnoughTimePassed
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayDeque


class MainActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var pickMedia: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var imageUri: Uri? = null
    private var imageBitmap: Bitmap? = null
    private var imageStack: ArrayDeque<Bitmap> = ArrayDeque()

    private var imageProcessingJob: Job? = null
    private var checkGrayScaleJob: Job? = null
    private var imageUriToBitmapDeferred: Deferred<Bitmap?>? = null

    private var selectedProcess: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        registerActivityResultCallbacks()
        setClickListeners()
        setEditTextFilters()

        if (OpenCVLoader.initDebug()) {
            Log.d("OpenCV", "OpenCV loaded successfully")
        } else {
            Log.d("OpenCV", "OpenCV not loaded")
        }
    }

    override fun onStart() {
        super.onStart()

        checkPermissions()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Default Image is Lenna.
        if (imageStack.size < 1) {
            imageUri = Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(resources.getResourcePackageName(R.drawable.lenna))
                .appendPath(resources.getResourceTypeName(R.drawable.lenna))
                .appendPath(resources.getResourceEntryName(R.drawable.lenna)).build()
            updateImageView(imageUri)
            hideGrayAndRgbTextView()

            imageUriToBitmapDeferred = this.lifecycleScope.async(Dispatchers.Default) {
                val bitmap = imageUri?.let { uriToBitmap(it) }
                if (bitmap != null) {
                    addToImageStack(bitmap)
                }
                bitmap
            }
        }
    }

    private fun checkPermissions() {
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun setClickListeners() {
        binding.apply {
            cameraButton.setOnClickListener(this@MainActivity)
            buttonProcess.setOnClickListener(this@MainActivity)
            galleryButton.setOnClickListener(this@MainActivity)
            textViewRgb.setOnClickListener(this@MainActivity)
            imageButtonReflectYAxis.setOnClickListener(this@MainActivity)
            imageButtonReflectXAxis.setOnClickListener(this@MainActivity)
            imageButtonResize.setOnClickListener(this@MainActivity)
            imageButtonCrop.setOnClickListener(this@MainActivity)
            imageButtonPixelate.setOnClickListener(this@MainActivity)
            imageButtonUndo.setOnClickListener(this@MainActivity)
            buttonRgbToHsi.setOnClickListener(this@MainActivity)
            buttonRgbToHsv.setOnClickListener(this@MainActivity)
            buttonMinFilter.setOnClickListener(this@MainActivity)
            buttonMaxFilter.setOnClickListener(this@MainActivity)
            buttonMedianFilter.setOnClickListener(this@MainActivity)
            buttonAverageFilter.setOnClickListener(this@MainActivity)
            buttonLaplacianFilter.setOnClickListener(this@MainActivity)
            buttonSobelGradient.setOnClickListener(this@MainActivity)
            buttonGammaTransformation.setOnClickListener(this@MainActivity)
            buttonSpecialOperation.setOnClickListener(this@MainActivity)
            buttonLungSegmentation.setOnClickListener(this@MainActivity)
            buttonSpecialOperation2.setOnClickListener(this@MainActivity)
            buttonHighPassFilter.setOnClickListener(this@MainActivity)
            buttonWienerFilter.setOnClickListener(this@MainActivity)
            buttonAverageThreshold.setOnClickListener(this@MainActivity)
            buttonOtsuThreshold.setOnClickListener(this@MainActivity)
            buttonSpecialThreshold.setOnClickListener(this@MainActivity)
        }
    }

    override fun onClick(v: View?) {
        v?.let {
            when (v.id) {
                R.id.imageButton_undo, R.id.cameraButton, R.id.galleryButton -> {
                    cancelCurrentJobs()
                    hideGrayAndRgbTextView()
                    updateSelectedProcess(null)
                    when (v.id) {
                        R.id.imageButton_undo -> removeFromImageStack()
                        R.id.cameraButton -> takePhoto()
                        R.id.galleryButton -> pickPhoto()
                    }
                }

                R.id.imageButton_reflect_y_axis, R.id.imageButton_reflect_x_axis -> {
                    cancelCurrentJobs(
                        isCheckGrayScaleCanceled = false, isImageUriToBitmapCanceled = false
                    )
                    updateSelectedProcess(v.id)
                    imageProcessingJob = this.lifecycleScope.launch(Dispatchers.Main) {
                        reflectBitmap(isReflectOnXAxis = v.id == R.id.imageButton_reflect_x_axis)
                    }
                }

                R.id.imageButton_resize, R.id.imageButton_crop, R.id.imageButton_pixelate -> {
                    cancelCurrentJobs(
                        isCheckGrayScaleCanceled = false, isImageUriToBitmapCanceled = false
                    )
                    updateSelectedProcess(v.id)
                }

                R.id.button_process -> {
                    cancelCurrentJobs(
                        isCheckGrayScaleCanceled = false, isImageUriToBitmapCanceled = false
                    )
                    if (selectedProcess == R.id.imageButton_resize || selectedProcess == R.id.imageButton_pixelate) {
                        val width = binding.textInputEditTextWidth.text.toString()
                        val height = binding.textInputEditTextHeight.text.toString()

                        if (checkIfInputsValid(listOf(width, height))) {
                            imageProcessingJob = this.lifecycleScope.launch(Dispatchers.Main) {
                                when (selectedProcess) {
                                    R.id.imageButton_resize -> resizeBitmap(
                                        width.toDouble(), height.toDouble()
                                    )
                                    R.id.imageButton_pixelate -> pixelateBitmap(
                                        width.toDouble(), height.toDouble()
                                    )
                                }
                            }
                        }
                    } else if (selectedProcess == R.id.imageButton_crop) {
                        val fromX = binding.textInputEditTextFromX.text.toString()
                        val fromY = binding.textInputEditTextFromY.text.toString()
                        val toX = binding.textInputEditTextToX.text.toString()
                        val toY = binding.textInputEditTextToY.text.toString()
                        if (checkIfInputsValid(listOf(fromX, fromY, toX, toY))) {
                            imageProcessingJob = this.lifecycleScope.launch(Dispatchers.Main) {
                                cropBitmap(
                                    fromX.toDouble(),
                                    fromY.toDouble(),
                                    toX.toDouble(),
                                    toY.toDouble()
                                )
                            }
                        }
                    }
                }

                R.id.button_rgbToHsi, R.id.button_rgbToHsv, R.id.textView_rgb, R.id.button_averageThreshold, R.id.button_otsuThreshold, R.id.button_specialThreshold,
                R.id.button_minFilter, R.id.button_maxFilter, R.id.button_medianFilter, R.id.button_averageFilter,
                R.id.button_laplacianFilter, R.id.button_sobelGradient, R.id.button_gammaTransformation,
                R.id.button_highPassFilter, R.id.button_wienerFilter -> {
                    cancelCurrentJobs(isImageUriToBitmapCanceled = false)
                    updateSelectedProcess(v.id)
                    imageProcessingJob = this.lifecycleScope.launch(Dispatchers.Main) {
                        filterOrTransformBitmap(
                            when (v.id) {
                                R.id.button_rgbToHsi -> RGB_TO_HSI
                                R.id.button_rgbToHsv -> RGB_TO_HSV
                                R.id.textView_rgb -> RGB_TO_GRAY
                                R.id.button_averageThreshold -> AVERAGE_THRESHOLD
                                R.id.button_otsuThreshold -> OTSU_THRESHOLD
                                R.id.button_specialThreshold -> SPECIAL_THRESHOLD
                                R.id.button_minFilter -> MIN_FILTER
                                R.id.button_maxFilter -> MAX_FILTER
                                R.id.button_medianFilter -> MEDIAN_FILTER
                                R.id.button_averageFilter -> AVERAGE_FILTER
                                R.id.button_laplacianFilter -> LAPLACIAN_FILTER
                                R.id.button_sobelGradient -> SOBEL_GRADIENT
                                R.id.button_gammaTransformation -> GAMMA_TRANSFORMATION
                                R.id.button_highPassFilter -> HIGH_PASS_FILTER
                                R.id.button_wienerFilter -> WIENER_FILTER
                                else -> RGB_TO_GRAY
                            }
                        )
                    }
                }

                R.id.button_specialOperation, R.id.button_lungSegmentation, R.id.button_specialOperation2 -> {
                    cancelCurrentJobs(isImageUriToBitmapCanceled = false)
                    updateSelectedProcess(v.id)
                    imageProcessingJob = this.lifecycleScope.launch(Dispatchers.Main) {
                        slideshow(
                            when (v.id) {
                                R.id.button_specialOperation -> SPECIAL_OPERATION_1
                                R.id.button_specialOperation2 -> SPECIAL_OPERATION_2
                                else -> LUNG_SEGMENTATION
                            }
                        )
                    }
                }

                else -> {}
            }
        }
    }

    private fun cancelCurrentJobs(
        isImageProcessingCanceled: Boolean = true,
        isCheckGrayScaleCanceled: Boolean = true,
        isImageUriToBitmapCanceled: Boolean = true
    ) {
        if (isImageProcessingCanceled && imageProcessingJob?.isActive == true) imageProcessingJob?.cancel()
        if (isCheckGrayScaleCanceled && checkGrayScaleJob?.isActive == true) checkGrayScaleJob?.cancel()
        if (isImageUriToBitmapCanceled && imageUriToBitmapDeferred?.isActive == true) imageUriToBitmapDeferred?.cancel()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
            // Camera permission is granted, start camera.
            startCamera()
        }
    }

    private fun registerActivityResultCallbacks() {
        // Registers a photo picker activity launcher in single-select mode.
        pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            // Callback is invoked after the user selects a media item or closes the
            // photo picker.
            if (uri != null) {
                Log.d(TAG, "Selected URI: $uri")
                try {
                    imageUri = uri
                    updateImageView(uri)

                    imageUriToBitmapDeferred = this.lifecycleScope.async(Dispatchers.Default) {
                        val bitmap = imageUri?.let { uriToBitmap(it) }
                        if (bitmap != null) {
                            addToImageStack(bitmap)
                        }
                        bitmap
                    }

                    checkIfGrayScale()
                } catch (e: Exception) {
                    Log.e(TAG, "Picking image from media failed: ${e.message}", e)
                }
            } else {
                Log.d(TAG, "No media selected")
            }
        }
    }

    private fun pickPhoto() {
        pickMedia?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Set up the capture use case to allow users to take photos.
            imageCapture =
                ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

            val cameraSelector: CameraSelector =
                CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, imageCapture
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Camera use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Take photo when camera button clicked.
    private fun takePhoto() {
        // If camera permission is not granted, return.
        if (!allPermissionsGranted()) {
            return
        }
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return
        showProgressBar(true)

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()


        imageCapture.takePicture(outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    runOnUiThread {
                        showProgressBar(false)
                    }
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        // Photo taken from the Camera,
                        // show it in ImageView.
                        imageUri = outputFileResults.savedUri
                        runOnUiThread {
                            updateImageView(imageUri)
                        }

                        imageUriToBitmapDeferred = CoroutineScope(Dispatchers.Default).async {
                            val bitmap = imageUri?.let { uriToBitmap(it) }
                            if (bitmap != null) {
                                addToImageStack(bitmap)
                            }
                            bitmap
                        }

                        // Check if image is grayscale.
                        checkIfGrayScale()
                    } catch (e: Exception) {
                        Log.e(TAG, "Loading image uri to ImageView failed: ${e.message}", e)
                    } finally {
                        runOnUiThread {
                            showProgressBar(false)
                        }
                    }
                }
            })
    }

    // Decode Uri to Bitmap, and then use pixelate algorithm on the Bitmap.
    private suspend fun pixelateBitmap(width: Double, height: Double) {
        try {
            showProgressBar(true)
            imageBitmap = imageUriToBitmapDeferred?.await()

            // Since this operation takes time, we use Dispatchers.Default,
            // which is optimized for time consuming calculations.
            if (checkEnoughTimePassed() && imageBitmap != null) {
                val pixelatedBitmapDrawable = withContext(Dispatchers.Default) {
                    invokePixelation(
                        bitmap = imageBitmap!!,
                        pixelWidth = (width.toInt()).coerceAtMost(imageBitmap!!.width),
                        pixelHeight = (height.toInt()).coerceAtMost(imageBitmap!!.height),
                        resources = resources
                    )
                }

                updateImageView(pixelatedBitmapDrawable)

                imageUriToBitmapDeferred = CoroutineScope(Dispatchers.Default).async {
                    val bitmap = pixelatedBitmapDrawable.bitmap
                    addToImageStack(bitmap)
                    bitmap
                }
            } else {
                Log.e(
                    TAG, "Not enough time has passed to re-pixate, or ImageBitmap is null."
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Pixelating bitmap failed: ${e.message}", e)
        } finally {
            showProgressBar(false)
        }
    }

    private suspend fun reflectBitmap(isReflectOnXAxis: Boolean) {
        try {
            showProgressBar(true)
            imageBitmap = imageUriToBitmapDeferred?.await()

            if (checkEnoughTimePassed() && imageBitmap != null) {
                // Since this operation takes time, we use Dispatchers.Default,
                // which is optimized for time consuming calculations.
                val reflectedBitmapDrawable = withContext(Dispatchers.Default) {
                    if (isReflectOnXAxis) {
                        reflectOnXAxis(
                            bitmap = imageBitmap!!, resources = resources
                        )
                    } else {
                        reflectOnYAxis(
                            bitmap = imageBitmap!!, resources = resources
                        )
                    }
                }

                updateImageView(reflectedBitmapDrawable)

                imageUriToBitmapDeferred = CoroutineScope(Dispatchers.Default).async {
                    val bitmap = reflectedBitmapDrawable.bitmap
                    addToImageStack(bitmap)
                    bitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reflecting bitmap failed: ${e.message}", e)
        } finally {
            showProgressBar(false)
        }
    }

    private suspend fun resizeBitmap(width: Double, height: Double) {
        try {
            showProgressBar(true)
            imageBitmap = imageUriToBitmapDeferred?.await()

            if (checkEnoughTimePassed() && imageBitmap != null && width <= MAX_WIDTH && height <= MAX_HEIGHT) {
                // Since this operation takes time, we use Dispatchers.Default,
                // which is optimized for time consuming calculations.
                val resizedBitmapDrawable = withContext(Dispatchers.Default) {
                    resize(
                        bitmap = imageBitmap!!,
                        width = width.toInt(),
                        height = height.toInt(),
                        resources = resources
                    )
                }

                updateImageView(resizedBitmapDrawable)

                imageUriToBitmapDeferred = CoroutineScope(Dispatchers.Default).async {
                    val bitmap = resizedBitmapDrawable.bitmap
                    addToImageStack(bitmap)
                    bitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Resizing bitmap failed: ${e.message}", e)
        } finally {
            showProgressBar(false)
        }
    }

    private suspend fun cropBitmap(
        fromXRatio: Double, fromYRatio: Double, toXRatio: Double, toYRatio: Double
    ) {
        try {
            showProgressBar(true)
            imageBitmap = imageUriToBitmapDeferred?.await()
            // X and Y points are taken proportional to the width and height of the bitmap
            if (checkEnoughTimePassed() && imageBitmap != null && fromXRatio <= 1 && fromYRatio <= 1 && toXRatio <= 1 && toYRatio <= 1 && fromXRatio < toXRatio && fromYRatio < toYRatio) {
                // Since this operation takes time, we use Dispatchers.Default,
                // which is optimized for time consuming calculations.
                val croppedBitmapDrawable = withContext(Dispatchers.Default) {
                    crop(
                        bitmap = imageBitmap!!,
                        fromX = (fromXRatio * imageBitmap!!.width).toInt(),
                        fromY = (fromYRatio * imageBitmap!!.height).toInt(),
                        toX = (toXRatio * imageBitmap!!.width).toInt(),
                        toY = (toYRatio * imageBitmap!!.height).toInt(),
                        resources = resources
                    )
                }

                updateImageView(croppedBitmapDrawable)

                imageUriToBitmapDeferred = CoroutineScope(Dispatchers.Default).async {
                    val bitmap = croppedBitmapDrawable.bitmap
                    addToImageStack(bitmap)
                    bitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cropping bitmap failed: ${e.message}", e)
        } finally {
            showProgressBar(false)
        }
    }

    private suspend fun filterOrTransformBitmap(filterOrTransformName: String) {
        try {
            showProgressBar(true)
            imageBitmap = imageUriToBitmapDeferred?.await()

            if (checkEnoughTimePassed() && imageBitmap != null) {
                // Since this operation takes time, we use Dispatchers.Default,
                // which is optimized for time consuming calculations.
                val filteredBitmapDrawable = withContext(Dispatchers.Default) {
                    when (filterOrTransformName) {
                        MIN_FILTER -> minFilter(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        MAX_FILTER -> maxFilter(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        MEDIAN_FILTER -> medianFilter(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        AVERAGE_FILTER -> averageFilter(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        LAPLACIAN_FILTER -> laplacianFilter(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        SOBEL_GRADIENT -> sobelGradientFilter(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        GAMMA_TRANSFORMATION -> gammaTransformation(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        RGB_TO_HSI -> rgbToHsi(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        RGB_TO_HSV -> rgbToHsv(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        AVERAGE_THRESHOLD -> applyAverageThreshold(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        OTSU_THRESHOLD -> applyOtsuMethod(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        SPECIAL_THRESHOLD -> applySpecialThreshold(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        else -> {
                            val grayBitmap = rgbToGray(
                                bitmap = imageBitmap!!, resources = resources
                            )
                            withContext(Dispatchers.Main) {
                                binding.apply {
                                    textViewRgb.visibility = View.GONE
                                    textViewGrayScale.visibility = View.VISIBLE
                                }
                            }
                            grayBitmap
                        }
                    }
                }

                updateImageView(filteredBitmapDrawable)

                imageUriToBitmapDeferred = CoroutineScope(Dispatchers.Default).async {
                    val bitmap = filteredBitmapDrawable.bitmap
                    addToImageStack(bitmap)
                    bitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Filtering-Transforming bitmap failed: ${e.message}", e)
        } finally {
            showProgressBar(false)
        }
    }

    private fun checkIfGrayScale() {
        checkGrayScaleJob = this.lifecycleScope.launch {
            imageBitmap = imageUriToBitmapDeferred?.await()
            imageBitmap?.let { bitmap ->
                val isGrayScale = withContext(Dispatchers.Default) {
                    isGrayScale(bitmap)
                }

                binding.apply {
                    textViewGrayScale.visibility = if (isGrayScale) View.VISIBLE else View.GONE
                    textViewRgb.visibility = if (!isGrayScale) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private suspend fun slideshow(specialOperationName: String) {
        try {
            showProgressBar(true)
            imageBitmap = imageUriToBitmapDeferred?.await()

            if (checkEnoughTimePassed() && imageBitmap != null) {
                // Since this operation takes time, we use Dispatchers.Default,
                // which is optimized for time consuming calculations.
                val listOfBitmapDrawables = withContext(Dispatchers.Default) {
                    when (specialOperationName) {
                        SPECIAL_OPERATION_1 -> specialImageOperations(
                            originalBitmap = imageBitmap!!, resources = resources
                        )
                        SPECIAL_OPERATION_2 -> specialFrequencyOperation(
                            bitmap = imageBitmap!!, resources = resources
                        )
                        else -> lungOperations(
                            originalBitmap = imageBitmap!!, resources = resources
                        )
                    }
                }

                imageUriToBitmapDeferred = CoroutineScope(Dispatchers.Default).async {
                    val bitmap = listOfBitmapDrawables.last().bitmap
                    bitmap
                }

                // Update imageView with list of images with 3 second delay
                listOfBitmapDrawables.forEachIndexed { index, bitmapDrawable ->
                    updateImageView(bitmapDrawable)
                    addToImageStack(bitmapDrawable.bitmap)
                    Toast.makeText(
                        this,
                        "Image ${index + 1} of ${listOfBitmapDrawables.size}",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (index < listOfBitmapDrawables.size - 1) {
                        delay(2000)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Special bitmap operation failed: ${e.message}", e)
        } finally {
            showProgressBar(false)
        }
    }

    private fun checkIfInputsValid(inputs: List<String>) = inputs.all { input ->
        input.isNotEmpty() && input.toDouble() > 0
    }

    // Load image to imageView
    private fun updateImageView(image: Any?) {
        if (image is Uri || image is BitmapDrawable) {
            image.let {
                Glide.with(this).load(it).into(binding.imageView)
            }
        }
    }

    private suspend fun addToImageStack(bitmap: Bitmap) {
        // If stack size is already IMAGE_STACK_SIZE_MAX, remove the first element
        if (imageStack.size == IMAGE_STACK_SIZE_MAX) {
            imageStack.removeAt(0)
        }

        imageStack.addLast(bitmap)
        if (imageStack.size > IMAGE_STACK_SIZE_MIN) {
            withContext(Dispatchers.Main) {
                binding.imageButtonUndo.visibility = View.VISIBLE
            }
        }
    }

    private fun removeFromImageStack() {
        if (imageStack.size > IMAGE_STACK_SIZE_MIN) {
            imageStack.removeLast()
            imageUriToBitmapDeferred = this.lifecycleScope.async(Dispatchers.Default) {
                imageStack.last()
            }
            updateImageView(imageStack.last().toDrawable(resources))
            if (binding.progresBar.isVisible) {
                showProgressBar(false)
            }
        }
        if (imageStack.size == IMAGE_STACK_SIZE_MIN) {
            binding.imageButtonUndo.visibility = View.GONE
        }
    }

    private fun updateSelectedProcess(imageButtonId: Int?) {
        clearInputTextFields()

        val allImageButtons = listOf(
            binding.imageButtonPixelate, binding.imageButtonResize, binding.imageButtonCrop
        )
        selectedProcess = imageButtonId

        if (imageButtonId != null) {
            binding.buttonProcess.apply {
                text = when (imageButtonId) {
                    R.id.imageButton_resize -> getString(R.string.resize)
                    R.id.imageButton_crop -> getString(R.string.crop)
                    R.id.imageButton_pixelate -> getString(R.string.pixelate)
                    else -> text.toString()
                }
            }
        }

        allImageButtons.forEach { button ->
            button.background = if (button.id == imageButtonId) {
                ContextCompat.getDrawable(this, R.drawable.image_button_selected_background)
            } else {
                ContextCompat.getDrawable(this, R.drawable.image_button_unselected_background)
            }
        }

        val cropInputLayouts = listOf(
            binding.textInputLayoutFromX,
            binding.textInputLayoutFromY,
            binding.textInputLayoutToX,
            binding.textInputLayoutToY
        )
        val widthAndHeightLayouts = listOf(
            binding.textInputLayoutWidth, binding.textInputLayoutHeight

        )

        cropInputLayouts.forEach {
            it.visibility =
                if (imageButtonId != null && imageButtonId == R.id.imageButton_crop) View.VISIBLE else View.GONE
        }
        widthAndHeightLayouts.forEach {
            it.visibility =
                if (imageButtonId != null && (imageButtonId == R.id.imageButton_resize || imageButtonId == R.id.imageButton_pixelate)) View.VISIBLE else View.GONE
        }

        if (imageButtonId != null && (imageButtonId == R.id.imageButton_pixelate || imageButtonId == R.id.imageButton_crop || imageButtonId == R.id.imageButton_resize)) {
            binding.apply {
                buttonProcess.visibility = View.VISIBLE
                viewSeparatorLineVertical.visibility = View.VISIBLE
            }
        } else {
            binding.apply {
                buttonProcess.visibility = View.GONE
                viewSeparatorLineVertical.visibility = View.GONE
            }
        }
    }

    private fun hideGrayAndRgbTextView() {
        binding.apply {
            textViewGrayScale.visibility = View.GONE
            textViewRgb.visibility = View.GONE
        }
    }

    private fun showProgressBar(isShown: Boolean) {
        binding.apply {
            progresBar.visibility = if (isShown) View.VISIBLE else View.GONE
            val clickableViews = listOf(
                buttonProcess,
                cameraButton,
                galleryButton,
                textViewRgb,
                imageButtonReflectYAxis,
                imageButtonReflectXAxis,
                imageButtonResize,
                imageButtonCrop,
                imageButtonPixelate,
                buttonRgbToHsv,
                buttonRgbToHsi,
                buttonMinFilter,
                buttonMaxFilter,
                buttonMedianFilter,
                buttonAverageFilter,
                buttonLaplacianFilter,
                buttonSobelGradient,
                buttonSpecialOperation,
                buttonGammaTransformation
            )
            clickableViews.forEach { it.isEnabled = !isShown }
        }
    }

    private fun clearInputTextFields() {
        binding.apply {
            textInputEditTextFromX.text?.clear()
            textInputEditTextFromY.text?.clear()
            textInputEditTextToX.text?.clear()
            textInputEditTextToY.text?.clear()
            textInputEditTextWidth.text?.clear()
            textInputEditTextHeight.text?.clear()
        }
    }

    private fun setEditTextFilters() {
        binding.apply {
            textInputEditTextFromX.filters = arrayOf(InputFilterMinMax(0.0, 1.0))
            textInputEditTextFromY.filters = arrayOf(InputFilterMinMax(0.0, 1.0))
            textInputEditTextToX.filters = arrayOf(InputFilterMinMax(0.0, 1.0))
            textInputEditTextToY.filters = arrayOf(InputFilterMinMax(0.0, 1.0))
            textInputEditTextWidth.filters = arrayOf(InputFilterMinMax(0.0, MAX_WIDTH.toDouble()))
            textInputEditTextHeight.filters = arrayOf(InputFilterMinMax(0.0, MAX_HEIGHT.toDouble()))
        }
    }

    // Decode image Uri to Bitmap
    private fun uriToBitmap(uri: Uri) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(
                contentResolver, uri
            )
        ) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
    } else {
        @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(contentResolver, uri)
    }

    override fun onStop() {
        super.onStop()
        cancelCurrentJobs()
        cameraExecutor.shutdown()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private val REQUIRED_PERMISSIONS = mutableListOf(
            CAMERA,
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}