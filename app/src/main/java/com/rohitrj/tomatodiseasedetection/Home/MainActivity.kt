package com.rohitrj.tomatodiseasedetection.Home

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.CameraX
import androidx.camera.core.Preview
import androidx.camera.core.PreviewConfig
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.ml.common.FirebaseMLException
import com.google.firebase.ml.common.modeldownload.FirebaseLocalModel
import com.google.firebase.ml.common.modeldownload.FirebaseModelDownloadConditions
import com.google.firebase.ml.common.modeldownload.FirebaseModelManager
import com.google.firebase.ml.common.modeldownload.FirebaseRemoteModel
import com.google.firebase.ml.custom.FirebaseModelDataType
import com.google.firebase.ml.custom.FirebaseModelInputOutputOptions
import com.google.firebase.ml.custom.FirebaseModelInterpreter
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.label.FirebaseVisionImageLabel
import com.google.firebase.ml.vision.label.FirebaseVisionImageLabeler
import com.google.firebase.ml.vision.label.FirebaseVisionOnDeviceAutoMLImageLabelerOptions
import com.rohitrj.tomatodiseasedetection.R
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.collections.ArrayList


private const val REQUEST_CODE_PERMISSIONS = 10
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

lateinit var mSelectedImage: Bitmap
lateinit var mGraphicOverlay: GraphicOverlay


val TAG = "MainActivity"
/**
 * An instance of the driver class to run model inference with Firebase.
 */
var mInterpreter: FirebaseModelInterpreter? = null
var labeler: FirebaseVisionImageLabeler? = null
/**
 * Data configuration of input & output data of model.
 */
lateinit var mDataOptions: FirebaseModelInputOutputOptions
/**
 * Name of the model file hosted with Firebase.
 */
private val HOSTED_MODEL_NAME = "Tomato_20191022143216"
private val LOCAL_MODEL_ASSET = "Tomato.tflite"
/**
 * Name of the label file stored in Assets.
 */
private val LABEL_PATH = "labels.txt"
/**
 * Number of results to show in the UI.
 */
private val RESULTS_TO_SHOW = 1
/**
 * Dimensions of inputs.
 */
private val DIM_BATCH_SIZE = 1
private val DIM_PIXEL_SIZE = 3
private val DIM_IMG_SIZE_X = 100
private val DIM_IMG_SIZE_Y = 100
/**
 * Labels corresponding to the output of the vision model.
 */

private var mLabelList: List<String>? = null
private val intValues = IntArray(DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y)


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mGraphicOverlay = findViewById(R.id.graphicOverlay)

        // Request camera permissions
        if (allPermissionsGranted()) {
            camera_view.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Every time the provided texture view changes, recompute layout
        camera_view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }

        initCustomModel()

        camera_view.setOnClickListener {

            mSelectedImage = camera_view.bitmap
            runModelInference()

        }
    }
    private fun startCamera() {
        // Create configuration object for the viewfinder use case
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetAspectRatio(Rational(1, 2))
            setTargetResolution(Size(800, 1200))
        }.build()
        // Build the viewfinder use case
        val preview = Preview(previewConfig)


        // Every time the viewfinder is updated, recompute layout
        preview.setOnPreviewOutputUpdateListener {

            // To update the SurfaceTexture, we have to remove it and re-add it
            val parent = camera_view.parent as ViewGroup
            parent.removeView(camera_view)
            parent.addView(camera_view, 0)

            camera_view.surfaceTexture = it.surfaceTexture
            updateTransform()
        }

        val contactUSBottonSheet = ContactUSBottonSheet()
        contactUSBottonSheet.show(supportFragmentManager, "lol")

        // Bind use cases to lifecycle
        // If Android Studio complains about "this" being not a LifecycleOwner
        // try rebuilding the project or updating the appcompat dependency to
        // version 1.1.0 or higher.
        CameraX.bindToLifecycle(this, preview)
    }

    private fun updateTransform() {
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = camera_view.width / 2f
        val centerY = camera_view.height / 2f

        // Correct preview output to account for display rotation
        val rotationDegrees = when (camera_view.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Finally, apply transformations to our TextureView
        camera_view.setTransform(matrix)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                camera_view.post { startCamera() }
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun initCustomModel() {

        mLabelList = loadLabelList()
        val inputDims = intArrayOf(DIM_BATCH_SIZE, DIM_IMG_SIZE_X, DIM_IMG_SIZE_Y, DIM_PIXEL_SIZE)
        val outputDims = intArrayOf(DIM_BATCH_SIZE, mLabelList!!.size)

        try {
            mDataOptions = FirebaseModelInputOutputOptions.Builder()
                .setInputFormat(0, FirebaseModelDataType.BYTE, inputDims)
                .setOutputFormat(0, FirebaseModelDataType.BYTE, outputDims)
                .build()

            val conditions: FirebaseModelDownloadConditions = FirebaseModelDownloadConditions
                .Builder()
                .build()

            val remoteModel: FirebaseRemoteModel = FirebaseRemoteModel.Builder(HOSTED_MODEL_NAME)
                .enableModelUpdates(true)
                .setInitialDownloadConditions(conditions)
                .setUpdatesDownloadConditions(conditions)  // You could also specify
                // different conditions
                // for updates
                .build()

            val localModel: FirebaseLocalModel = FirebaseLocalModel.Builder("asset")
                .setAssetFilePath(LOCAL_MODEL_ASSET).build()

            val manager: FirebaseModelManager = FirebaseModelManager.getInstance()
            manager.registerRemoteModel(remoteModel)
            manager.registerLocalModel(localModel)

            val modelOptions = FirebaseVisionOnDeviceAutoMLImageLabelerOptions.Builder()
//                .setConfidenceThreshold(.6f)
                .setRemoteModelName(HOSTED_MODEL_NAME)
                .setLocalModelName("asset")
                .build()

            labeler = FirebaseVision.getInstance().getOnDeviceAutoMLImageLabeler(modelOptions)

        } catch (e: FirebaseMLException) {
            showToast("Error while setting up the model")
            e.printStackTrace()
        }

    }

    private fun runModelInference() {
        mGraphicOverlay.clear()

        if (labeler == null) {
            Log.e(TAG, "Image classifier has not been initialized; Skipped.")
            return
        }
        val image = FirebaseVisionImage.fromBitmap(mSelectedImage)

        labeler!!.processImage(image).continueWith { task ->

            val labelProbList = task.result

            // Indicate whether the remote or local model is used.
            // Note: in most common cases, once a remote model is downloaded it will be used. However, in
            // very rare cases, the model itself might not be valid, and thus the local model is used. In
            // addition, since model download failures can be transient, and model download can also be
            // triggered in the background during inference, it is possible that a remote model is used
            // even if the first download fails.
            val textToShow =  if (labelProbList.isNullOrEmpty())
                "No Result\n Try again"
            else
                printTopKLabels(labelProbList)

            val topLabels = listOf(textToShow)

            Log.i("labels", textToShow)
            val labelGraphic: GraphicOverlay.Graphic =
                LabelGraphic(mGraphicOverlay, topLabels)
            mGraphicOverlay.add(labelGraphic)

            // print the results
            textToShow
        }

    }

    /** Prints top-K labels, to be shown in UI as the results.  */
    private val printTopKLabels: (List<FirebaseVisionImageLabel>) -> String = {
        it.joinToString(
            separator = "\n",
            limit = RESULTS_TO_SHOW
        ) { label ->
            String.format(Locale.getDefault(), "%s :: Confidence: %4.2f" , label.text, label.confidence)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Reads label list from Assets.
     */
    private fun loadLabelList(): List<String> {
        val labelList = ArrayList<String>()
        val assetManager: AssetManager = resources.assets
        val inputStream: InputStream?
        try {
            inputStream = assetManager.open(LABEL_PATH)
            val s = Scanner(inputStream)
            while (s.hasNext()) {
//                Log.i("labelstxt",s.nextLine() )
                labelList.add(s.nextLine())
            }

        } catch (e: IOException) {
            e.printStackTrace()
        }
        return labelList
    }


}

