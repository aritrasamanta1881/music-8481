package com.example.ui.model

data class NowPlayingState(
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String = "",
    val isPlaying: Boolean = false,
    val currentTimeSeconds: Float = 0f,
    val durationSeconds: Float = 0f,
    val isVisible: Boolean = false
) {
    val hasContent: Boolean
        get() = title.isNotBlank() || isPlaying || currentTimeSeconds > 0f
}
