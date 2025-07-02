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
import kotlin.math.exp

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
    
    // ===============================================
    // TESTING DIFFERENT LABEL ORDERS
    // ===============================================
    
    // OPTION 1: Alfabetikal (current)
    private val labelsModelA = arrayOf("dikupas", "tidak_dikupas")
    private val labelsModelD = arrayOf("cokelat", "cokelat_muda", "hitam")
    
    // OPTION 2: Reverse order (uncomment to test)
    // private val labelsModelA = arrayOf("tidak_dikupas", "dikupas") 
    // private val labelsModelD = arrayOf("hitam", "cokelat_muda", "cokelat")
    
    // OPTION 3: Different order (uncomment to test)
    // private val labelsModelD = arrayOf("cokelat_muda", "cokelat", "hitam")
    
    // ===============================================
    
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
            
            // Debug model input/output info
            interpreterModelA?.let { interpreter ->
                val inputTensor = interpreter.getInputTensor(0)
                val outputTensor = interpreter.getOutputTensor(0)
                Log.d(TAG, "Model A - Input shape: ${inputTensor.shape().contentToString()}")
                Log.d(TAG, "Model A - Input data type: ${inputTensor.dataType()}")
                Log.d(TAG, "Model A - Output shape: ${outputTensor.shape().contentToString()}")
                Log.d(TAG, "Model A - Output data type: ${outputTensor.dataType()}")
            }
            
            interpreterModelD?.let { interpreter ->
                val inputTensor = interpreter.getInputTensor(0)
                val outputTensor = interpreter.getOutputTensor(0)
                Log.d(TAG, "Model D - Input shape: ${inputTensor.shape().contentToString()}")
                Log.d(TAG, "Model D - Input data type: ${inputTensor.dataType()}")
                Log.d(TAG, "Model D - Output shape: ${outputTensor.shape().contentToString()}")
                Log.d(TAG, "Model D - Output data type: ${outputTensor.dataType()}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading models", e)
            Toast.makeText(this, "Error loading AI models: ${e.message}", Toast.LENGTH_LONG).show()
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
        
        Log.d(TAG, "=== IMAGE PROCESSING START ===")
        Log.d(TAG, "Image URI: $imageUri")
        Log.d(TAG, "Image Path: $imagePath")
        
        try {
            val bitmap = when {
                imageUri != null -> {
                    Log.d(TAG, "Loading image from URI...")
                    val uri = Uri.parse(imageUri)
                    val inputStream = contentResolver.openInputStream(uri)
                    BitmapFactory.decodeStream(inputStream)
                }
                imagePath != null -> {
                    Log.d(TAG, "Loading image from file path...")
                    BitmapFactory.decodeFile(imagePath)
                }
                else -> {
                    Toast.makeText(this, "No image data found", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            
            if (bitmap != null) {
                Log.d(TAG, "✅ Bitmap loaded successfully")
                Log.d(TAG, "Original bitmap size: ${bitmap.width}x${bitmap.height}")
                Log.d(TAG, "Bitmap config: ${bitmap.config}")
                Log.d(TAG, "Bitmap has alpha: ${bitmap.hasAlpha()}")
                
                // DEBUG: Analyze original bitmap pixels
                analyzeImagePixels(bitmap, "Original")
                
                // Rotate image if needed based on EXIF data
                val rotatedBitmap = rotateBitmapIfRequired(bitmap, imageUri, imagePath)
                Log.d(TAG, "After rotation: ${rotatedBitmap.width}x${rotatedBitmap.height}")
                
                // Analyze rotated bitmap
                if (rotatedBitmap != bitmap) {
                    analyzeImagePixels(rotatedBitmap, "Rotated")
                }
                
                // Display the image
                ivResultImage.setImageBitmap(rotatedBitmap)
                
                // Preprocess and classify
                val preprocessedBitmap = preprocessImage(rotatedBitmap)
                Log.d(TAG, "Preprocessed bitmap size: ${preprocessedBitmap.width}x${preprocessedBitmap.height}")
                
                // Analyze preprocessed bitmap
                analyzeImagePixels(preprocessedBitmap, "Preprocessed")
                
                classifyImage(preprocessedBitmap)
            } else {
                Log.e(TAG, "❌ Failed to load bitmap")
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
            Toast.makeText(this, "Error processing image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun analyzeImagePixels(bitmap: Bitmap, stage: String) {
        Log.d(TAG, "=== ANALYZING $stage IMAGE ===")
        
        val sampleSize = 100 // Sample 100 pixels
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(sampleSize)
        
        // Sample pixels from different positions
        val samplePositions = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until sampleSize) {
            val x = (i % 10) * (width / 10).coerceAtLeast(1)
            val y = (i / 10) * (height / 10).coerceAtLeast(1)
            samplePositions.add(Pair(x.coerceAtMost(width - 1), y.coerceAtMost(height - 1)))
        }
        
        // Extract pixel values
        val rValues = mutableListOf<Int>()
        val gValues = mutableListOf<Int>()
        val bValues = mutableListOf<Int>()
        
        samplePositions.forEachIndexed { index, (x, y) ->
            val pixel = bitmap.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            
            rValues.add(r)
            gValues.add(g)
            bValues.add(b)
            
            if (index < 5) { // Log first 5 pixels
                Log.d(TAG, "$stage Pixel at ($x,$y): RGB($r,$g,$b)")
            }
        }
        
        // Calculate statistics
        val rMin = rValues.minOrNull() ?: 0
        val rMax = rValues.maxOrNull() ?: 0
        val rAvg = rValues.average()
        
        val gMin = gValues.minOrNull() ?: 0
        val gMax = gValues.maxOrNull() ?: 0
        val gAvg = gValues.average()
        
        val bMin = bValues.minOrNull() ?: 0
        val bMax = bValues.maxOrNull() ?: 0
        val bAvg = bValues.average()
        
        Log.d(TAG, "$stage RED   - Min: $rMin, Max: $rMax, Avg: ${rAvg.toInt()}")
        Log.d(TAG, "$stage GREEN - Min: $gMin, Max: $gMax, Avg: ${gAvg.toInt()}")
        Log.d(TAG, "$stage BLUE  - Min: $bMin, Max: $bMax, Avg: ${bAvg.toInt()}")
        
        // Check if image is mostly white/blank
        val avgBrightness = (rAvg + gAvg + bAvg) / 3
        val variation = maxOf(rMax - rMin, gMax - gMin, bMax - bMin)
        
        Log.d(TAG, "$stage Average brightness: ${avgBrightness.toInt()}/255")
        Log.d(TAG, "$stage Color variation: $variation/255")
        
        // Updated thresholds for raw values (0-255)
        if (avgBrightness > 240) {
            Log.w(TAG, "⚠️ WARNING: $stage image is very bright (mostly white)")
        }
        
        if (variation < 20) {
            Log.w(TAG, "⚠️ WARNING: $stage image has very low color variation")
        }
        
        if (avgBrightness > 240 && variation < 20) {
            Log.e(TAG, "❌ CRITICAL: $stage image appears to be mostly blank/white!")
            Log.e(TAG, "❌ This will cause incorrect classification results!")
            Log.e(TAG, "❌ Now using RAW VALUES 0-255 (fixed from working app)")
            
            // Show warning to user
            if (stage == "Preprocessed") {
                runOnUiThread {
                    Toast.makeText(this@ClassificationActivity, 
                        "⚠️ PERINGATAN: Gambar terlalu putih/kosong!\nHasil klasifikasi mungkin tidak akurat.\nCoba gunakan gambar biji kakao yang lebih jelas.", 
                        Toast.LENGTH_LONG).show()
                }
            }
        }
        
        Log.d(TAG, "=== END $stage ANALYSIS ===")
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
        // Resize to 256x256 - SAMA DENGAN TRAINING
        return Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)
    }
    
    private fun classifyImage(bitmap: Bitmap) {
        try {
            Log.d(TAG, "=== STARTING CLASSIFICATION ===")
            Log.d(TAG, "🔧 FIXED: Using RAW VALUES 0-255 (same as working app)")
            Log.d(TAG, "📱 Based on working TensorFlowHelper.kt approach")
            
            val inputBuffer = convertBitmapToByteBuffer(bitmap)
            
            Log.d(TAG, "Original buffer position: ${inputBuffer.position()}")
            Log.d(TAG, "Original buffer capacity: ${inputBuffer.capacity()}")
            
            // Create separate buffers for each model to avoid position issues
            val bufferA = ByteBuffer.allocateDirect(inputBuffer.capacity())
            val bufferD = ByteBuffer.allocateDirect(inputBuffer.capacity())
            
            // Copy original buffer to both
            inputBuffer.rewind()
            bufferA.put(inputBuffer)
            bufferA.rewind()
            
            inputBuffer.rewind()
            bufferD.put(inputBuffer)
            bufferD.rewind()
            
            Log.d(TAG, "Buffer A position: ${bufferA.position()}, capacity: ${bufferA.capacity()}")
            Log.d(TAG, "Buffer D position: ${bufferD.position()}, capacity: ${bufferD.capacity()}")
            
            // Classify with Model A (skin condition)
            val resultA = classifyWithModelA(bufferA)
            
            // Classify with Model D (color)  
            val resultD = classifyWithModelD(bufferD)
            
            // Display results
            displayResults(resultA, resultD)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during classification", e)
            Toast.makeText(this, "Error during classification: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // BERDASARKAN APLIKASI LAMA: gunakan raw values 0-255 (TIDAK dinormalisasi)
        val byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * pixelSize)
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(imageSize * imageSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        Log.d(TAG, "Converting bitmap to ByteBuffer...")
        Log.d(TAG, "Expected buffer size: ${4 * imageSize * imageSize * pixelSize} bytes")
        Log.d(TAG, "USING RAW VALUES 0-255 (like working app)")
        
        // Debug: Sample some pixels to verify preprocessing
        val samplePixels = mutableListOf<String>()
        
        var pixel = 0
        for (i in 0 until imageSize) {
            for (j in 0 until imageSize) {
                val value = intValues[pixel++]
                
                // FIXED: Use RAW values 0-255 (like working app)
                val r = (value shr 16 and 0xFF).toFloat()
                val g = (value shr 8 and 0xFF).toFloat()
                val b = (value and 0xFF).toFloat()
                
                // Sample first few pixels for debugging
                if (pixel <= 5) {
                    val originalR = (value shr 16 and 0xFF)
                    val originalG = (value shr 8 and 0xFF)
                    val originalB = (value and 0xFF)
                    samplePixels.add("Pixel $pixel: RGB($originalR,$originalG,$originalB) -> raw($r,$g,$b)")
                }
                
                byteBuffer.putFloat(r)
                byteBuffer.putFloat(g)
                byteBuffer.putFloat(b)
                
                // Validation for raw values 0-255
                if (r < 0f || r > 255f || g < 0f || g > 255f || b < 0f || b > 255f) {
                    Log.e(TAG, "ERROR: Pixel values out of range [0,255]: R=$r, G=$g, B=$b")
                }
            }
        }
        
        // Log sample pixels
        samplePixels.forEach { Log.d(TAG, it) }
        
        Log.d(TAG, "ByteBuffer created with size: ${byteBuffer.capacity()}")
        Log.d(TAG, "ByteBuffer position after fill: ${byteBuffer.position()}")
        
        // Reset position for model input
        byteBuffer.rewind()
        Log.d(TAG, "ByteBuffer position after rewind: ${byteBuffer.position()}")
        
        return byteBuffer
    }
    
    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val expValues = logits.map { exp(it - maxLogit) }.toFloatArray()
        val sumExp = expValues.sum()
        return expValues.map { it / sumExp }.toFloatArray()
    }
    
    private fun classifyWithModelA(inputBuffer: ByteBuffer): Pair<String, Float> {
        val result = Array(1) { FloatArray(labelsModelA.size) }
        
        Log.d(TAG, "=== MODEL A INFERENCE ===")
        Log.d(TAG, "Input buffer capacity: ${inputBuffer.capacity()}")
        Log.d(TAG, "Input buffer position before inference: ${inputBuffer.position()}")
        Log.d(TAG, "Expected input size: ${1 * imageSize * imageSize * pixelSize * 4} bytes")
        
        try {
            interpreterModelA?.run(inputBuffer, result)
            Log.d(TAG, "Model A inference completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during Model A inference: ${e.message}", e)
            return Pair("error", 0f)
        }
        
        val output = result[0]
        Log.d(TAG, "Model A raw output: ${output.contentToString()}")
        Log.d(TAG, "Model A output size: ${output.size}")
        
        // Check if output values make sense
        val outputSum = output.sum()
        Log.d(TAG, "Model A output sum: $outputSum")
        
        // Apply softmax if needed (though TensorFlow Lite model should already have softmax)
        val probabilities = if (outputSum > 1.1f || outputSum < 0.9f || output.any { it < 0 }) {
            Log.d(TAG, "Applying softmax to Model A output (sum=$outputSum)")
            softmax(output)
        } else {
            Log.d(TAG, "Using raw probabilities from Model A (sum=$outputSum)")
            output
        }
        
        Log.d(TAG, "Model A probabilities: ${probabilities.contentToString()}")
        Log.d(TAG, "Model A labels: ${labelsModelA.contentToString()}")
        
        // DEBUG: Test different label orders
        for (i in probabilities.indices) {
            Log.d(TAG, "DEBUG - Index $i: ${labelsModelA[i]} = ${probabilities[i]} (${(probabilities[i] * 100).toInt()}%)")
        }
        
        var maxIndex = 0
        var maxConfidence = probabilities[0]
        
        for (i in probabilities.indices) {
            if (probabilities[i] > maxConfidence) {
                maxConfidence = probabilities[i]
                maxIndex = i
            }
        }
        
        Log.d(TAG, "Model A final result: ${labelsModelA[maxIndex]} with confidence $maxConfidence")
        Log.d(TAG, "=== END MODEL A ===")
        return Pair(labelsModelA[maxIndex], maxConfidence)
    }
    
    private fun classifyWithModelD(inputBuffer: ByteBuffer): Pair<String, Float> {
        val result = Array(1) { FloatArray(labelsModelD.size) }
        
        Log.d(TAG, "=== MODEL D INFERENCE ===")
        Log.d(TAG, "Input buffer capacity: ${inputBuffer.capacity()}")
        Log.d(TAG, "Input buffer position before inference: ${inputBuffer.position()}")
        
        try {
            interpreterModelD?.run(inputBuffer, result)
            Log.d(TAG, "Model D inference completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during Model D inference: ${e.message}", e)
            return Pair("error", 0f)
        }
        
        val output = result[0]
        Log.d(TAG, "Model D raw output: ${output.contentToString()}")
        Log.d(TAG, "Model D output size: ${output.size}")
        
        // Check if output values make sense
        val outputSum = output.sum()
        Log.d(TAG, "Model D output sum: $outputSum")
        
        // Apply softmax if needed
        val probabilities = if (outputSum > 1.1f || outputSum < 0.9f || output.any { it < 0 }) {
            Log.d(TAG, "Applying softmax to Model D output (sum=$outputSum)")
            softmax(output)
        } else {
            Log.d(TAG, "Using raw probabilities from Model D (sum=$outputSum)")
            output
        }
        
        Log.d(TAG, "Model D probabilities: ${probabilities.contentToString()}")
        Log.d(TAG, "Model D labels: ${labelsModelD.contentToString()}")
        
        // DEBUG: Test all possible label mappings
        for (i in probabilities.indices) {
            Log.d(TAG, "DEBUG - Index $i: ${labelsModelD[i]} = ${probabilities[i]} (${(probabilities[i] * 100).toInt()}%)")
        }
        
        var maxIndex = 0
        var maxConfidence = probabilities[0]
        
        for (i in probabilities.indices) {
            if (probabilities[i] > maxConfidence) {
                maxConfidence = probabilities[i]
                maxIndex = i
            }
        }
        
        Log.d(TAG, "Model D final result: ${labelsModelD[maxIndex]} with confidence $maxConfidence")
        Log.d(TAG, "=== END MODEL D ===")
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
        
        Log.d(TAG, "Final Classification Results:")
        Log.d(TAG, "Model A: ${resultA.first} (${confidenceA}%)")
        Log.d(TAG, "Model D: ${resultD.first} (${(resultD.second * 100).toInt()}%)")
        Log.d(TAG, "Roasting Status: $roastingStatusText")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        interpreterModelA?.close()
        interpreterModelD?.close()
    }
} 