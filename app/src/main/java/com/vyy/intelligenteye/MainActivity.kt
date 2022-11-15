package com.vyy.intelligenteye

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
import android.widget.ImageButton
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
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.vyy.intelligenteye.databinding.ActivityMainBinding
import com.vyy.intelligenteye.processes.*
import com.vyy.intelligenteye.utils.Constants.FILENAME_FORMAT
import com.vyy.intelligenteye.utils.Constants.IMAGE_STACK_SIZE_MAX
import com.vyy.intelligenteye.utils.Constants.IMAGE_STACK_SIZE_MIN
import com.vyy.intelligenteye.utils.Constants.MAX_HEIGHT
import com.vyy.intelligenteye.utils.Constants.MAX_WIDTH
import com.vyy.intelligenteye.utils.Constants.REQUEST_CODE_PERMISSIONS
import com.vyy.intelligenteye.utils.InputFilterMinMax
import com.vyy.intelligenteye.utils.checkEnoughTimePassed
import kotlinx.coroutines.*
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
    private var imageUriToBitmapDeferred: Deferred<Bitmap?>? = null

    private var selectedProcess: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerActivityResultCallbacks()
        setClickListeners()
        setEditTextFilters()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStart() {
        super.onStart()

        checkPermissions()

        // Default Image
        if (imageStack.size < 1) {
            imageUri = Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(resources.getResourcePackageName(R.drawable.lenna))
                .appendPath(resources.getResourceTypeName(R.drawable.lenna))
                .appendPath(resources.getResourceEntryName(R.drawable.lenna)).build()
            updateImageView(imageUri)

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
            imageButtonReflectYAxis.setOnClickListener(this@MainActivity)
            imageButtonReflectXAxis.setOnClickListener(this@MainActivity)
            imageButtonResize.setOnClickListener(this@MainActivity)
            imageButtonCrop.setOnClickListener(this@MainActivity)
            imageButtonUndo.setOnClickListener(this@MainActivity)
        }
    }

    override fun onClick(v: View?) {
        v?.let {
            when (v.id) {
                R.id.imageButton_undo, R.id.cameraButton, R.id.galleryButton -> {
                    cancelCurrentJobs()
                    when (v.id) {
                        R.id.imageButton_undo -> removeFromImageStack()
                        R.id.cameraButton -> takePhoto()
                        R.id.galleryButton -> pickPhoto()
                    }
                }

                R.id.imageButton_reflect_y_axis, R.id.imageButton_reflect_x_axis -> {
                    cancelCurrentJobs(
                        isImageUriToBitmapCanceled = false
                    )

                    imageProcessingJob = this.lifecycleScope.launch(Dispatchers.Main) {
                        reflectBitmap(isReflectOnXAxis = v.id == R.id.imageButton_reflect_x_axis)
                    }
                }

                R.id.imageButton_resize, R.id.imageButton_crop -> {
                    cancelCurrentJobs(
                        isImageUriToBitmapCanceled = false
                    )

                    updateSelectedProcess(
                        when (v.id) {
                            R.id.imageButton_resize -> binding.imageButtonResize
                            else -> binding.imageButtonCrop
                        }
                    )
                }

                R.id.button_process -> {
                    cancelCurrentJobs(
                        isImageUriToBitmapCanceled = false
                    )

                    if (selectedProcess == R.id.imageButton_resize) {
                        val width = binding.textInputEditTextWidth.text.toString()
                        val height = binding.textInputEditTextHeight.text.toString()

                        if (checkIfInputsValid(listOf(width, height))) {
                            imageProcessingJob = this.lifecycleScope.launch(Dispatchers.Main) {
                                resizeBitmap(
                                    width.toDouble(), height.toDouble()
                                )
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

                else -> {}
            }
        }
    }

    private fun cancelCurrentJobs(
        isImageProcessingCanceled: Boolean = true, isImageUriToBitmapCanceled: Boolean = true
    ) {
        if (isImageProcessingCanceled && imageProcessingJob?.isActive == true) imageProcessingJob?.cancel()
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


        imageCapture.takePicture(
            outputOptions,
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

    private suspend fun reflectBitmap(isReflectOnXAxis: Boolean) {
        try {
            showProgressBar(true)
            imageBitmap = imageUriToBitmapDeferred?.await()

            updateSelectedProcess(
                if (isReflectOnXAxis) {
                    binding.imageButtonReflectXAxis
                } else {
                    binding.imageButtonReflectYAxis
                }
            )

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
        updateSelectedProcess(null)

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

    private fun updateSelectedProcess(imageButton: ImageButton?) {
        clearInputTextFields()

        val allImageButtons = listOf(
            binding.imageButtonResize, binding.imageButtonCrop
        )

        selectedProcess = imageButton?.id

        if (imageButton != null) {
            binding.buttonProcess.apply {
                text = when (imageButton.id) {
                    R.id.imageButton_resize -> getString(R.string.resize)
                    R.id.imageButton_crop -> getString(R.string.crop)
                    else -> text.toString()
                }
            }
        }

        allImageButtons.forEach { button ->
            button.background = if (button == imageButton) {
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

        if (imageButton != null && imageButton.id == R.id.imageButton_crop) {
            cropInputLayouts.forEach {
                it.visibility = View.VISIBLE
            }
        } else if (imageButton != null && imageButton.id == R.id.imageButton_resize) {
            widthAndHeightLayouts.forEach {
                it.visibility = View.VISIBLE
            }
        } else {
            cropInputLayouts.forEach {
                it.visibility = View.GONE
            }
            widthAndHeightLayouts.forEach {
                it.visibility = View.GONE
            }
        }

        if (imageButton != null && (imageButton.id == R.id.imageButton_crop || imageButton.id == R.id.imageButton_resize)) {
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

    private fun showProgressBar(isShown: Boolean) {
        binding.apply {
            progresBar.visibility = if (isShown) View.VISIBLE else View.GONE
            val clickableViews = listOf(
                buttonProcess,
                cameraButton,
                galleryButton,
                imageButtonReflectYAxis,
                imageButtonReflectXAxis,
                imageButtonResize,
                imageButtonCrop,
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
    }

    override fun onDestroy() {
        super.onDestroy()
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