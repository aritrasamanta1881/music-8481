package com.example

import android.app.Application
import android.os.Build

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Pre-create WebView HTTP Code Cache directory to prevent Chromium opendir log error on cold start
        try {
            val jsCacheDir = java.io.File(cacheDir, "WebView/Default/HTTP Cache/Code Cache/js")
            if (!jsCacheDir.exists()) {
                jsCacheDir.mkdirs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Perform runtime APK signature verification check (V1/V2/V3)
        com.example.util.SignatureVerification.verifySignature(this)
    }

    override fun getAttributionTag(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "default"
        } else {
            super.getAttributionTag()
        }
    }
}
