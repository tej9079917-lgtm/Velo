package com.example.ui

import android.app.Activity
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.example.browser.VideoPlayerRequest
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ShieldEmerald
import kotlinx.coroutines.delay

private enum class VideoAspectMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3")
}

/**
 * Full-Featured Native Video & Audio Player supporting any Android-supported quality & format
 * (MP4, WebM, MKV, MOV, HLS .m3u8, MP3, M4A, WAV, FLAC) with Speed, Aspect Ratio, Quality/Source
 * switcher, Audio Track switcher, Volume/Brightness, Loop, Lock Controls, and PiP.
 */
@Composable
fun VideoPlayerSheet(
    request: VideoPlayerRequest?,
    onSelectLocalUri: (Uri, isAudio: Boolean) -> Unit,
    onOpenStreamUrl: (String) -> Unit,
    onEnterPipMode: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    val activity = context as? Activity

    var activeSource by remember(request) { mutableStateOf(request?.primarySource.orEmpty()) }
    var activeQualityLabel by remember(request) {
        mutableStateOf(request?.qualityVariants?.firstOrNull()?.first ?: "Auto")
    }
    var isPrepared by remember(activeSource) { mutableStateOf(false) }
    var isPlaying by remember(activeSource) { mutableStateOf(false) }
    var errorMessage by remember(activeSource) { mutableStateOf<String?>(null) }
    var durationMs by remember(activeSource) { mutableIntStateOf(0) }
    var positionMs by remember(activeSource) { mutableIntStateOf(0) }
    var videoWidth by remember(activeSource) { mutableIntStateOf(16) }
    var videoHeight by remember(activeSource) { mutableIntStateOf(9) }

    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var aspectMode by remember { mutableStateOf(VideoAspectMode.FIT) }
    var isMuted by remember { mutableStateOf(false) }
    var volumeLevel by remember { mutableFloatStateOf(1.0f) }
    var brightnessLevel by remember { mutableFloatStateOf(0.75f) }
    var isLooping by remember { mutableStateOf(false) }
    var controlsLocked by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var audioTracks by remember(activeSource) { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var selectedAudioTrack by remember(activeSource) { mutableIntStateOf(-1) }

    var showSpeedDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showAudioTrackDialog by remember { mutableStateOf(false) }
    var showOpenUrlDialog by remember { mutableStateOf(false) }
    var showSlidersPanel by remember { mutableStateOf(false) }

    val mediaPlayerRef = remember { mutableStateOf<MediaPlayer?>(null) }
    val surfaceRef = remember { mutableStateOf<Surface?>(null) }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onSelectLocalUri(uri, false)
    }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onSelectLocalUri(uri, true)
    }

    // Restore window brightness cleanly on exit
    DisposableEffect(activity) {
        val originalBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        onDispose {
            activity?.window?.let { win ->
                val lp = win.attributes
                lp.screenBrightness = originalBrightness
                win.attributes = lp
            }
        }
    }

    // Configure MediaPlayer whenever activeSource changes
    DisposableEffect(activeSource, request?.isLocalFile) {
        val src = activeSource.trim()
        if (src.isEmpty()) {
            onDispose { }
        } else {
            val mp = MediaPlayer()
            mediaPlayerRef.value = mp
            errorMessage = null
            isPrepared = false
            isPlaying = false

            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                if (src.startsWith("content://") || src.startsWith("file://")) {
                    mp.setDataSource(context, src.toUri())
                } else {
                    mp.setDataSource(src)
                }
                surfaceRef.value?.let { surf ->
                    if (surf.isValid && request?.isAudioOnly != true) {
                        mp.setSurface(surf)
                    }
                }
                mp.setOnPreparedListener { player ->
                    isPrepared = true
                    durationMs = player.duration.coerceAtLeast(0)
                    if (player.videoWidth > 0 && player.videoHeight > 0) {
                        videoWidth = player.videoWidth
                        videoHeight = player.videoHeight
                    }
                    player.isLooping = isLooping
                    val vol = if (isMuted) 0f else volumeLevel
                    player.setVolume(vol, vol)
                    try {
                        player.playbackParams = PlaybackParams().setSpeed(playbackSpeed)
                    } catch (_: Exception) {
                    }
                    // Discover audio tracks
                    try {
                        val tracks = player.trackInfo.mapIndexedNotNull { idx, info ->
                            if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                                val lang = info.language.orEmpty().ifBlank { "Track ${idx + 1}" }
                                idx to "Audio (${lang.uppercase()})"
                            } else null
                        }
                        audioTracks = tracks
                        selectedAudioTrack = tracks.firstOrNull()?.first ?: -1
                    } catch (_: Exception) {
                        audioTracks = emptyList()
                    }
                    player.start()
                    isPlaying = true
                }
                mp.setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) {
                        videoWidth = w
                        videoHeight = h
                    }
                }
                mp.setOnCompletionListener {
                    if (!isLooping) isPlaying = false
                }
                mp.setOnErrorListener { _, what, extra ->
                    errorMessage = "Playback error (code $what/$extra). Format or stream may be restricted."
                    isPrepared = false
                    isPlaying = false
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                errorMessage = e.localizedMessage ?: "Unable to initialize media source."
            }

            onDispose {
                try {
                    if (mp.isPlaying) mp.stop()
                    mp.reset()
                    mp.release()
                } catch (_: Exception) {
                }
                if (mediaPlayerRef.value === mp) {
                    mediaPlayerRef.value = null
                }
            }
        }
    }

    // Live position ticker
    LaunchedEffect(isPrepared, isPlaying) {
        while (isPrepared && isPlaying) {
            val mp = mediaPlayerRef.value
            if (mp != null) {
                try {
                    positionMs = mp.currentPosition.coerceAtLeast(0)
                } catch (_: Exception) {
                }
            }
            delay(400L)
        }
    }

    val isAudioOnly = request?.isAudioOnly == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
    ) {
        // --- VIDEO / AUDIO STAGE ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    if (!controlsLocked) controlsVisible = !controlsVisible
                },
            contentAlignment = Alignment.Center
        ) {
            if (activeSource.isBlank()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "Velo Universal Media Player",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        text = "Play MP4, WebM, MKV, MOV, HLS streams, or local videos & audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                videoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            modifier = Modifier.testTag("player_pick_video_button")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pick Video")
                        }
                        Button(
                            onClick = { audioPicker.launch(arrayOf("audio/*")) },
                            modifier = Modifier.testTag("player_pick_audio_button")
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pick Audio")
                        }
                    }
                    TextButton(onClick = { showOpenUrlDialog = true }) {
                        Text("Open Network Stream URL", color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else if (isAudioOnly) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = "Audio playback",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    Text(
                        text = request?.title.orEmpty().ifBlank { "Audio Track" },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Text(
                        text = "Hi-Fi Audio Playback • ${playbackSpeed}x",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = ShieldEmerald
                    )
                }
            } else {
                val aspectModifier = when (aspectMode) {
                    VideoAspectMode.FILL -> Modifier.fillMaxSize()
                    VideoAspectMode.RATIO_16_9 -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    VideoAspectMode.RATIO_4_3 -> Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                    VideoAspectMode.FIT -> {
                        val ratio = (videoWidth.toFloat() / videoHeight.toFloat().coerceAtLeast(1f))
                            .coerceIn(0.4f, 2.6f)
                        Modifier.fillMaxWidth().aspectRatio(ratio)
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    val surf = Surface(st)
                                    surfaceRef.value = surf
                                    try {
                                        mediaPlayerRef.value?.setSurface(surf)
                                    } catch (_: Exception) {
                                    }
                                }

                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    try {
                                        mediaPlayerRef.value?.setSurface(null)
                                    } catch (_: Exception) {
                                    }
                                    surfaceRef.value?.release()
                                    surfaceRef.value = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = aspectModifier
                )
            }

            // Buffering Spinner
            if (activeSource.isNotBlank() && !isPrepared && errorMessage == null) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            // Error Notice
            if (errorMessage != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = { showOpenUrlDialog = true }) {
                            Text("Try Another Stream URL")
                        }
                    }
                }
            }
        }

        // --- LOCKED OVERLAY BUTTON ---
        if (controlsLocked) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(16.dp)
            ) {
                IconButton(
                    onClick = {
                        controlsLocked = false
                        controlsVisible = true
                    },
                    modifier = Modifier.size(48.dp).testTag("unlock_player_controls_button")
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = "Unlock player controls", tint = Color.White)
                }
            }
        }

        // --- TOP CONTROL BAR ---
        if (!controlsLocked && (controlsVisible || activeSource.isBlank())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).testTag("close_video_player_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close player", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = request?.title.orEmpty().ifBlank { "Velo Player" },
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isPrepared && !isAudioOnly && videoHeight > 16) {
                        Text(
                            text = "${videoWidth}x${videoHeight} • ${activeQualityLabel} • ${playbackSpeed}x",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ShieldEmerald
                        )
                    }
                }
                IconButton(
                    onClick = {
                        videoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Open local video", tint = Color.White)
                }
                IconButton(
                    onClick = onEnterPipMode,
                    modifier = Modifier.size(44.dp).testTag("player_pip_button")
                ) {
                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Enter PiP", tint = Color.White)
                }
            }
        }

        // --- BOTTOM PRO CONTROL DOCK ---
        if (!controlsLocked && controlsVisible && activeSource.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.74f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Brightness & Volume Panel
                if (showSlidersPanel) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.BrightnessHigh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Slider(
                            value = brightnessLevel,
                            onValueChange = { v ->
                                brightnessLevel = v
                                activity?.window?.let { win ->
                                    val lp = win.attributes
                                    lp.screenBrightness = v.coerceIn(0.05f, 1f)
                                    win.attributes = lp
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Slider(
                            value = volumeLevel,
                            onValueChange = { v ->
                                volumeLevel = v
                                isMuted = v <= 0.01f
                                mediaPlayerRef.value?.setVolume(v, v)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Scrubber Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatMillis(positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = Color.White
                    )
                    Slider(
                        value = if (durationMs > 0) positionMs.toFloat().coerceIn(0f, durationMs.toFloat()) else 0f,
                        onValueChange = { target ->
                            positionMs = target.toInt()
                            mediaPlayerRef.value?.seekTo(target.toInt())
                        },
                        valueRange = 0f..(durationMs.coerceAtLeast(1).toFloat()),
                        modifier = Modifier.weight(1f).testTag("player_scrubber_slider")
                    )
                    Text(
                        text = formatMillis(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = Color.White
                    )
                }

                // Transport Controls Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { controlsLocked = true },
                        modifier = Modifier.size(48.dp).testTag("lock_player_controls_button")
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = "Lock controls", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            val target = (positionMs - 10_000).coerceAtLeast(0)
                            positionMs = target
                            mediaPlayerRef.value?.seekTo(target)
                        },
                        modifier = Modifier.size(48.dp).testTag("player_rewind_button")
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind 10 seconds", tint = Color.White)
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(54.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val mp = mediaPlayerRef.value ?: return@IconButton
                                if (isPlaying) {
                                    mp.pause()
                                    isPlaying = false
                                } else {
                                    mp.start()
                                    isPlaying = true
                                }
                            },
                            modifier = Modifier.fillMaxSize().testTag("player_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            val target = (positionMs + 10_000).coerceAtMost(durationMs.coerceAtLeast(0))
                            positionMs = target
                            mediaPlayerRef.value?.seekTo(target)
                        },
                        modifier = Modifier.size(48.dp).testTag("player_forward_button")
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            val v = if (isMuted) 0f else volumeLevel
                            mediaPlayerRef.value?.setVolume(v, v)
                        },
                        modifier = Modifier.size(48.dp).testTag("player_mute_button")
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = Color.White
                        )
                    }
                }

                // Pro Feature Chips Row (Quality, Speed, Aspect Ratio, Audio Track, Loop, Brightness/Vol)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { showSpeedDialog = true },
                        label = { Text("${playbackSpeed}x", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White) }
                    )
                    if (!isAudioOnly) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val entries = VideoAspectMode.entries
                                aspectMode = entries[(aspectMode.ordinal + 1) % entries.size]
                            },
                            label = { Text(aspectMode.label, color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White) }
                        )
                    }
                    if (!request?.qualityVariants.isNullOrEmpty()) {
                        FilterChip(
                            selected = false,
                            onClick = { showQualityDialog = true },
                            label = { Text(activeQualityLabel, color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White) }
                        )
                    }
                    if (audioTracks.size > 1) {
                        FilterChip(
                            selected = false,
                            onClick = { showAudioTrackDialog = true },
                            label = { Text("Audio Track", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White) }
                        )
                    }
                    FilterChip(
                        selected = isLooping,
                        onClick = {
                            isLooping = !isLooping
                            mediaPlayerRef.value?.isLooping = isLooping
                        },
                        label = { Text("Loop", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White) }
                    )
                    FilterChip(
                        selected = showSlidersPanel,
                        onClick = { showSlidersPanel = !showSlidersPanel },
                        label = { Text("Bright/Vol", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.BrightnessHigh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White) }
                    )
                }
            }
        }
    }

    // Speed Selector Dialog
    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    speeds.forEach { spd ->
                        TextButton(
                            onClick = {
                                playbackSpeed = spd
                                try {
                                    mediaPlayerRef.value?.playbackParams = PlaybackParams().setSpeed(spd)
                                } catch (_: Exception) {
                                }
                                showSpeedDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${spd}x" + if (spd == playbackSpeed) "  (Active)" else "",
                                fontWeight = if (spd == playbackSpeed) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) { Text("Close") }
            }
        )
    }

    // Quality / Stream Variant Picker Dialog
    if (showQualityDialog) {
        val variants = request?.qualityVariants.orEmpty()
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Video Quality & Stream") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    variants.forEach { (label, url) ->
                        TextButton(
                            onClick = {
                                activeQualityLabel = label
                                activeSource = url
                                showQualityDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = label + if (url == activeSource) "  (Active)" else "",
                                fontWeight = if (url == activeSource) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) { Text("Close") }
            }
        )
    }

    // Audio Track Picker Dialog
    if (showAudioTrackDialog) {
        AlertDialog(
            onDismissRequest = { showAudioTrackDialog = false },
            title = { Text("Select Audio Track") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    audioTracks.forEach { (idx, label) ->
                        TextButton(
                            onClick = {
                                selectedAudioTrack = idx
                                try {
                                    mediaPlayerRef.value?.selectTrack(idx)
                                } catch (_: Exception) {
                                }
                                showAudioTrackDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = label + if (idx == selectedAudioTrack) "  (Active)" else "",
                                fontWeight = if (idx == selectedAudioTrack) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAudioTrackDialog = false }) { Text("Close") }
            }
        )
    }

    // Open Network Stream URL Dialog
    if (showOpenUrlDialog) {
        var urlInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showOpenUrlDialog = false },
            title = { Text("Play Network Video Stream") },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("https://.../video.mp4 or .m3u8") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onOpenStreamUrl(urlInput.trim())
                            showOpenUrlDialog = false
                        }
                    }
                ) { Text("Play") }
            },
            dismissButton = {
                TextButton(onClick = { showOpenUrlDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatMillis(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hrs = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return if (hrs > 0) "%d:%02d:%02d".format(hrs, mins, secs) else "%02d:%02d".format(mins, secs)
}
