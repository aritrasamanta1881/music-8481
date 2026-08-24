package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.util.NetworkObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val networkObserver = NetworkObserver(application)

    private val _isOnline = MutableStateFlow(networkObserver.checkCurrentConnectedState())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _pageProgress = MutableStateFlow(0)
    val pageProgress: StateFlow<Int> = _pageProgress.asStateFlow()

    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _hasError = MutableStateFlow(false)
    val hasError: StateFlow<Boolean> = _hasError.asStateFlow()

    private val _isAtTop = MutableStateFlow(true)
    val isAtTop: StateFlow<Boolean> = _isAtTop.asStateFlow()

    val targetUrl: String = try {
        val configuredUrl = BuildConfig.APP_URL
        if (configuredUrl.isNotBlank() && configuredUrl != "APP_URL") {
            configuredUrl
        } else {
            "https://Ayushgaana.vercel.app"
        }
    } catch (e: Throwable) {
        "https://Ayushgaana.vercel.app"
    }

    init {
        viewModelScope.launch {
            networkObserver.isOnline.collect { online ->
                _isOnline.value = online
                if (online && _hasError.value) {
                    _hasError.value = false
                }
            }
        }
    }

    fun onProgressChanged(progress: Int) {
        _pageProgress.value = progress
        if (progress >= 90 && _isInitialLoading.value) {
            _isInitialLoading.value = false
        }
        if (progress >= 100) {
            _isRefreshing.value = false
        }
    }

    fun onPageStarted() {
        _hasError.value = false
    }

    fun onPageFinished() {
        _pageProgress.value = 100
        _isInitialLoading.value = false
        _isRefreshing.value = false
    }

    fun onPageError() {
        if (!_isOnline.value) {
            _hasError.value = true
        }
        _isInitialLoading.value = false
        _isRefreshing.value = false
    }

    fun startRefresh() {
        _isRefreshing.value = true
        _hasError.value = false
    }

    fun setScrollAtTop(atTop: Boolean) {
        _isAtTop.value = atTop
    }

    fun dismissError() {
        _hasError.value = false
    }
}
