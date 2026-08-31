package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.ui.model.NowPlayingState
import com.example.util.NetworkObserver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _nowPlayingState = MutableStateFlow(NowPlayingState())
    val nowPlayingState: StateFlow<NowPlayingState> = _nowPlayingState.asStateFlow()

    private val _mediaActionEvents = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val mediaActionEvents: SharedFlow<String> = _mediaActionEvents.asSharedFlow()

    private var userDismissed = false

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

    fun updateNowPlaying(
        title: String,
        artist: String,
        artworkUrl: String,
        isPlaying: Boolean,
        currentTime: Float,
        duration: Float
    ) {
        // If song changed (different title), reset userDismissed flag so banner appears for new song
        val currentTitle = _nowPlayingState.value.title
        if (title.isNotBlank() && title != currentTitle) {
            userDismissed = false
        }

        val shouldBeVisible = !userDismissed && (title.isNotBlank() || isPlaying || currentTime > 0f)

        _nowPlayingState.update { current ->
            current.copy(
                title = title,
                artist = artist,
                artworkUrl = artworkUrl,
                isPlaying = isPlaying,
                currentTimeSeconds = currentTime,
                durationSeconds = duration,
                isVisible = shouldBeVisible
            )
        }
    }

    fun dismissBanner() {
        userDismissed = true
        _nowPlayingState.update { it.copy(isVisible = false) }
    }

    fun togglePlayPause() {
        viewModelScope.launch {
            if (_nowPlayingState.value.isPlaying) {
                _mediaActionEvents.emit("pause")
            } else {
                _mediaActionEvents.emit("play")
            }
        }
    }

    fun skipNext() {
        viewModelScope.launch {
            _mediaActionEvents.emit("next")
        }
    }

    fun skipPrevious() {
        viewModelScope.launch {
            _mediaActionEvents.emit("previous")
        }
    }

    fun seekTo(seconds: Float) {
        viewModelScope.launch {
            _mediaActionEvents.emit("seek:$seconds")
        }
    }
}
