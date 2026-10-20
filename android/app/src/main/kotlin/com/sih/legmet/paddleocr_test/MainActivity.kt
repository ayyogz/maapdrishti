package com.sih.legmet.paddleocr_test

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.model.OCRRunResult
import java.util.ArrayList

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.sih.legmet/paddleocr"
    private var paddleOCR: PaddleOCR? = null
    private val initJob = Job()
    private val initScope = CoroutineScope(Dispatchers.IO + initJob)

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d("PaddleOCR", "Configuring Flutter engine, setting up MethodChannel")

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            Log.d("PaddleOCR", "MethodChannel call received: ${call.method}")
            when (call.method) {
                "initialize" -> {
                    Log.d("PaddleOCR", "Initialize method called")
                    initializeOCR(result)
                }
                "recognize" -> {
                    Log.d("PaddleOCR", "Recognize method called")
                    recognizeText(call, result)
                }
                "detectAruco" -> {
                    Log.d("PaddleOCR", "Detect ArUco method called")
                    detectAruco(call, result)
                }
                "checkCompliance" -> {
                    Log.d("PaddleOCR", "Check compliance method called")
                    checkCompliance(call, result)
                }
                "release" -> {
                    Log.d("PaddleOCR", "Release method called")
                    releaseOCR(result)
                }
                else -> {
                    Log.w("PaddleOCR", "Method not implemented: ${call.method}")
                    result.notImplemented()
                }
            }
        }
    }

    private fun initializeOCR(result: Result) {
        Log.d("PaddleOCR", "Starting OCR initialization...")
        initScope.launch {
            try {
                val context = applicationContext
                Log.d("PaddleOCR", "Creating PaddleOCR instance...")
                paddleOCR = PaddleOCR.create(
                    context = context,
                    config = PaddleOCRConfig(),
                    engineConfig = EngineConfig(numThreads = 4),
                    detModelAssetPath = "models/det/inference.onnx",
                    recModelAssetPath = "models/rec/inference.onnx",
                    recConfigAssetPath = "models/rec/inference.yml"
                )
                Log.d("PaddleOCR", "PaddleOCR created successfully, coldLoadTimeMs: ${paddleOCR?.coldLoadTimeMs ?: 0}")
                val responseMap = mutableMapOf<String, Any>()
                responseMap["success"] = true
                responseMap["coldLoadTimeMs"] = paddleOCR?.coldLoadTimeMs ?: 0
                result.success(responseMap)
                Log.d("PaddleOCR", "Initialization result sent to Flutter")
            } catch (e: Exception) {
                Log.e("PaddleOCR", "Initialization failed", e)
                result.error("INIT_FAILED", e.message, e.stackTraceToString())
            }
        }
    }

    private fun recognizeText(call: MethodCall, result: Result) {
        if (paddleOCR == null) {
            result.error("NOT_INITIALIZED", "PaddleOCR not initialized", null)
            return
        }

        val imagePath = call.argument<String>("imagePath")
        if (imagePath == null || imagePath.isEmpty()) {
            result.error("INVALID_ARGUMENT", "imagePath is required", null)
            return
        }

        initScope.launch {
            try {
                // Decode with downsampling to prevent OOM on large images
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(imagePath, options)
                val width = options.outWidth
                val height = options.outHeight
                Log.d("PaddleOCR", "Original image size: ${width}x${height}")

                // Scale down if image is larger than 2000px on any side
                var inSampleSize = 1
                while (width / inSampleSize > 2000 || height / inSampleSize > 2000) {
                    inSampleSize *= 2
                }

                options.inJustDecodeBounds = false
                options.inSampleSize = inSampleSize
                val bitmap = BitmapFactory.decodeFile(imagePath, options)
                if (bitmap == null) {
                    result.error("INVALID_IMAGE", "Failed to decode image", null)
                    return@launch
                }
                Log.d("PaddleOCR", "Decoded image size: ${bitmap.width}x${bitmap.height} (inSampleSize=$inSampleSize)")

                val ocrResult: OCRRunResult = paddleOCR!!.recognize(bitmap)

                val results = ocrResult.results.map { item ->
                    val boxPoints = item.box.points.map { p ->
                        mapOf<String, Double>("x" to p.x.toDouble(), "y" to p.y.toDouble())
                    }
                    mapOf<String, Any>(
                        "text" to item.text,
                        "confidence" to item.confidence,
                        "box" to boxPoints
                    )
                }

                val responseMap = mutableMapOf<String, Any>()
                responseMap["results"] = results
                responseMap["detectionTimeMs"] = ocrResult.detectionTimeMs
                responseMap["recognitionTimeMs"] = ocrResult.recognitionTimeMs
                responseMap["totalTimeMs"] = ocrResult.totalTimeMs
                responseMap["lineCount"] = ocrResult.lineCount
                result.success(responseMap)
            } catch (e: Exception) {
                Log.e("PaddleOCR", "Recognition failed", e)
                result.error("RECOGNITION_FAILED", e.message, e.stackTraceToString())
            }
        }
    }

    private fun releaseOCR(result: Result) {
        initScope.launch {
            paddleOCR?.release()
            paddleOCR = null
            result.success(true)
        }
    }

    private fun checkCompliance(call: MethodCall, result: Result) {
        if (paddleOCR == null) {
            result.error("NOT_INITIALIZED", "PaddleOCR not initialized", null)
            return
        }

        val imagePath = call.argument<String>("imagePath")
        if (imagePath == null || imagePath.isEmpty()) {
            result.error("INVALID_ARGUMENT", "imagePath is required", null)
            return
        }

        val pixelToMmRatio = call.argument<Double>("pixelToMmRatio")
        val markerLengthMm = call.argument<Double>("markerLengthMm")?.toFloat() ?: 20.0f

        // pixelToMmRatio can be null — means no ArUco calibration, skip font size checks only
        val hasCalibration = pixelToMmRatio != null && pixelToMmRatio > 0

        initScope.launch {
            try {
                // Decode with downsampling to prevent OOM
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(imagePath, options)
                val width = options.outWidth
                val height = options.outHeight

                var inSampleSize = 1
                while (width / inSampleSize > 2000 || height / inSampleSize > 2000) {
                    inSampleSize *= 2
                }

                options.inJustDecodeBounds = false
                options.inSampleSize = inSampleSize
                val bitmap = BitmapFactory.decodeFile(imagePath, options)
                if (bitmap == null) {
                    result.error("INVALID_IMAGE", "Failed to decode image", null)
                    return@launch
                }

                // Run OCR first
                val ocrResult: OCRRunResult = paddleOCR!!.recognize(bitmap)

                // Run ArUco detection (secondary verification if we have a ratio)
                val arucoDetector = ArUcoDetector(markerLengthMm)
                val calibrationResult = arucoDetector.detectMarkers(bitmap)

                // Use calibration result if it succeeded, otherwise use the provided ratio
                // (provided ratio should come from a successful detectAruco call)
                val effectiveRatio = if (calibrationResult.success) {
                    calibrationResult.pixelToMmRatio
                } else if (hasCalibration) {
                    pixelToMmRatio!!
                } else {
                    0.0 // No calibration — font sizes will be skipped in engine
                }

                // Convert OCR results to ComplianceEngine format
                val ocrResults = ocrResult.results.map { item ->
                    val points = ArrayList<Point>()
                    for (p in item.box.points) {
                        points.add(Point(p.x.toFloat(), p.y.toFloat()))
                    }
                    OCRResult(
                        text = item.text,
                        confidence = item.confidence,
                        box = points
                    )
                }

                // Run compliance engine
                Log.d("PaddleOCR", "Running compliance engine with ratio: $effectiveRatio")
                val engine = ComplianceEngine(effectiveRatio)
                val report = engine.analyze(ocrResults, bitmap.width, bitmap.height)
                Log.d("PaddleOCR", "Compliance analysis complete: overall=${report.overallCompliant}, percentage=${report.compliancePercentage}")

                // Build response
                val responseMap = mutableMapOf<String, Any>()
                responseMap["overallCompliant"] = report.overallCompliant
                responseMap["compliancePercentage"] = report.compliancePercentage
                responseMap["totalChecks"] = report.totalChecks
                responseMap["passedChecks"] = report.passedChecks
                responseMap["pixelToMmRatio"] = effectiveRatio
                responseMap["minFontHeightPx"] = report.minFontHeightPx

                // Extracted fields
                val fieldsMap = mutableMapOf<String, Any>()
                with(report.extractedFields) {
                    fieldsMap["productName"] = productName ?: "NOT FOUND"
                    fieldsMap["netQuantity"] = netQuantity ?: "NOT FOUND"
                    fieldsMap["mrp"] = mrp ?: "NOT FOUND"
                    fieldsMap["mfgDate"] = mfgDate ?: "NOT FOUND"
                    fieldsMap["bestBefore"] = bestBefore ?: "NOT FOUND"
                    fieldsMap["manufacturer"] = manufacturer ?: "NOT FOUND"
                    fieldsMap["address"] = address ?: "NOT FOUND"
                    fieldsMap["phone"] = phone ?: "NOT FOUND"
                    fieldsMap["email"] = email ?: "NOT FOUND"
                    fieldsMap["customerCare"] = customerCare ?: "NOT FOUND"
                    fieldsMap["countryOfOrigin"] = countryOfOrigin ?: "NOT FOUND"
                    fieldsMap["batchNumber"] = batchNumber ?: "NOT FOUND"
                }
                responseMap["extractedFields"] = fieldsMap

                // Mandatory checks
                val mandatoryList = mutableListOf<Map<String, Any>>()
                for (check in report.mandatoryChecks) {
                    val checkMap = mutableMapOf<String, Any>()
                    checkMap["rule"] = check.rule
                    checkMap["description"] = check.description
                    checkMap["passed"] = check.passed
                    checkMap["foundValue"] = check.foundValue?.toString() ?: "NOT FOUND"
                    checkMap["severity"] = check.severity.name
                    mandatoryList.add(checkMap)
                }
                responseMap["mandatoryChecks"] = mandatoryList

                // Font size checks
                val fontSizeList = mutableListOf<Map<String, Any>>()
                for (check in report.fontSizeChecks) {
                    val checkMap = mutableMapOf<String, Any>()
                    checkMap["rule"] = check.rule
                    checkMap["description"] = check.description
                    checkMap["passed"] = check.passed
                    checkMap["foundValue"] = check.foundValue?.toString() ?: "N/A"
                    checkMap["severity"] = check.severity.name
                    checkMap["text"] = check.text ?: ""
                    fontSizeList.add(checkMap)
                }
                responseMap["fontSizeChecks"] = fontSizeList

                // Critical failures summary
                val criticalFailures = mutableListOf<Map<String, Any>>()
                for (check in report.criticalFailures) {
                    val checkMap = mutableMapOf<String, Any>()
                    checkMap["rule"] = check.rule
                    checkMap["description"] = check.description
                    checkMap["foundValue"] = check.foundValue?.toString() ?: "NOT FOUND"
                    criticalFailures.add(checkMap)
                }
                responseMap["criticalFailures"] = criticalFailures

                Log.d("PaddleOCR", "Sending compliance result to Flutter: overallCompliant=${report.overallCompliant}")
                result.success(responseMap)
            } catch (e: Exception) {
                Log.e("PaddleOCR", "Compliance check failed", e)
                result.error("COMPLIANCE_FAILED", e.message, e.stackTraceToString())
            }
        }
    }

    private fun detectAruco(call: MethodCall, result: Result) {
        val imagePath = call.argument<String>("imagePath")
        if (imagePath == null || imagePath.isEmpty()) {
            result.error("INVALID_ARGUMENT", "imagePath is required", null)
            return
        }

        val markerLengthMm = call.argument<Double>("markerLengthMm")?.toFloat() ?: 20.0f

        initScope.launch {
            try {
                // Decode with downsampling to prevent OOM on large images
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(imagePath, options)
                val width = options.outWidth
                val height = options.outHeight
                Log.d("PaddleOCR", "ArUco: Original image size: ${width}x${height}")

                // Scale down if image is larger than 2000px on any side
                var inSampleSize = 1
                while (width / inSampleSize > 2000 || height / inSampleSize > 2000) {
                    inSampleSize *= 2
                }

                options.inJustDecodeBounds = false
                options.inSampleSize = inSampleSize
                val bitmap = BitmapFactory.decodeFile(imagePath, options)
                if (bitmap == null) {
                    result.error("INVALID_IMAGE", "Failed to decode image", null)
                    return@launch
                }
                Log.d("PaddleOCR", "ArUco: Decoded image size: ${bitmap.width}x${bitmap.height} (inSampleSize=$inSampleSize)")

                // Create ArUco detector with specified marker length
                val arucoDetector = ArUcoDetector(markerLengthMm)

                val calibrationResult = arucoDetector.detectMarkers(bitmap)

                val responseMap = mutableMapOf<String, Any>()
                responseMap["success"] = calibrationResult.success
                responseMap["pixelToMmRatio"] = calibrationResult.pixelToMmRatio
                responseMap["message"] = calibrationResult.message

                val markersList = mutableListOf<Map<String, Any>>()
                for (marker in calibrationResult.detectedMarkers) {
                    val cornersList = mutableListOf<Map<String, Double>>()
                    for (pt in marker.corners) {
                        cornersList.add(mapOf("x" to pt.x.toDouble(), "y" to pt.y.toDouble()))
                    }
                    markersList.add(mapOf(
                        "id" to marker.id,
                        "corners" to cornersList,
                        "centerX" to marker.centerX.toDouble(),
                        "centerY" to marker.centerY.toDouble(),
                        "pixelToMmRatio" to marker.pixelToMmRatio.toDouble()
                    ))
                }
                responseMap["detectedMarkers"] = markersList

                result.success(responseMap)
            } catch (e: Exception) {
                Log.e("PaddleOCR", "ArUco detection failed", e)
                result.error("ARUCO_FAILED", e.message, e.stackTraceToString())
            }
        }
    }

    override fun onDestroy() {
        initScope.launch {
            paddleOCR?.release()
        }
        initJob.cancel()
        super.onDestroy()
    }
}