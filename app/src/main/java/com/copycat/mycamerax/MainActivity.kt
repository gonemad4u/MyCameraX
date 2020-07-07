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

        // initiate ncnn shit
        val retInit = identify.init(assets)
        if (!retInit) {
            Log.e("FK", "identify Init failed")
        }
        readCacheLabelFromLocalFile()

        pause_button.setOnClickListener {

            // Disable all camera controls
            it.isEnabled = false

            if (pauseAnalysis) {
                // If image analysis is in paused state, resume it
                fkfkfk.setImageResource(android.R.color.transparent)
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

            var frameCounter = 0
            var lastFpsTimestamp = System.currentTimeMillis()
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
                        fkfkfk.setImageResource(android.R.color.transparent)
                        drawResults()
                    }
                    return@Analyzer
                }
                Log.d("Resolution", image.image?.height.toString() + " WITH " + image.image?.width.toString())
                // Convert the image to RGB and place it in our shared buffer
                image.use { converter.yuvToRgb(image.image!!, bitmapBuffer) }

                drawResults()


                // Compute the FPS of the entire pipeline
                val frameCount = 10
                if (++frameCounter % frameCount == 0) {
                    frameCounter = 0
                    val now = System.currentTimeMillis()
                    val delta = now - lastFpsTimestamp
                    val fps = 1000 * frameCount.toFloat() / delta
                    Log.d(TAG, "FPS: ${"%.02f".format(fps)}")
                    lastFpsTimestamp = now
                }
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

    // draws results
    private fun drawResults() {
        runOnUiThread{
            val matrix = Matrix().apply {
                postRotate(imageRotationDegrees.toFloat())
                if (isFrontFacing) postScale(-1f, 1f)
            }
            val uprightImage = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            val temp = Bitmap.createScaledBitmap(uprightImage, 300, 300, false)
            val result = identify.detect(temp, true)
            //
            val emptyBB = Bitmap.createBitmap(uprightImage.width, uprightImage.height, Bitmap.Config.ARGB_8888)

            val canvas = Canvas(emptyBB)
            val paint = Paint()
            val getFinalResult = backToTwoArrayLambda(result)
            val num = result!!.size / 6 // number of object
            // draw

            var objectNum = 0
            while (objectNum < num) {
                // draw on picture
                paint.color = Color.MAGENTA
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f
                canvas.drawRect(getFinalResult[objectNum][2] * emptyBB.width, getFinalResult[objectNum][3] * emptyBB.height,
                    getFinalResult[objectNum][4] * emptyBB.width, getFinalResult[objectNum][5] * emptyBB.height, paint)
                // draw label
                paint.color = Color.RED
                paint.style = Paint.Style.FILL

                // Set text size
                val testTextSize = 30f
                val text = getFinalResult[objectNum][1].toString()
                val bounds = Rect()
                paint.getTextBounds(text, 0, text.length, bounds)

                // Calculate the desired size as a proportion of our testTextSize.
                val desiredTextSize: Float = testTextSize * 50 / bounds.width()

                // Set the paint for that size.
                paint.textSize = desiredTextSize
                canvas.drawText( resultLabel[getFinalResult[objectNum][0].toInt()].orEmpty(),
                    getFinalResult[objectNum][2] * emptyBB.width , getFinalResult[objectNum][3] * emptyBB.height + 15, paint)
                canvas.drawText(getFinalResult[objectNum][1].toString(),
                    getFinalResult[objectNum][2] * emptyBB.width , getFinalResult[objectNum][3] * emptyBB.height + 35, paint)
                objectNum++
            }
            image_predicted!!.setImageBitmap(uprightImage)
            //
            image_predicted.visibility = View.VISIBLE
            fkfkfk!!.setImageBitmap(emptyBB)
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

    /** Convenience method used to check if all permissions required by this app are granted */
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
            Log.d("labelCache", "good")
        } catch (e: Exception) {
            Log.e("labelCache", "error $e")
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        // restore the one dimensional array back to two dimensional array
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
