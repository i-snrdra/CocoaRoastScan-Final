package com.daffa.cocoaroastscan

import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

class ClassificationActivity : AppCompatActivity() {
    
    private lateinit var ivResultImage: ImageView
    private lateinit var tvSkinCondition: TextView
    private lateinit var tvBeanColor: TextView
    private lateinit var tvRoastingStatus: TextView
    private lateinit var btnBack: ImageButton
    
    private var interpreterModelA: Interpreter? = null // Dikupas/Tidak Dikupas
    private var interpreterModelD: Interpreter? = null // Warna
    
    private val imageSize = 256
    private val pixelSize = 3
    private val imageMean = 0.0f
    private val imageStd = 255.0f
    
    // Labels for Model A (skin condition)
    private val labelsModelA = arrayOf("dikupas", "tidak_dikupas")
    
    // Labels for Model D (color)
    private val labelsModelD = arrayOf("cokelat", "cokelat_muda", "hitam")
    
    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        private const val TAG = "ClassificationActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_classification)
        
        initViews()
        setupClickListeners()
        loadModels()
        processImage()
    }
    
    private fun initViews() {
        ivResultImage = findViewById(R.id.iv_result_image)
        tvSkinCondition = findViewById(R.id.tv_skin_condition)
        tvBeanColor = findViewById(R.id.tv_bean_color)
        tvRoastingStatus = findViewById(R.id.tv_roasting_status)
        btnBack = findViewById(R.id.btn_back)
    }
    
    private fun setupClickListeners() {
        btnBack.setOnClickListener {
            finish()
        }
    }
    
    private fun loadModels() {
        try {
            interpreterModelA = Interpreter(loadModelFile("mobilenetv2_model_a_20250621_0107.tflite"))
            interpreterModelD = Interpreter(loadModelFile("mobilenetv2_model_d_warna_20250621_0202.tflite"))
            Log.d(TAG, "Models loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading models", e)
            Toast.makeText(this, "Error loading AI models", Toast.LENGTH_LONG).show()
        }
    }
    
    @Throws(IOException::class)
    private fun loadModelFile(filename: String): MappedByteBuffer {
        val fileDescriptor: AssetFileDescriptor = assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        val startOffset: Long = fileDescriptor.startOffset
        val declaredLength: Long = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    private fun processImage() {
        val imageUri = intent.getStringExtra(EXTRA_IMAGE_URI)
        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        
        try {
            val bitmap = when {
                imageUri != null -> {
                    val uri = Uri.parse(imageUri)
                    val inputStream = contentResolver.openInputStream(uri)
                    BitmapFactory.decodeStream(inputStream)
                }
                imagePath != null -> {
                    BitmapFactory.decodeFile(imagePath)
                }
                else -> {
                    Toast.makeText(this, "No image data found", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            
            if (bitmap != null) {
                // Rotate image if needed based on EXIF data
                val rotatedBitmap = rotateBitmapIfRequired(bitmap, imageUri, imagePath)
                
                // Display the image
                ivResultImage.setImageBitmap(rotatedBitmap)
                
                // Preprocess and classify
                val preprocessedBitmap = preprocessImage(rotatedBitmap)
                classifyImage(preprocessedBitmap)
            } else {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun rotateBitmapIfRequired(bitmap: Bitmap, imageUri: String?, imagePath: String?): Bitmap {
        try {
            val exif = when {
                imagePath != null -> ExifInterface(imagePath)
                imageUri != null -> {
                    val inputStream = contentResolver.openInputStream(Uri.parse(imageUri))
                    inputStream?.let { ExifInterface(it) }
                }
                else -> null
            }
            
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            ) ?: ExifInterface.ORIENTATION_UNDEFINED
            
            return when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                else -> bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading EXIF data", e)
            return bitmap
        }
    }
    
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    
    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        // Resize to 256x256
        return Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)
    }
    
    private fun classifyImage(bitmap: Bitmap) {
        try {
            val inputBuffer = convertBitmapToByteBuffer(bitmap)
            
            // Classify with Model A (skin condition)
            val resultA = classifyWithModelA(inputBuffer.duplicate())
            
            // Classify with Model D (color)
            val resultD = classifyWithModelD(inputBuffer.duplicate())
            
            // Display results
            displayResults(resultA, resultD)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during classification", e)
            Toast.makeText(this, "Error during classification", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * pixelSize)
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(imageSize * imageSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        var pixel = 0
        for (i in 0 until imageSize) {
            for (j in 0 until imageSize) {
                val value = intValues[pixel++]
                byteBuffer.putFloat(((value shr 16 and 0xFF) - imageMean) / imageStd)
                byteBuffer.putFloat(((value shr 8 and 0xFF) - imageMean) / imageStd)
                byteBuffer.putFloat(((value and 0xFF) - imageMean) / imageStd)
            }
        }
        
        return byteBuffer
    }
    
    private fun classifyWithModelA(inputBuffer: ByteBuffer): Pair<String, Float> {
        val result = Array(1) { FloatArray(labelsModelA.size) }
        
        interpreterModelA?.run(inputBuffer, result)
        
        val output = result[0]
        var maxIndex = 0
        var maxConfidence = output[0]
        
        for (i in output.indices) {
            if (output[i] > maxConfidence) {
                maxConfidence = output[i]
                maxIndex = i
            }
        }
        
        return Pair(labelsModelA[maxIndex], maxConfidence)
    }
    
    private fun classifyWithModelD(inputBuffer: ByteBuffer): Pair<String, Float> {
        val result = Array(1) { FloatArray(labelsModelD.size) }
        
        interpreterModelD?.run(inputBuffer, result)
        
        val output = result[0]
        var maxIndex = 0
        var maxConfidence = output[0]
        
        for (i in output.indices) {
            if (output[i] > maxConfidence) {
                maxConfidence = output[i]
                maxIndex = i
            }
        }
        
        return Pair(labelsModelD[maxIndex], maxConfidence)
    }
    
    private fun displayResults(resultA: Pair<String, Float>, resultD: Pair<String, Float>) {
        // Display skin condition
        val skinConditionText = when (resultA.first) {
            "dikupas" -> getString(R.string.peeled)
            "tidak_dikupas" -> getString(R.string.not_peeled)
            else -> resultA.first
        }
        val confidenceA = (resultA.second * 100).toInt()
        tvSkinCondition.text = "$skinConditionText ($confidenceA%)"
        
        // Display bean color
        val beanColorText = when (resultD.first) {
            "cokelat" -> getString(R.string.brown)
            "cokelat_muda" -> getString(R.string.light_brown)
            "hitam" -> getString(R.string.dark_brown)
            else -> resultD.first
        }
        tvBeanColor.text = beanColorText
        
        // Display roasting status based on color
        val roastingStatusText = when (resultD.first) {
            "cokelat" -> getString(R.string.mature)
            "cokelat_muda" -> getString(R.string.not_mature)
            "hitam" -> getString(R.string.over_roast)
            else -> "Unknown"
        }
        tvRoastingStatus.text = roastingStatusText
        
        Log.d(TAG, "Classification Results:")
        Log.d(TAG, "Model A: ${resultA.first} (${confidenceA}%)")
        Log.d(TAG, "Model D: ${resultD.first}")
        Log.d(TAG, "Roasting Status: $roastingStatusText")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        interpreterModelA?.close()
        interpreterModelD?.close()
    }
} 