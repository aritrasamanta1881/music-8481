package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainViewModel
import com.example.ui.components.CustomLoadingScreen
import com.example.ui.components.OfflineBanner
import com.example.ui.components.OfflineErrorView
import com.example.ui.components.WebViewScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun getAttributionTag(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "default"
        } else {
            super.getAttributionTag()
        }
    }

    private var wasOfflineBefore = false

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null) return
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isConnected = isNetworkAvailable(connectivityManager)

            if (!isConnected) {
                if (!wasOfflineBefore) {
                    Toast.makeText(context, "You are offline. Please check your internet connection.", Toast.LENGTH_LONG).show()
                    wasOfflineBefore = true
                }
            } else {
                if (wasOfflineBefore) {
                    Toast.makeText(context, "Internet connection restored.", Toast.LENGTH_SHORT).show()
                    wasOfflineBefore = false
                }
            }
        }
    }

    private fun isNetworkAvailable(connectivityManager: ConnectivityManager?): Boolean {
        if (connectivityManager == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo != null && networkInfo.isConnected
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MusicAppContent()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        @Suppress("DEPRECATION")
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectivityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(connectivityReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(connectivityReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }
}

@Composable
fun MusicAppContent(
    viewModel: MainViewModel = viewModel()
) {
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val pageProgress by viewModel.pageProgress.collectAsStateWithLifecycle()
    val isInitialLoading by viewModel.isInitialLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val hasError by viewModel.hasError.collectAsStateWithLifecycle()
    val isAtTop by viewModel.isAtTop.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DarkBackground)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Offline notification banner
                OfflineBanner(
                    isOffline = !isOnline,
                    onRetry = { viewModel.startRefresh() }
                )

                // Main full-screen WebView Screen
                WebViewScreen(
                    url = viewModel.targetUrl,
                    isOnline = isOnline,
                    isRefreshing = isRefreshing,
                    isAtTop = isAtTop,
                    onRefresh = { viewModel.startRefresh() },
                    onProgressChanged = { progress -> viewModel.onProgressChanged(progress) },
                    onPageStarted = { viewModel.onPageStarted() },
                    onPageFinished = { viewModel.onPageFinished() },
                    onPageError = { viewModel.onPageError() },
                    onScrollAtTop = { atTop -> viewModel.setScrollAtTop(atTop) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Offline error screen if main page failed to load with no internet & no cache
            if (hasError && !isOnline) {
                OfflineErrorView(
                    onRetry = {
                        viewModel.dismissError()
                        viewModel.startRefresh()
                    }
                )
            }

            // Custom initial loading screen overlay
            CustomLoadingScreen(
                isVisible = isInitialLoading,
                progress = pageProgress
            )
        }
    }
}
