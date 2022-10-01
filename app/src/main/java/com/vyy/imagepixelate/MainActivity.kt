package com.vyy.imagepixelate

import android.Manifest.permission.CAMERA
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.vyy.imagemosaicing.R
import com.vyy.imagemosaicing.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var pickMedia: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var imageUri: Uri? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        registerActivityResultCallbacks()
        setClickListeners()

        cameraExecutor = Executors.newSingleThreadExecutor()
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

    private fun registerActivityResultCallbacks() {
        // Registers a photo picker activity launcher in single-select mode.
        pickMedia =
            registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                // Callback is invoked after the user selects a media item or closes the
                // photo picker.
                if (uri != null) {
                    Log.d(TAG, "Selected URI: $uri")

                    try {
                        imageUri = uri
                        updateImageView(uri)
                    } catch (e: Exception) {
                        Log.e(TAG, "Decoding URI to Bitmap failed: ${e.message}", e)
                    }
                } else {
                    Log.d(TAG, "No media selected")
                }
            }
    }


    private fun setClickListeners() {
        binding.apply {
            cameraButton.setOnClickListener(this@MainActivity)
            buttonPixelate.setOnClickListener(this@MainActivity)
            galleryButton.setOnClickListener(this@MainActivity)
        }
    }

    override fun onClick(v: View?) {
        v?.let {
            when (v.id) {
                R.id.cameraButton -> {
                    takePhoto()
                }

                R.id.button_pixelate -> {
                    this.lifecycleScope.launch {
                        pixelateBitmap()
                    }
                }

                R.id.galleryButton -> {
                    pickPhoto()
                }
                else -> {}
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Set up the capture use case to allow users to take photos.
            imageCapture = ImageCapture.Builder()
                .setTargetRotation(binding.imageView.display.rotation)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector: CameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, imageCapture
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

            cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, imageCapture)

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    // TODO: Update permission request
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED)
        ) {
            startCamera()
        }
    }

    // Take photo when camera button clicked.
    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return
        showProgressDialog(true)

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()


        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    showProgressDialog(false)
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        imageUri = outputFileResults.savedUri
                        runOnUiThread {
                            updateImageView(imageUri)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Converting ImageProxy to Bitmap failed: ${e.message}", e)
                    } finally {
                        runOnUiThread {
                            showProgressDialog(false)
                        }
                    }

                }
            })
    }

    // Decode Uri to Bitmap, and then use pixelate algorithm on the Bitmap.
    private suspend fun pixelateBitmap() {
        imageUri?.let { uri ->
            val pixelWidth = binding.textInputEditTextPixelateWidth.text.toString()
            val pixelHeight = binding.textInputEditTextPixelateHeight.text.toString()
            if (pixelWidth.isNotEmpty() && pixelHeight.isNotEmpty()
                && pixelWidth.toInt() > 0 && pixelHeight.toInt() > 0
            ) {

                showProgressDialog(true)

                withContext(Dispatchers.Default) {
                    try {
                        val imageBitmap = uriToBitmap(uri)
                        // Check if pixel size is smaller then the image.
                        if (pixelWidth.toInt() < imageBitmap.width
                            && pixelHeight.toInt() < imageBitmap.height
                        ) {
                            val scaledBitmap = Bitmap.createScaledBitmap(
                                imageBitmap,
                                pixelWidth.toInt(),
                                pixelHeight.toInt(),
                                false
                            )
                            updateImageView(scaledBitmap)
                        } else {
                            Log.e(TAG, "Pixel size is bigger than actual image!")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Pixelating bitmap failed: ${e.message}", e)
                    } finally {
                        showProgressDialog(false)
                    }
                }
            }
        }
    }

    private fun pickPhoto() {
        pickMedia?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // Load image as Uri to imageView
    private fun updateImageView(uri: Uri?) {
        uri.let {
            Glide
                .with(this)
                .load(it)
                .into(binding.imageView)
        }
    }

    // Load image as Bitmap to imageView
    private fun updateImageView(bitmap: Bitmap?) {
        runOnUiThread {
            binding.imageView.setImageBitmap(bitmap)
        }
    }

    private fun showProgressDialog(isShown: Boolean) {
        runOnUiThread {
            binding.apply {
                progresBar.visibility = if (isShown) View.VISIBLE else View.GONE
                buttonPixelate.isEnabled = !isShown
                cameraButton.isEnabled = !isShown
                galleryButton.isEnabled = !isShown
            }
        }
    }

    // Decode image uri to Bitmap
    private fun uriToBitmap(uri: Uri) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(
                contentResolver,
                uri
            )
        )
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(contentResolver, uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                CAMERA,
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}