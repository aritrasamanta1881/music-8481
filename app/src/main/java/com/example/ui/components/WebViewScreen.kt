package com.example.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ui.theme.DarkBackground
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(
    url: String,
    isOnline: Boolean,
    isRefreshing: Boolean,
    isAtTop: Boolean,
    onRefresh: () -> Unit,
    onProgressChanged: (Int) -> Unit,
    onPageStarted: () -> Unit,
    onPageFinished: () -> Unit,
    onPageError: () -> Unit,
    onScrollAtTop: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appName = context.getString(R.string.app_name)
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }

    // Pre-create app storage directories (appName/music and appName/video)
    LaunchedEffect(Unit) {
        try {
            val extStorage = Environment.getExternalStorageDirectory()
            File(File(extStorage, appName), "music").mkdirs()
            File(File(extStorage, appName), "video").mkdirs()

            val pubDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(File(pubDownload, appName), "music").mkdirs()
            File(File(pubDownload, appName), "video").mkdirs()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        pendingPermissionRequest?.let { request ->
            if (isGranted) {
                request.grant(request.resources)
            } else {
                request.deny()
            }
            pendingPermissionRequest = null
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(webViewRef) {
        com.example.service.MediaControllerHelper.onActionReceived = { action ->
            webViewRef?.post {
                webViewRef?.evaluateJavascript("if (window.__triggerMediaAction) window.__triggerMediaAction('$action');", null)
            }
        }
    }

    // Intercept back button to navigate back inside web view history if possible
    BackHandler(enabled = canGoBack) {
        webViewRef?.let { webView ->
            if (webView.canGoBack()) {
                webView.goBack()
                canGoBack = webView.canGoBack()
            }
        }
    }

    // Trigger reload when pulled to refresh
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            webViewRef?.reload()
        }
    }

    // Update cache mode when network state changes
    LaunchedEffect(isOnline) {
        webViewRef?.settings?.cacheMode = if (isOnline) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
    }

    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pullToRefreshState,
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        setBackgroundColor(android.graphics.Color.parseColor("#121212"))

                        setupWebSettings(this, isOnline)

                        // JS Interface for blob/base64 downloads
                        addJavascriptInterface(
                            AndroidDownloadBridge(ctx, appName) { msg ->
                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            },
                            "AndroidDownloadBridge"
                        )

                        // JS Interface for Media Notification State
                        addJavascriptInterface(
                            AndroidMediaBridge(ctx) { title, artist, artworkUrl, isPlaying ->
                                com.example.service.MediaControllerHelper.updateState(
                                    ctx, title, artist, artworkUrl, isPlaying
                                )
                            },
                            "AndroidMediaBridge"
                        )

                        // Download Listener for music and video files
                        setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                            }

                            if (downloadUrl.startsWith("blob:")) {
                                val guessedName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
                                val js = """
                                    (function() {
                                        var guessed = "$guessedName";
                                        var fileName = (guessed && guessed !== "downloadfile.bin" && guessed !== "blob") ? guessed : (window.__lastDownloadName || "");
                                        var xhr = new XMLHttpRequest();
                                        xhr.open('GET', '$downloadUrl', true);
                                        xhr.responseType = 'blob';
                                        xhr.onload = function(e) {
                                            if (this.status == 200) {
                                                var blob = this.response;
                                                var reader = new FileReader();
                                                reader.readAsDataURL(blob);
                                                reader.onloadend = function() {
                                                    var base64data = reader.result;
                                                    var type = blob.type || '$mimetype';
                                                    window.AndroidDownloadBridge.processBlob(base64data, type, fileName);
                                                }
                                            }
                                        };
                                        xhr.send();
                                    })();
                                """.trimIndent()
                                evaluateJavascript(js, null)
                            } else if (downloadUrl.startsWith("data:")) {
                                val bridge = AndroidDownloadBridge(ctx, appName) { msg ->
                                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                }
                                val guessedName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
                                bridge.processBlob(downloadUrl, mimetype, guessedName)
                            } else {
                                triggerDownload(ctx, appName, downloadUrl, userAgent, contentDisposition, mimetype)
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                onProgressChanged(newProgress)
                            }

                            override fun onPermissionRequest(request: PermissionRequest?) {
                                if (request == null) return

                                val hasAudioRequest = request.resources.any {
                                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                                }

                                if (hasAudioRequest) {
                                    if (ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        request.grant(request.resources)
                                    } else {
                                        pendingPermissionRequest = request
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                } else {
                                    request.grant(request.resources)
                                }
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                canGoBack = view?.canGoBack() == true
                                onPageStarted()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                canGoBack = view?.canGoBack() == true
                                onPageFinished()

                                val js = """
                                    (function() {
                                        if (!window.__downloadInterceptorInjected) {
                                            window.__downloadInterceptorInjected = true;
                                            document.addEventListener('click', function(e) {
                                                var target = e.target;
                                                while (target && target.tagName !== 'A') {
                                                    target = target.parentElement;
                                                }
                                                if (target && target.hasAttribute('download')) {
                                                    window.__lastDownloadName = target.getAttribute('download');
                                                }
                                            }, true);
                                        }

                                        if (!window.__mediaBridgeInjected) {
                                            window.__mediaBridgeInjected = true;

                                            // Intercept setter for navigator.mediaSession.metadata to get instant updates
                                            if ('mediaSession' in navigator) {
                                                var ms = navigator.mediaSession;
                                                var _meta = ms.metadata;
                                                try {
                                                    Object.defineProperty(ms, 'metadata', {
                                                        get: function() { return _meta; },
                                                        set: function(val) {
                                                            _meta = val;
                                                            setTimeout(notifyAndroid, 50);
                                                        },
                                                        configurable: true,
                                                        enumerable: true
                                                    });
                                                } catch(e) {}
                                            }

                                            function notifyAndroid() {
                                                try {
                                                    var meta = (navigator.mediaSession && navigator.mediaSession.metadata) ? navigator.mediaSession.metadata : null;
                                                    var title = meta ? (meta.title || '') : '';
                                                    var artist = meta ? (meta.artist || '') : '';
                                                    var artwork = '';
                                                    if (meta && meta.artwork && meta.artwork.length > 0) {
                                                        var last = meta.artwork[meta.artwork.length - 1];
                                                        artwork = last ? (last.src || '') : '';
                                                    }

                                                    var audio = document.querySelector('audio');
                                                    var isPlaying = false;

                                                    if (audio) {
                                                        isPlaying = !audio.paused && !audio.ended && audio.readyState > 0;
                                                    }
                                                    if (navigator.mediaSession && navigator.mediaSession.playbackState) {
                                                        if (navigator.mediaSession.playbackState === 'playing') isPlaying = true;
                                                        else if (navigator.mediaSession.playbackState === 'paused') isPlaying = false;
                                                    }

                                                    // Fallback to DOM elements inside player container if mediaSession title is empty
                                                    if (!title) {
                                                        var player = document.querySelector('footer, [class*="bottom-"], [class*="fixed bottom"], [class*="MiniPlayer"], [class*="player"]');
                                                        if (player) {
                                                            var elTitle = player.querySelector('[class*="title"], [class*="song"], [class*="track"], strong, b');
                                                            if (elTitle) title = elTitle.innerText.trim();
                                                            var elArtist = player.querySelector('[class*="artist"], [class*="singer"], [class*="sub"]');
                                                            if (elArtist) artist = elArtist.innerText.trim();
                                                        }
                                                    }

                                                    // Clean HTML entities if any
                                                    if (title) {
                                                        title = title.replace(/&quot;/g, '"').replace(/&#039;/g, "'").replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
                                                    }
                                                    if (artist) {
                                                        artist = artist.replace(/&quot;/g, '"').replace(/&#039;/g, "'").replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
                                                    }

                                                    if (window.AndroidMediaBridge) {
                                                        if (title || isPlaying || (audio && audio.currentTime > 0)) {
                                                            window.AndroidMediaBridge.onMediaStateChanged(title, artist, artwork, isPlaying);
                                                        }
                                                    }
                                                } catch(e) {
                                                    console.error(e);
                                                }
                                            }

                                            if ('mediaSession' in navigator) {
                                                var ms = navigator.mediaSession;
                                                var originalSetActionHandler = ms.setActionHandler;
                                                window.__mediaHandlers = window.__mediaHandlers || {};

                                                ms.setActionHandler = function(action, handler) {
                                                    window.__mediaHandlers[action] = handler;
                                                    if (originalSetActionHandler) {
                                                        try {
                                                            originalSetActionHandler.call(ms, action, handler);
                                                        } catch(e){}
                                                    }
                                                };
                                            }

                                            window.__triggerMediaAction = function(action) {
                                                if (action === 'play') {
                                                    if (window.__mediaHandlers && typeof window.__mediaHandlers['play'] === 'function') {
                                                        window.__mediaHandlers['play']({ action: 'play' });
                                                    } else {
                                                        var audio = document.querySelector('audio');
                                                        if (audio) audio.play();
                                                        else {
                                                            var btn = document.querySelector('button[aria-label*="Play"], button[title*="Play"], [class*="play"]');
                                                            if (btn) btn.click();
                                                        }
                                                    }
                                                } else if (action === 'pause') {
                                                    if (window.__mediaHandlers && typeof window.__mediaHandlers['pause'] === 'function') {
                                                        window.__mediaHandlers['pause']({ action: 'pause' });
                                                    } else {
                                                        var audio = document.querySelector('audio');
                                                        if (audio) audio.pause();
                                                        else {
                                                            var btn = document.querySelector('button[aria-label*="Pause"], button[title*="Pause"], [class*="pause"]');
                                                            if (btn) btn.click();
                                                        }
                                                    }
                                                } else if (action === 'next') {
                                                    if (window.__mediaHandlers && typeof window.__mediaHandlers['nexttrack'] === 'function') {
                                                        window.__mediaHandlers['nexttrack']({ action: 'nexttrack' });
                                                    } else {
                                                        var btn = document.querySelector('button[aria-label*="Next"], button[title*="Next"], [class*="next"], [class*="skip-next"]');
                                                        if (btn) btn.click();
                                                    }
                                                } else if (action === 'previous') {
                                                    if (window.__mediaHandlers && typeof window.__mediaHandlers['previoustrack'] === 'function') {
                                                        window.__mediaHandlers['previoustrack']({ action: 'previoustrack' });
                                                    } else {
                                                        var btn = document.querySelector('button[aria-label*="Previous"], button[title*="Previous"], [class*="prev"], [class*="skip-prev"]');
                                                        if (btn) btn.click();
                                                    }
                                                }
                                                setTimeout(notifyAndroid, 300);
                                            };

                                            document.addEventListener('play', function(e) { if (e.target.tagName === 'AUDIO') notifyAndroid(); }, true);
                                            document.addEventListener('pause', function(e) { if (e.target.tagName === 'AUDIO') notifyAndroid(); }, true);
                                            document.addEventListener('playing', function(e) { if (e.target.tagName === 'AUDIO') notifyAndroid(); }, true);
                                            document.addEventListener('ended', function(e) { if (e.target.tagName === 'AUDIO') notifyAndroid(); }, true);
                                            document.addEventListener('timeupdate', function(e) {
                                                if (e.target.tagName === 'AUDIO' && (!window.__lastNotifyTime || Date.now() - window.__lastNotifyTime > 1500)) {
                                                    window.__lastNotifyTime = Date.now();
                                                    notifyAndroid();
                                                }
                                            }, true);

                                            setInterval(notifyAndroid, 1500);
                                            notifyAndroid();
                                        }
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(js, null)
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    onPageError()
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val requestUrl = request?.url?.toString() ?: return false

                                return if (requestUrl.startsWith("http://") || requestUrl.startsWith("https://")) {
                                    false
                                } else {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                    true
                                }
                            }
                        }

                        setOnScrollChangeListener { _, _, scrollY, _, _ ->
                            onScrollAtTop(scrollY == 0)
                        }

                        loadUrl(url)
                        webViewRef = this
                        canGoBack = canGoBack()
                    }
                },
                update = { webView ->
                    webViewRef = webView
                    canGoBack = webView.canGoBack()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

class AndroidDownloadBridge(
    private val context: Context,
    private val appName: String,
    private val onToast: (String) -> Unit
) {
    @JavascriptInterface
    fun processBlob(base64Data: String, mimeType: String?, suggestedName: String?) {
        try {
            val pureBase64 = if (base64Data.contains(",")) {
                base64Data.substringAfter(",")
            } else {
                base64Data
            }
            val bytes = android.util.Base64.decode(pureBase64, android.util.Base64.DEFAULT)

            val mime = if (mimeType.isNullOrEmpty()) {
                if (base64Data.startsWith("data:")) {
                    base64Data.substringAfter("data:").substringBefore(";")
                } else "audio/mpeg"
            } else mimeType

            val lowerName = suggestedName?.lowercase() ?: ""
            val isVideo = mime.lowercase().startsWith("video/") ||
                    lowerName.endsWith(".mp4") ||
                    lowerName.endsWith(".mkv") ||
                    lowerName.endsWith(".webm") ||
                    lowerName.endsWith(".avi") ||
                    lowerName.endsWith(".mov")

            val subFolder = if (isVideo) "video" else "music"
            val defaultExt = if (isVideo) ".mp4" else if (lowerName.endsWith(".lrc")) ".lrc" else ".mp3"

            var fileName = suggestedName ?: ""
            if (fileName.isBlank() || fileName == "downloadfile.bin" || fileName == "blob" || !fileName.contains(".")) {
                fileName = "download_${System.currentTimeMillis()}$defaultExt"
            }

            // 1. Save to External Storage root (/sdcard/<appName>/<subFolder>/)
            val externalStorage = Environment.getExternalStorageDirectory()
            val targetDir = File(File(externalStorage, appName), subFolder)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, fileName)
            targetFile.writeBytes(bytes)

            // 2. Save to Public Downloads directory (/sdcard/Download/<appName>/<subFolder>/)
            try {
                val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val pubSubDir = File(File(publicDownload, appName), subFolder)
                if (!pubSubDir.exists()) {
                    pubSubDir.mkdirs()
                }
                File(pubSubDir, fileName).writeBytes(bytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Refresh media store
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mime),
                null
            )

            onToast("Downloaded $fileName to $appName/$subFolder")
        } catch (e: Exception) {
            e.printStackTrace()
            onToast("Download failed: ${e.message}")
        }
    }
}

private fun triggerDownload(
    context: Context,
    appName: String,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimetype: String?
) {
    try {
        var fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
        val mime = mimetype ?: if (fileName.endsWith(".mp4")) "video/mp4" else "audio/mpeg"
        val lowerName = fileName.lowercase()

        val isVideo = mime.lowercase().startsWith("video/") ||
                lowerName.endsWith(".mp4") ||
                lowerName.endsWith(".mkv") ||
                lowerName.endsWith(".webm") ||
                lowerName.endsWith(".avi") ||
                url.lowercase().contains("video") ||
                url.lowercase().contains("format=mp4")

        val subFolder = if (isVideo) "video" else "music"
        val defaultExt = if (isVideo) ".mp4" else if (lowerName.endsWith(".lrc")) ".lrc" else ".mp3"

        if (fileName.isBlank() || fileName == "downloadfile.bin" || fileName == "blob" || !fileName.contains(".")) {
            fileName = "download_${System.currentTimeMillis()}$defaultExt"
        }

        // Ensure directories exist
        val externalStorage = Environment.getExternalStorageDirectory()
        val appFolder = File(externalStorage, appName)
        val targetDir = File(appFolder, subFolder)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val pubFolder = File(File(publicDownload, appName), subFolder)
        if (!pubFolder.exists()) {
            pubFolder.mkdirs()
        }

        val targetFile = File(targetDir, fileName)

        val request = DownloadManager.Request(Uri.parse(url))
        request.setMimeType(mime)

        if (!userAgent.isNullOrEmpty()) {
            request.addRequestHeader("User-Agent", userAgent)
        }

        val cookies = CookieManager.getInstance().getCookie(url)
        if (!cookies.isNullOrEmpty()) {
            request.addRequestHeader("Cookie", cookies)
        }

        request.setTitle(fileName)
        request.setDescription("Downloading to $appName/$subFolder...")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setAllowedOverMetered(true)
        request.setAllowedOverRoaming(true)

        var setSuccess = false
        try {
            request.setDestinationUri(Uri.fromFile(targetFile))
            setSuccess = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (!setSuccess) {
            try {
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "$appName/$subFolder/$fileName"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)

        Toast.makeText(context, "Downloading $fileName to $appName/$subFolder...", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Failed to start download: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun setupWebSettings(webView: WebView, isOnline: Boolean) {
    // Pre-create Code Cache/js directory to prevent Chromium opendir log error on initial launch
    try {
        val cacheDir = java.io.File(webView.context.cacheDir, "WebView/Default/HTTP Cache/Code Cache/js")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    } catch (e: Exception) {
        // Ignore exception
    }

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        loadWithOverviewMode = true
        useWideViewPort = true
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = true
        mediaPlaybackRequiresUserGesture = false

        // Offline caching settings
        cacheMode = if (isOnline) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
    }
}

class AndroidMediaBridge(
    private val context: Context,
    private val onMediaState: (title: String, artist: String, artworkUrl: String, isPlaying: Boolean) -> Unit
) {
    @JavascriptInterface
    fun onMediaStateChanged(title: String?, artist: String?, artworkUrl: String?, isPlaying: Boolean) {
        onMediaState(title ?: "", artist ?: "", artworkUrl ?: "", isPlaying)
    }
}
