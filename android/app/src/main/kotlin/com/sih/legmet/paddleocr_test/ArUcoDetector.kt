package com.sih.legmet.paddleocr_test

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.Dictionary
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import java.util.ArrayList
import java.util.Collections
import java.util.List

/**
 * ArUco marker detector for pixel-to-millimeter calibration.
 * Uses DICT_4X4_50 dictionary which is good for close-range detection.
 */
class ArUcoDetector(private val markerLengthMm: Float = 20.0f) {
    private val TAG = "ArUcoDetector"
    private val dictionary: Dictionary
    private val detector: ArucoDetector

    init {
        // Use 4x4 dictionary with 50 markers - good balance of detection rate and uniqueness
        dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
        val params = DetectorParameters()
        detector = ArucoDetector(dictionary, params)
        Log.d(TAG, "ArUcoDetector initialized with DICT_4X4_50, markerLengthMm=$markerLengthMm")
    }

    /**
     * Detect ArUco markers in a bitmap and return calibration data.
     * @param bitmap Input image bitmap
     * @return ArUcoCalibrationResult with pixel-to-mm ratio and marker corners
     */
    fun detectMarkers(bitmap: Bitmap): ArUcoCalibrationResult {
        val mat = BitmapUtils.bitmapToMat(bitmap)
        val grayMat = Mat()

        try {
            // Convert to grayscale if needed
            if (mat.channels() == 3) {
                Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
            } else if (mat.channels() == 4) {
                Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            } else {
                mat.copyTo(grayMat)
            }

            val corners: MutableList<Mat> = ArrayList()
            val ids = MatOfInt()
            val rejectedCandidates: MutableList<Mat> = ArrayList()

            // Detect markers
            detector.detectMarkers(grayMat, corners, ids, rejectedCandidates)

            // Check if ids is empty (no markers detected) before calling toList()
            // In OpenCV 4.14.0, empty MatOfInt.toList() crashes with "Native Mat has unexpected type or size"
            val idArray = if (ids.empty()) {
                emptyList<Int>()
            } else {
                ids.toList()
            }
            Log.d(TAG, "Detected ${idArray.size} ArUco markers")

            if (idArray.isEmpty()) {
                return ArUcoCalibrationResult(
                    success = false,
                    pixelToMmRatio = 0.0,
                    detectedMarkers = java.util.Collections.emptyList<DetectedMarker>() as java.util.List<DetectedMarker>,
                    message = "No ArUco markers detected"
                )
            }

            // Refine corner positions for sub-pixel accuracy
            val criteria = TermCriteria(
                TermCriteria.EPS + TermCriteria.MAX_ITER,
                30, 0.001
            )

            for (i in corners.indices) {
                val cornerMat = corners[i]
                val cornerMat2f = MatOfPoint2f()
                cornerMat.convertTo(cornerMat2f, CvType.CV_32FC2)
                Imgproc.cornerSubPix(grayMat, cornerMat2f, org.opencv.core.Size(5.0, 5.0),
                    org.opencv.core.Size(-1.0, -1.0), criteria)
                corners[i] = cornerMat2f
            }

            // Calculate pixel-to-mm ratio from detected markers
            var totalRatio = 0.0
            var validMarkers = 0
            val detectedMarkers: java.util.ArrayList<DetectedMarker> = java.util.ArrayList()

            for (i in idArray.indices) {
                val markerId = idArray[i].toInt()
                val cornerMat = corners[i]

                // Convert Mat to MatOfPoint2f then get array
                val cornerMat2f = cornerMat as MatOfPoint2f
                val pointArray: Array<Point> = cornerMat2f.toArray()
                val markerCorners = ArrayList<Point>()
                for (j in pointArray.indices) {
                    markerCorners.add(pointArray[j])
                }

                if (markerCorners.size == 4) {
                    // Calculate marker perimeter in pixels
                    var perimeterPx = 0.0
                    for (j in 0..3) {
                        val p1 = markerCorners[j]
                        val p2 = markerCorners[(j + 1) % 4]
                        val dx = p2.x - p1.x
                        val dy = p2.y - p1.y
                        perimeterPx += Math.sqrt(dx * dx + dy * dy)
                    }

                    // Physical perimeter = 4 * markerLengthMm
                    val physicalPerimeterMm = 4.0 * markerLengthMm
                    val ratio = physicalPerimeterMm / perimeterPx

                    totalRatio += ratio
                    validMarkers++

                    // Compute center point
                    var centerX = 0.0
                    var centerY = 0.0
                    for (pt in markerCorners) {
                        centerX += pt.x
                        centerY += pt.y
                    }
                    centerX /= 4.0
                    centerY /= 4.0

                    val cornerPointsF = java.util.ArrayList<PointF>()
                    for (pt in markerCorners) {
                        cornerPointsF.add(PointF(pt.x.toFloat(), pt.y.toFloat()))
                    }

                    detectedMarkers.add(DetectedMarker(
                        id = markerId,
                        corners = cornerPointsF as java.util.List<PointF>,
                        centerX = centerX.toFloat(),
                        centerY = centerY.toFloat(),
                        pixelToMmRatio = ratio.toFloat()
                    ))
                }
            }

            if (validMarkers == 0) {
                return ArUcoCalibrationResult(
                    success = false,
                    pixelToMmRatio = 0.0,
                    detectedMarkers = java.util.Collections.emptyList<DetectedMarker>() as java.util.List<DetectedMarker>,
                    message = "No valid markers with 4 corners"
                )
            }

            val avgRatio = totalRatio / validMarkers
            Log.d(TAG, "Calibration: avg pixel/mm ratio = $avgRatio from $validMarkers markers")

            return ArUcoCalibrationResult(
                success = true,
                pixelToMmRatio = avgRatio,
                detectedMarkers = java.util.ArrayList(detectedMarkers) as java.util.List<DetectedMarker>,
                message = "Successfully calibrated with $validMarkers marker(s)"
            )

        } catch (e: Exception) {
            Log.e(TAG, "ArUco detection failed", e)
            return ArUcoCalibrationResult(
                success = false,
                pixelToMmRatio = 0.0,
                detectedMarkers = java.util.Collections.emptyList<DetectedMarker>() as java.util.List<DetectedMarker>,
                message = "Detection error: ${e.message}"
            )
        } finally {
            mat.release()
            grayMat.release()
        }
    }

    /**
     * Draw detected markers on bitmap for visualization
     */
    fun drawDetectedMarkers(bitmap: Bitmap, result: ArUcoCalibrationResult): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val mat = BitmapUtils.bitmapToMat(output)

        try {
            val cornersList: java.util.ArrayList<Mat> = java.util.ArrayList()
            val idsList = java.util.ArrayList<Int>()

            for (marker in result.detectedMarkers) {
                val points = java.util.ArrayList<Point>()
                for (pt in marker.corners) {
                    points.add(Point(pt.x.toDouble(), pt.y.toDouble()))
                }
                cornersList.add(MatOfPoint2f(*points.toTypedArray()))
                idsList.add(marker.id)
            }

            if (!cornersList.isEmpty()) {
                val idsMat = MatOfInt(*idsList.toIntArray())
                // Use Objdetect.drawDetectedMarkers static method
                Objdetect.drawDetectedMarkers(mat, cornersList, idsMat, Scalar(0.0, 255.0, 0.0, 255.0))
            }

            BitmapUtils.matToBitmap(mat, output)
        } finally {
            mat.release()
        }

        return output
    }
}

/**
 * Result of a single detected ArUco marker
 */
data class DetectedMarker(
    val id: Int,
    val corners: java.util.List<PointF>, // 4 corners in image coordinates
    val centerX: Float,
    val centerY: Float,
    val pixelToMmRatio: Float
)

/**
 * Calibration result containing pixel-to-mm ratio and detected markers
 */
data class ArUcoCalibrationResult(
    val success: Boolean,
    val pixelToMmRatio: Double, // mm per pixel
    val detectedMarkers: java.util.List<DetectedMarker>,
    val message: String
)

/**
 * Utility for Bitmap <-> Mat conversion
 */
object BitmapUtils {
    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat(bitmap.height, bitmap.width, CvType.CV_8UC4)
        org.opencv.android.Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    fun matToBitmap(mat: Mat, bitmap: Bitmap) {
        org.opencv.android.Utils.matToBitmap(mat, bitmap)
    }
}