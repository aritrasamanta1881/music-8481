package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

object MediaControllerHelper {
    var onActionReceived: ((String) -> Unit)? = null

    fun sendActionToWebView(action: String) {
        onActionReceived?.invoke(action)
    }

    fun updateState(
        context: Context,
        title: String,
        artist: String,
        artworkUrl: String,
        isPlaying: Boolean
    ) {
        val intent = Intent(context, MediaNotificationService::class.java).apply {
            putExtra("EXTRA_TITLE", title)
            putExtra("EXTRA_ARTIST", artist)
            putExtra("EXTRA_ARTWORK", artworkUrl)
            putExtra("EXTRA_IS_PLAYING", isPlaying)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopService(context: Context) {
        val intent = Intent(context, MediaNotificationService::class.java)
        context.stopService(intent)
    }
}

class MediaNotificationService : Service() {

    private val CHANNEL_ID = "music_playback_channel"
    private val NOTIFICATION_ID = 8481

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentArtworkUrl: String = ""
    private var currentBitmap: Bitmap? = null
    var isPlaying: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "MusicNotificationService").apply {
            isActive = true
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
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val action = it.action
            if (action != null) {
                when (action) {
                    ACTION_PLAY -> MediaControllerHelper.sendActionToWebView("play")
                    ACTION_PAUSE -> MediaControllerHelper.sendActionToWebView("pause")
                    ACTION_NEXT -> MediaControllerHelper.sendActionToWebView("next")
                    ACTION_PREVIOUS -> MediaControllerHelper.sendActionToWebView("previous")
                    ACTION_STOP -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
            } else {
                val title = it.getStringExtra("EXTRA_TITLE") ?: ""
                val artist = it.getStringExtra("EXTRA_ARTIST") ?: ""
                val artworkUrl = it.getStringExtra("EXTRA_ARTWORK") ?: ""
                val playing = it.getBooleanExtra("EXTRA_IS_PLAYING", false)

                updateMediaData(title, artist, artworkUrl, playing)
            }
        }
        return START_STICKY
    }

    private fun updateMediaData(title: String, artist: String, artworkUrl: String, playing: Boolean) {
        val titleText = title.ifBlank { getString(R.string.app_name) }
        val artistText = artist.ifBlank { "Playing" }

        val artworkChanged = artworkUrl != currentArtworkUrl

        currentTitle = titleText
        currentArtist = artistText
        isPlaying = playing

        // Always show notification synchronously first so startForeground is called immediately
        showNotification(currentBitmap)

        if (artworkChanged && artworkUrl.isNotBlank()) {
            currentArtworkUrl = artworkUrl
            loadArtwork(artworkUrl)
        }
    }

    private fun loadArtwork(urlStr: String) {
        serviceScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val url = URL(urlStr)
                    BitmapFactory.decodeStream(url.openStream())
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (bitmap != null) {
                currentBitmap = bitmap
                showNotification(bitmap)
            }
        }
    }

    private fun showNotification(artworkBitmap: Bitmap?) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)

        if (artworkBitmap != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artworkBitmap)
        }
        mediaSession.setMetadata(metadataBuilder.build())

        // Open App PendingIntent
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action PendingIntents
        val prevPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaNotificationService::class.java).apply { action = ACTION_PREVIOUS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val playPausePendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MediaNotificationService::class.java).apply { action = playPauseAction },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextPendingIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, MediaNotificationService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultIcon = try {
            BitmapFactory.decodeResource(resources, R.drawable.app_logo)
        } catch (e: Throwable) {
            null
        }
        val displayIcon = artworkBitmap ?: defaultIcon

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSubText("Music@8481")
            .setSmallIcon(R.drawable.ic_music_notification)
            .setLargeIcon(displayIcon)
            .setContentIntent(openAppPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            // Not removable while music is playing, removable when paused
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            .setStyle(
                MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession.sessionToken)
            )

        val notification = notificationBuilder.build()

        // ALWAYS call startForeground first to fulfill Android 8+ startForegroundService contract
        startForeground(NOTIFICATION_ID, notification)

        if (!isPlaying) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows media notification controls for currently playing song"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PLAY = "com.example.service.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.service.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.service.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.service.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
    }
}
