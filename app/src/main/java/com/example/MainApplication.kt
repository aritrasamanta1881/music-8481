package com.example

import android.app.Application
import android.os.Build
import android.util.Log

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val jsCacheDir = java.io.File(cacheDir, "WebView/Default/HTTP Cache/Code Cache/js")
            if (!jsCacheDir.exists()) {
                jsCacheDir.mkdirs()
            }
        } catch (e: Throwable) {
            Log.w("MainApplication", "Error initializing WebView cache dir", e)
        }

        try {
            com.example.util.SignatureVerification.verifySignature(this)
        } catch (e: Throwable) {
            Log.w("MainApplication", "Signature verification warning", e)
        }
    }

    override fun getAttributionTag(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "default"
        } else {
            super.getAttributionTag()
        }
    }
}
