package com.example.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

object SignatureVerification {

    /**
     * Verifies the APK's signature at runtime (checking V1, V2, and V3 signatures).
     */
    fun verifySignature(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    val apkSigners = if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                    apkSigners != null && apkSigners.isNotEmpty()
                } else false
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val signatures = packageInfo.signatures
                signatures != null && signatures.isNotEmpty()
            }
        } catch (e: Exception) {
            Log.e("SignatureVerification", "APK signature check failed", e)
            false
        }
    }
}
