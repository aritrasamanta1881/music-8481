package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object MediaControllerHelper {
    var onActionReceived: ((String) -> Unit)? = null

    var currentTitle: String = ""
    var currentArtist: String = ""
    var currentArtworkUrl: String = ""
    var currentIsPlaying: Boolean = false

    fun updateState(
        context: Context,
        title: String,
        artist: String,
        artworkUrl: String,
        isPlaying: Boolean
    ) {
        currentTitle = title
        currentArtist = artist
        currentArtworkUrl = artworkUrl
        currentIsPlaying = isPlaying

        val intent = Intent(context, MediaNotificationService::class.java).apply {
            putExtra(MediaNotificationService.EXTRA_TITLE, title)
            putExtra(MediaNotificationService.EXTRA_ARTIST, artist)
            putExtra(MediaNotificationService.EXTRA_ARTWORK, artworkUrl)
            putExtra(MediaNotificationService.EXTRA_IS_PLAYING, isPlaying)
        }

        if (isPlaying) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            // Send update to existing service (e.g. paused state)
            context.startService(intent)
        }
    }

    fun stopService(context: Context) {
        val intent = Intent(context, MediaNotificationService::class.java)
        context.stopService(intent)
    }

    fun sendActionToWebView(action: String) {
        onActionReceived?.invoke(action)
    }
}

class MediaNotificationService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var imageLoadingJob: Job? = null
    private var currentBitmap: Bitmap? = null

    private var songTitle = ""
    private var songArtist = ""
    private var songArtworkUrl = ""
    private var isPlaying = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "GaanaMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    MediaControllerHelper.sendActionToWebView("play")
                }

                override fun onPause() {
                    MediaControllerHelper.sendActionToWebView("pause")
                }

                override fun onSkipToNext() {
                    MediaControllerHelper.sendActionToWebView("next")
                }

                override fun onSkipToPrevious() {
                    MediaControllerHelper.sendActionToWebView("previous")
                }

                override fun onStop() {
                    stopPlaybackService()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_PLAY -> {
                    MediaControllerHelper.sendActionToWebView("play")
                    return START_STICKY
                }
                ACTION_PAUSE -> {
                    MediaControllerHelper.sendActionToWebView("pause")
                    return START_STICKY
                }
                ACTION_NEXT -> {
                    MediaControllerHelper.sendActionToWebView("next")
                    return START_STICKY
                }
                ACTION_PREVIOUS -> {
                    MediaControllerHelper.sendActionToWebView("previous")
                    return START_STICKY
                }
                ACTION_STOP, ACTION_DISMISS -> {
                    stopPlaybackService()
                    return START_NOT_STICKY
                }
            }

            val newTitle = intent.getStringExtra(EXTRA_TITLE) ?: songTitle
            val newArtist = intent.getStringExtra(EXTRA_ARTIST) ?: songArtist
            val newArtwork = intent.getStringExtra(EXTRA_ARTWORK) ?: songArtworkUrl
            val newPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying)

            val artworkChanged = newArtwork != songArtworkUrl && newArtwork.isNotBlank()

            songTitle = newTitle
            songArtist = newArtist
            songArtworkUrl = newArtwork
            isPlaying = newPlaying

            if (songTitle.isBlank() && !isPlaying) {
                stopPlaybackService()
                return START_NOT_STICKY
            }

            updateMediaSessionState()

            if (artworkChanged) {
                loadArtworkAndShow(songArtworkUrl)
            } else {
                showNotification(currentBitmap)
            }
        }

        return START_STICKY
    }

    private fun loadArtworkAndShow(url: String) {
        imageLoadingJob?.cancel()
        // First show notification with current/fallback bitmap
        showNotification(currentBitmap)

        if (url.isBlank()) return

        imageLoadingJob = serviceScope.launch {
            try {
                val imageLoader = ImageLoader(this@MediaNotificationService)
                val request = ImageRequest.Builder(this@MediaNotificationService)
                    .data(url)
                    .allowHardware(false)
                    .build()

                val result = withContext(Dispatchers.IO) {
                    imageLoader.execute(request)
                }

                if (result is SuccessResult) {
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        currentBitmap = bitmap
                        showNotification(bitmap)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun showNotification(artworkBitmap: Bitmap?) {
        val displayTitle = if (songTitle.isNotBlank()) songTitle else getString(R.string.app_name)
        val displayArtist = if (songArtist.isNotBlank()) songArtist else "Ayush Gaana"

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = PendingIntent.getService(
            this, 5,
            Intent(this, MediaNotificationService::class.java).apply { action = ACTION_DISMISS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPendingIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MediaNotificationService::class.java).apply { action = ACTION_PREVIOUS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val playPausePendingIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MediaNotificationService::class.java).apply { action = playPauseAction },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextPendingIntent = PendingIntent.getService(
            this, 3,
            Intent(this, MediaNotificationService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mediaStyle = MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
            .setShowCancelButton(true)
            .setCancelButtonIntent(dismissIntent)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setStyle(mediaStyle)
            .setContentTitle(displayTitle)
            .setContentText(displayArtist)
            .setSubText(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_music_notification)
            .setContentIntent(openAppIntent)
            .setDeleteIntent(dismissIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_skip_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(R.drawable.ic_skip_next, "Next", nextPendingIntent)

        if (artworkBitmap != null) {
            notificationBuilder.setLargeIcon(artworkBitmap)
        }

        if (isPlaying) {
            // When playing: Ongoing foreground service notification (keeps background playback alive)
            notificationBuilder.setOngoing(true)
            val notification = notificationBuilder.build()
            startForeground(NOTIFICATION_ID, notification)
        } else {
            // When paused: Non-ongoing so user can swipe it away and dismiss it freely!
            notificationBuilder.setOngoing(false)
            notificationBuilder.setAutoCancel(true)
            val notification = notificationBuilder.build()

            // Detach from foreground state so notification becomes removable/swipeable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }

            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateMediaSessionState() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, songTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, songArtist)
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun stopPlaybackService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls and song info"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        imageLoadingJob?.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "gaana_media_channel"
        const val NOTIFICATION_ID = 8481

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_ARTWORK = "extra_artwork"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        const val ACTION_PLAY = "com.example.service.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.service.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.service.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.service.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_DISMISS = "com.example.service.ACTION_DISMISS"
    }
}
