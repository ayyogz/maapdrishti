package com.sih.legmet.paddleocr_test

import android.app.Application
import android.util.Log

class PaddleOCRApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load OpenCV native library early, before any other code uses it
        try {
            System.loadLibrary("opencv_java4")
            Log.d("PaddleOCR", "OpenCV library loaded successfully in Application.onCreate")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("PaddleOCR", "Failed to load OpenCV library in Application.onCreate", e)
        }
    }
}