package com.copycat.mycamerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.android.synthetic.main.activity_main.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private val identify = Identify()
    private val resultLabel: MutableList<String?> = ArrayList()

    private lateinit var bitmapBuffer: Bitmap

    private val executor = Executors.newSingleThreadExecutor()
    private val permissions = listOf(Manifest.permission.CAMERA)
    private val permissionsRequestCode = Random.nextInt(0, 10000)

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private val isFrontFacing get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    private var pauseAnalysis = false
    private var imageRotationDegrees: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initiate NCNN
        val retInit = identify.init(assets)
        if (!retInit) {
            Log.e("Init", "Init failed!")
        }
        readCacheLabelFromLocalFile()

        pause_button.setOnClickListener {

            // Disable all camera controls
            it.isEnabled = false

            if (pauseAnalysis) {
                // If image analysis is in paused state, resume it
                pauseAnalysis = false
                image_predicted.visibility = View.GONE

            } else {
                // Otherwise, pause image analysis and freeze image
                pauseAnalysis = true
            }

            // Re-enable camera controls
            it.isEnabled = true
        }
    }

    /** Declare and bind preview and analysis use cases */
    @SuppressLint("UnsafeExperimentalUsageError")
    private fun bindCameraUseCases() = viewFinder.post {

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {

            // Camera provider is now guaranteed to be available
            val cameraProvider = cameraProviderFuture.get()

            // Set up the view finder use case to display camera preview
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(viewFinder.display.rotation)
                .build()

            // Set up the image analysis use case which will process frames in real time
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val converter = YuvToRgbConverter(this)
            imageAnalysis.setAnalyzer(executor, ImageAnalysis.Analyzer { image ->
                if (!::bitmapBuffer.isInitialized) {
                    // The image rotation and RGB image buffer are initialized only once
                    // the analyzer has started running
                    imageRotationDegrees = image.imageInfo.rotationDegrees
                    bitmapBuffer = Bitmap.createBitmap(
                        image.width, image.height, Bitmap.Config.ARGB_8888)
                }

                // Early exit: image analysis is in paused state
                if (pauseAnalysis) {
                    image.close()
                    runOnUiThread{
                        drawResults(pauseAnalysis)
                    }
                    return@Analyzer
                }

                // Convert the image to RGB and place it in our shared buffer
                image.use { converter.yuvToRgb(image.image!!, bitmapBuffer) }

                drawResults(pauseAnalysis)
            })

            // Create a new camera selector each time, enforcing lens facing
            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            // Apply declared configs to CameraX using the same lifecycle owner
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this as LifecycleOwner, cameraSelector, preview, imageAnalysis)

            // Use the camera object to link our preview use case with the view
            preview.setSurfaceProvider(viewFinder.createSurfaceProvider())

        }, ContextCompat.getMainExecutor(this))
    }

    private fun drawResults(paused: Boolean) {
        runOnUiThread{
            // After converting to RGB the image becomes vertical and had to be flipped
            val matrix = Matrix().apply {
                postRotate(imageRotationDegrees.toFloat())
                if (isFrontFacing) postScale(-1f, 1f)
            }
            val uprightImage = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)

            // If paused set the latest image to image view
            if (paused) {image_predicted!!.setImageBitmap(uprightImage); image_predicted.visibility = View.VISIBLE}

            // Since the model is trained with 300*300, incoming frame has to be resized
            val temp = Bitmap.createScaledBitmap(uprightImage, 300, 300, false)
            val result = identify.detect(temp, true)

            // Create a empty bitmap to draw detection boxes
            val emptyBB = Bitmap.createBitmap(uprightImage.width, uprightImage.height, Bitmap.Config.ARGB_8888)

            val canvas = Canvas(emptyBB)
            val paint = Paint()
            val getFinalResult = backToTwoArrayLambda(result)
            val num = getFinalResult.size // 6 // number of labels
            var objectNum = 0

            // For all objects detected
            while (objectNum < num) {
                // Box setting
                paint.color = Color.MAGENTA
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f

                // Get rid of negatives
                val left : Float = if (getFinalResult[objectNum][2] * emptyBB.width < 0) 0F else getFinalResult[objectNum][2] * emptyBB.width
                val top : Float = if (getFinalResult[objectNum][3] * emptyBB.height < 0) 0F else getFinalResult[objectNum][3] * emptyBB.height
                val right : Float = if (getFinalResult[objectNum][4] * emptyBB.width < 0) 0F else getFinalResult[objectNum][4] * emptyBB.width
                val bottom : Float = if (getFinalResult[objectNum][5] * emptyBB.height < 0) 0F else getFinalResult[objectNum][5] * emptyBB.height

                // Get rid of stupid detections
                if (right < emptyBB.width.toFloat() * 0.9) {
                    canvas.drawRect(
                        left, top,
                        right, bottom , paint)

                    // Label setting
                    paint.color = Color.RED
                    paint.style = Paint.Style.FILL

                    // Set text size and draw labels
                    paint.textSize = 15f
                    canvas.drawText( resultLabel[getFinalResult[objectNum][0].toInt()].orEmpty() + " (" + getFinalResult[objectNum][1].toString() + ")",
                        left, top + 15, paint)
                }
                objectNum++
            }

            // Paused or not detection needs to be set
            detection!!.setImageBitmap(emptyBB)
        }
    }


    override fun onResume() {
        super.onResume()

        // Request permissions each time the app resumes, since they can be revoked at any time
        if (!hasPermissions(this)) {
            ActivityCompat.requestPermissions(
                this, permissions.toTypedArray(), permissionsRequestCode)
        } else {
            bindCameraUseCases()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionsRequestCode && hasPermissions(this)) {
            bindCameraUseCases()
        } else {
            finish() // If we don't have the required permissions, we can't run
        }
    }

    // Convenience method used to check if all permissions required by this app are granted
    private fun hasPermissions(context: Context) = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    // load label's name
    private fun readCacheLabelFromLocalFile() {
        try {
            val assetManager = applicationContext.assets
            val reader = BufferedReader(InputStreamReader(assetManager.open("words.txt")))
            var readLine: String?
            while (reader.readLine().also { readLine = it } != null) {
                resultLabel.add(readLine)
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("labelCache", "error $e")
        }
    }

    companion object {
        // Restore the one dimensional array back to two dimensional array
        val backToTwoArrayLambda = {floatInput: FloatArray? ->
            val num = floatInput!!.size / 6
            val floatOutput = Array(num) { FloatArray(6) }
            var k = 0
            for (i in 0 until num) {
                var j = 0
                while (j < 6) {
                    floatOutput[i][j] = floatInput[k]
                    k++
                    j++
                }
            }
            floatOutput
        }
    }

}

