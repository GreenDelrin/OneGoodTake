package com.socialmaster.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.socialmaster.app.audio.AudioCapture
import com.socialmaster.app.audio.AudioDecoder
import com.socialmaster.app.audio.BackingLibraryEntry
import com.socialmaster.app.audio.Exporter
import com.socialmaster.app.audio.InputChannelMode
import com.socialmaster.app.audio.InputLevels
import com.socialmaster.app.audio.InputSourceProfile
import com.socialmaster.app.audio.Mastering
import com.socialmaster.app.audio.Mixer
import com.socialmaster.app.audio.OutputTest
import com.socialmaster.app.audio.Project
import com.socialmaster.app.audio.RealtimeMixPlayer
import com.socialmaster.app.audio.Track
import com.socialmaster.app.audio.WavIO
import com.socialmaster.app.audio.WorkspaceStore
import com.socialmaster.app.audio.fileFromUri
import com.socialmaster.app.audio.restoreProject
import com.socialmaster.app.audio.restoreBackingLibrary
import com.socialmaster.app.audio.writeBackingLibrary
import com.socialmaster.app.audio.writeTo
import com.socialmaster.app.camera.RecordingController
import com.socialmaster.app.player.AudioPlayer
import com.socialmaster.app.ui.VideoPreviewController
import com.socialmaster.app.ui.screens.InputSetupDialog
import com.socialmaster.app.ui.screens.BackingLibraryDialog
import com.socialmaster.app.ui.screens.MixingScreen
import com.socialmaster.app.ui.screens.RecordingScreen
import com.socialmaster.app.ui.theme.SocialMasterTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SocialMasterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(this)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(activity: MainActivity) {
    val scope = rememberCoroutineScope()
    val outputDir = remember { activity.filesDir }
    val workspaceStore = remember { WorkspaceStore(activity) }
    val restored = remember { workspaceStore.load() }
    val project = remember { restoreProject(restored) }
    val camera = remember {
        RecordingController(activity).apply { useFrontCamera = restored["camera.front"]?.toBooleanStrictOrNull() ?: true }
    }
    val capture = remember { AudioCapture(activity) }
    LaunchedEffect(Unit) { capture.warmUp() }
    val backingPlayer = remember { AudioPlayer(activity) }
    LaunchedEffect(Unit) { backingPlayer.warmUp() }
    val mixPreview = remember { RealtimeMixPlayer(activity) }
    LaunchedEffect(Unit) { mixPreview.warmUp() }
    val mixVideoController = remember { VideoPreviewController() }

    var screen by remember {
        mutableStateOf(if ((restored["screen"] == "mix" || restored["screen"] == "master") && project.tracks.isNotEmpty()) "mix" else "record")
    }
    var hasPermissions by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var isFinalizing by remember { mutableStateOf(false) }
    var isStartingTake by remember { mutableStateOf(false) }
    var isMonitoring by remember { mutableStateOf(false) }
    var monitorInput by remember { mutableStateOf(restored["capture.monitorInput"]?.toBooleanStrictOrNull() ?: false) }
    var isLoadingBacking by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var isExporting by remember { mutableStateOf(false) }
    var isMixPreviewPlaying by remember { mutableStateOf(false) }
    var isMixPreviewPreparing by remember { mutableStateOf(false) }
    var mixPreviewPositionMs by remember { mutableLongStateOf(0L) }
    var mixPreviewDurationMs by remember { mutableLongStateOf(0L) }
    var keepScreenOn by remember { mutableStateOf(restored["capture.keepScreenOn"]?.toBooleanStrictOrNull() ?: true) }
    var useFront by remember { mutableStateOf(restored["camera.front"]?.toBooleanStrictOrNull() ?: true) }
    var cameraWasFront by remember { mutableStateOf(restored["camera.front"]?.toBooleanStrictOrNull() ?: true) }
    var mode by remember {
        mutableStateOf(InputChannelMode.values().firstOrNull { it.name == restored["capture.mode"] } ?: InputChannelMode.INPUTS_1_2_STEREO)
    }
    var sourceProfile by remember {
        mutableStateOf(InputSourceProfile.values().firstOrNull { it.name == restored["capture.profile"] }
            ?: InputSourceProfile.USB_DIRECT)
    }
    var sourceProfileManuallySelected by remember { mutableStateOf(restored["capture.profileManual"]?.toBooleanStrictOrNull() ?: false) }
    var inputDevices by remember { mutableStateOf(capture.availableInputDevices()) }
    val savedDeviceIdentity = restored["capture.deviceIdentity"].orEmpty()
    val restoredDevice = inputDevices.firstOrNull { it.workspaceIdentity() == savedDeviceIdentity }
    var selectedDeviceIdentity by remember { mutableStateOf(savedDeviceIdentity) }
    var selectedDeviceId by remember { mutableStateOf(restoredDevice?.id) }
    var userSelectedInput by remember {
        mutableStateOf(restored["capture.userSelectedInput"]?.toBooleanStrictOrNull() == true && (restoredDevice != null || savedDeviceIdentity.isEmpty()))
    }
    var levels by remember { mutableStateOf(InputLevels(0f, 0f)) }
    var routeInfo by remember { mutableStateOf("") }
    var inputDiagnostics by remember { mutableStateOf("") }
    var outputRoute by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var livePeaks by remember { mutableStateOf<List<Float>>(emptyList()) }
    var backingPeaks by remember { mutableStateOf<List<Float>>(emptyList()) }
    var trackWaveforms by remember { mutableStateOf<Map<String, List<Float>>>(emptyMap()) }
    val restoredBacking = restoredFile(restored, "backing.path")
    val restoredBackingName = restored["backing.name"].orEmpty()
    var backingTrack by remember { mutableStateOf(restoredBacking) }
    var backingDisplayName by remember { mutableStateOf(restoredBackingName) }
    var backingLibrary by remember {
        mutableStateOf(
            restoreBackingLibrary(restored).let { entries ->
                if (restoredBacking == null || entries.any { it.path == restoredBacking.absolutePath }) entries
                else listOf(BackingLibraryEntry(restoredBacking.absolutePath, restoredBackingName, false, 0L)) + entries
            }
        )
    }
    var backingLibraryOpen by remember { mutableStateOf(false) }
    var backingPlaying by remember { mutableStateOf(false) }
    var backingPositionMs by remember { mutableLongStateOf(restored["backing.positionMs"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L) }
    var backingDurationMs by remember { mutableLongStateOf(0L) }
    var takeBackingStartMs by remember { mutableLongStateOf(0L) }
    val restoredVideo = restoredFile(restored, "media.video")
    var recordedVideo by remember { mutableStateOf(restoredVideo) }
    var completedVideo by remember { mutableStateOf(restoredVideo) }
    var videoDurationMs by remember { mutableLongStateOf(restored["media.videoDurationMs"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L) }
    var recordedAudio by remember {
        mutableStateOf(project.tracks.firstOrNull { it.id == "interface" }?.sourceUri?.let(::fileFromUri)?.takeIf { it.isFile })
    }
    var measuredLufs by remember {
        mutableFloatStateOf(restored["master.measuredLufs"]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: -70f)
    }
    var trackVersion by remember { mutableIntStateOf(0) }
    var videoFinalize by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    fun saveWorkspace() {
        val snapshot = linkedMapOf<String, String>()
        snapshot["screen"] = screen
        snapshot["camera.front"] = useFront.toString()
        snapshot["capture.mode"] = mode.name
        snapshot["capture.profile"] = sourceProfile.name
        snapshot["capture.profileManual"] = sourceProfileManuallySelected.toString()
        snapshot["capture.deviceIdentity"] = selectedDeviceIdentity
        snapshot["capture.userSelectedInput"] = userSelectedInput.toString()
        snapshot["capture.monitorInput"] = monitorInput.toString()
        snapshot["capture.keepScreenOn"] = keepScreenOn.toString()
        snapshot["backing.path"] = backingTrack?.absolutePath.orEmpty()
        snapshot["backing.name"] = backingDisplayName
        snapshot["backing.positionMs"] = backingPositionMs.toString()
        snapshot["media.video"] = completedVideo?.absolutePath.orEmpty()
        snapshot["media.videoDurationMs"] = videoDurationMs.toString()
        snapshot["master.measuredLufs"] = measuredLufs.toString()
        project.writeTo(snapshot)
        writeBackingLibrary(backingLibrary, snapshot)
        workspaceStore.save(snapshot)
    }

    val saveWorkspaceOnStop by rememberUpdatedState(::saveWorkspace)

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermissions = results.values.all { it }
        if (!hasPermissions) status = "Camera and microphone access are required to record a take."
    }
    LaunchedEffect(Unit) {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (required.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
            hasPermissions = true
        } else permissionLauncher.launch(required)
    }
    LaunchedEffect(inputDevices, userSelectedInput, selectedDeviceId, selectedDeviceIdentity, sourceProfileManuallySelected) {
        if (!userSelectedInput && selectedDeviceId == null) {
            val preferred = inputDevices.firstOrNull { it.workspaceIdentity() == selectedDeviceIdentity }
            val device = preferred ?: inputDevices.firstOrNull { it.isUsbAudioInput() }
            if (device != null) {
                selectedDeviceId = device.id
                selectedDeviceIdentity = device.workspaceIdentity()
                userSelectedInput = true
                routeInfo = "${device.productName} detected; test input in setup"
            }
        }
        if (!sourceProfileManuallySelected) {
            val selected = inputDevices.firstOrNull { it.id == selectedDeviceId }
            if (selected != null) sourceProfile = if (selected.isUsbAudioInput()) InputSourceProfile.USB_DIRECT else InputSourceProfile.RAW
            else if (inputDevices.isNotEmpty() && inputDevices.none { it.isUsbAudioInput() }) sourceProfile = InputSourceProfile.RAW
        }
    }
    LaunchedEffect(completedVideo) {
        val file = completedVideo ?: return@LaunchedEffect
        val duration = withContext(Dispatchers.IO) { readVideoDurationMs(file) } ?: return@LaunchedEffect
        if (duration <= 0L) return@LaunchedEffect
        videoDurationMs = duration
        if (project.trimEndMs == 0L || project.trimEndMs > duration) project.trimEndMs = duration
        if (project.trimStartMs >= project.trimEndMs) project.trimStartMs = 0L
    }
    LaunchedEffect(hasPermissions, selectedDeviceId, sourceProfile) {
        if (hasPermissions && sourceProfile == InputSourceProfile.USB_DIRECT && selectedDeviceId != null) {
            val requestDeviceId = selectedDeviceId
            capture.requestDirectUsbPermission(requestDeviceId) { error ->
                if (selectedDeviceId == requestDeviceId && sourceProfile == InputSourceProfile.USB_DIRECT) {
                    if (error == null) status = "Direct USB access granted."
                    else {
                        sourceProfile = InputSourceProfile.RAW
                        sourceProfileManuallySelected = true
                        status = error
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        val file = backingTrack ?: return@LaunchedEffect
        val duration = withContext(Dispatchers.IO) { WavIO.durationMs(file) }
        val peaks = withContext(Dispatchers.IO) { WavIO.peaks(file, 500) }
        if (duration == null || peaks == null || duration <= 0L) {
            backingTrack = null
            backingDisplayName = ""
            backingPositionMs = 0L
        } else {
            backingPeaks = peaks
            backingDurationMs = duration
            backingPositionMs = backingPositionMs.coerceAtMost(backingDurationMs)
        }
    }
    LaunchedEffect(
        screen, useFront, mode, sourceProfile, sourceProfileManuallySelected, selectedDeviceIdentity,
        userSelectedInput, monitorInput, keepScreenOn, backingTrack, backingDisplayName,
        backingLibrary,
        backingPositionMs, backingPlaying, completedVideo, videoDurationMs, isExporting,
        measuredLufs, trackVersion, project.syncOffsetMs, project.master.makeupGainDb,
        project.trimStartMs, project.trimEndMs, project.master.limiterThresholdDb,
        project.master.targetLufs, project.master.truePeakCeilingDb
    ) {
        kotlinx.coroutines.delay(350)
        if (!backingPlaying) saveWorkspace()
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) saveWorkspaceOnStop()
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(capture) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                inputDevices = capture.availableInputDevices()
                if (selectedDeviceId == null) routeInfo = "USB input connected — check Recording setup"
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                inputDevices = capture.availableInputDevices()
                if (removedDevices.any { it.id == selectedDeviceId }) {
                    selectedDeviceId = null
                    userSelectedInput = false
                    routeInfo = "Selected interface disconnected; choose an input in setup."
                    if (isRecording || isMonitoring) scope.launch(Dispatchers.IO) { capture.stop() }
                    isMonitoring = false
                }
            }
        }
        capture.registerDeviceCallback(callback)
        onDispose {
            capture.unregisterDeviceCallback(callback)
            capture.stop()
            backingPlayer.release()
        }
    }

    DisposableEffect(mixPreview) {
        onDispose { mixPreview.release() }
    }

    LaunchedEffect(screen) {
        if (screen != "mix" && (isMixPreviewPlaying || isMixPreviewPreparing)) {
            mixPreview.stop()
            mixVideoController.pause()
            isMixPreviewPlaying = false
            isMixPreviewPreparing = false
        }
    }

    LaunchedEffect(isMixPreviewPlaying) {
        while (isMixPreviewPlaying) {
            mixPreviewPositionMs = mixPreview.positionMs
            mixPreviewDurationMs = mixPreview.durationMs
            val videoDuration = mixVideoController.durationMs()
            val videoEnd = project.trimEndMs.takeIf { it > 0L } ?: videoDuration
            if (videoEnd > 0L && mixVideoController.positionMs() >= videoEnd && mixPreview.isPlaying) {
                mixPreview.stop(); mixVideoController.pause()
                isMixPreviewPlaying = false
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(80)
        }
    }

    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(isRecording) {
        val insets = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (isRecording) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else insets.show(WindowInsetsCompat.Type.systemBars())
        onDispose { insets.show(WindowInsetsCompat.Type.systemBars()) }
    }

    LaunchedEffect(isRecording) {
        if (!isRecording) livePeaks = emptyList()
        while (isRecording) {
            livePeaks = capture.livePeaks()
            kotlinx.coroutines.delay(60)
        }
    }
    LaunchedEffect(backingTrack, backingPlaying) {
        while (backingPlaying) {
            backingPositionMs = backingPlayer.currentPositionMs()
            kotlinx.coroutines.delay(100)
        }
    }
    val trackSourcesKey = project.tracks.joinToString("\u0000") { "${it.id}:${it.sourceUri}" }
    LaunchedEffect(trackSourcesKey) {
        val tracks = project.tracks.toList()
        trackWaveforms = withContext(Dispatchers.IO) {
            tracks.mapNotNull { track ->
                val file = fileFromUri(track.sourceUri) ?: return@mapNotNull null
                val peaks = WavIO.peaks(file, 240) ?: return@mapNotNull null
                track.id to peaks
            }.toMap()
        }
    }

    suspend fun loadBackingFile(file: File, name: String): Boolean {
        isLoadingBacking = true
        backingPlayer.stop(); backingPlaying = false
        val (duration, peaks) = withContext(Dispatchers.IO) {
            WavIO.durationMs(file) to WavIO.peaks(file, 500)
        }
        isLoadingBacking = false
        if (duration == null || duration <= 0L || peaks == null) {
            status = "The selected file contains no readable PCM audio."
            return false
        }
        backingTrack = file
        backingDisplayName = name
        backingPeaks = peaks
        backingDurationMs = duration
        backingPositionMs = 0L
        status = "Backing track ready"
        return true
    }

    fun rememberBacking(file: File, name: String) {
        val path = file.absolutePath
        val favorite = backingLibrary.firstOrNull { it.path == path }?.favorite ?: false
        val updated = listOf(BackingLibraryEntry(path, name, favorite, System.currentTimeMillis())) + backingLibrary.filterNot { it.path == path }
        val favorites = updated.filter { it.favorite }
        val recents = updated.filterNot { it.favorite }.take((20 - favorites.size).coerceAtLeast(0))
        backingLibrary = (favorites + recents).sortedByDescending { it.lastUsedMs }
    }

    val backingPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { selected ->
            isLoadingBacking = true
            loadProgress = 0f
            status = "Preparing backing track…"
            val displayName = activity.contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "Backing track"
            backingDisplayName = displayName
            scope.launch {
                val output = File(outputDir, "backing_${System.currentTimeMillis()}.wav")
                val decoded = withContext(Dispatchers.IO) {
                    AudioDecoder.decodeToWav(activity, selected, output) { progress ->
                        activity.runOnUiThread { loadProgress = progress }
                    }
                }
                if (decoded) {
                    if (loadBackingFile(output, displayName)) rememberBacking(output, displayName)
                    else output.delete()
                } else {
                    output.delete()
                    isLoadingBacking = false
                    status = "Could not decode that audio file. Try a PCM WAV or MP3."
                }
            }
        }
    }

    fun testOutput() {
        status = "Playing a 1 kHz test tone…"
        OutputTest.play(activity) { result ->
            status = result
            outputRoute = result
        }
    }

    fun startInputTest() {
        if (isMonitoring) {
            isMonitoring = false; levels = InputLevels(0f, 0f); routeInfo = ""; status = ""
            scope.launch(Dispatchers.IO) { capture.stop() }
            return
        }
        val started = capture.startMonitor(selectedDeviceId, mode,
            onLevels = { levels = it },
            onRoute = { routeInfo = it },
            onError = { status = it; if (isMonitoring) isMonitoring = false },
            onDiagnostics = { inputDiagnostics = it },
            onOutput = { outputRoute = it },
            monitorInput = monitorInput,
            sourceProfile = sourceProfile
        )
        isMonitoring = started
        if (started) status = "Input test running — play each input to check its meter."
    }

    suspend fun startCaptureAndCamera(video: File) {
        val audio = recordedAudio ?: return
        isStartingTake = true
        val captureStarted = withContext(Dispatchers.IO) {
            capture.startRecording(audio, selectedDeviceId, mode,
                onLevels = { levels = it },
                onRoute = { routeInfo = it },
                onError = { message -> activity.runOnUiThread { status = message } },
                onDiagnostics = { inputDiagnostics = it },
                onOutput = { route -> activity.runOnUiThread { outputRoute = route } },
                monitorInput = monitorInput,
                sourceProfile = sourceProfile
            )
        }
        if (!captureStarted) {
            backingPlayer.stop(); backingPlaying = false; isStartingTake = false
            return
        }
        videoFinalize = CompletableDeferred()
        try {
            camera.start(video) { event ->
                if (event is androidx.camera.video.VideoRecordEvent.Finalize) {
                    videoFinalize?.complete(!event.hasError())
                    if (event.hasError()) status = "Video recording failed (${event.error})."
                }
            }
        } catch (e: Exception) {
            capture.stop()
            status = "Could not start camera recording: ${e.message ?: "camera unavailable"}"
            backingPlayer.stop(); backingPlaying = false; isStartingTake = false
            return
        }
        isStartingTake = false
        isRecording = true
        status = "Recording ${mode.label}…"
    }

    fun beginTake(video: File) {
        if (!hasPermissions || isRecording || isStartingTake || isFinalizing || isLoadingBacking) return
        if (isMonitoring) { status = "Stop the input test in setup before recording."; return }
        recordedAudio = File(outputDir, "take_audio_${System.currentTimeMillis()}.wav")
        recordedVideo = video
        takeBackingStartMs = backingPositionMs
        val backing = backingTrack
        if (backing == null) {
            isStartingTake = true
            status = "Starting synchronized capture…"
            scope.launch { startCaptureAndCamera(video) }
            return
        }
        isStartingTake = true
        status = "Starting backing playback for synchronized capture…"
        backingPlayer.play(backing, loop = false, startPositionMs = takeBackingStartMs)
        backingPlaying = true
        scope.launch {
            val started = withTimeoutOrNull(3_000) {
                while (!backingPlayer.isPlaying()) kotlinx.coroutines.delay(20)
                true
            } ?: false
            if (!started) {
                backingPlayer.stop(); backingPlaying = false; isStartingTake = false
                status = "Backing playback did not start; retry or remove the backing track."
                return@launch
            }
            takeBackingStartMs = backingPlayer.currentPositionMs()
            startCaptureAndCamera(video)
        }
    }

    fun finishTake() {
        if (!isRecording || isFinalizing) return
        isRecording = false
        isFinalizing = true
        if (backingPlaying) backingPositionMs = backingPlayer.currentPositionMs()
        backingPlayer.stop(); backingPlaying = false
        camera.stop()
        status = "Saving synchronized audio and video…"
        val finalize = videoFinalize
        scope.launch {
            withContext(Dispatchers.IO) { capture.stop() }
            val videoOk = withTimeoutOrNull(10_000) { finalize?.await() } ?: false
            livePeaks = capture.livePeaks()
            if (!videoOk || recordedAudio?.let { it.exists() && it.length() > 44L } != true) {
                status = "Take did not finish correctly. Open setup and test the selected input."
                isFinalizing = false
                return@launch
            }
            project.tracks.clear()
            backingTrack?.let { project.tracks += Track("backing", "Backing track", "file://${it.absolutePath}", sourceOffsetMs = takeBackingStartMs) }
            recordedAudio?.let {
                project.tracks += Track("interface", mode.label, "file://${it.absolutePath}")
            }
            completedVideo = recordedVideo
            videoDurationMs = completedVideo?.let { file -> withContext(Dispatchers.IO) { readVideoDurationMs(file) } } ?: 0L
            project.trimStartMs = 0L
            project.trimEndMs = videoDurationMs
            trackVersion++
            status = "Take saved — mixing is ready."
            isFinalizing = false
            screen = "mix"
        }
    }

    fun toggleMixPreview() {
        if (isMixPreviewPlaying || isMixPreviewPreparing) {
            mixPreview.stop()
            mixVideoController.pause()
            isMixPreviewPlaying = false
            isMixPreviewPreparing = false
            return
        }
        isMixPreviewPreparing = true
        mixPreviewPositionMs = 0L
        val previewEnd = project.trimEndMs.takeIf { it > 0L } ?: videoDurationMs
        mixPreview.play(project.tracks.toList(), project.trimStartMs, previewEnd,
            onStarted = {
                mixVideoController.playAt(project.trimStartMs)
                isMixPreviewPreparing = false
                isMixPreviewPlaying = true
                mixPreviewDurationMs = mixPreview.durationMs
            },
            onFinished = { error ->
                mixVideoController.pause()
                isMixPreviewPreparing = false
                isMixPreviewPlaying = false
                mixPreviewPositionMs = mixPreview.positionMs
                if (error != null) status = "Mix preview failed: $error"
            },
            onRoute = { route -> activity.runOnUiThread { outputRoute = route } }
        )
    }

    fun exportFinalVideo() {
        val video = completedVideo ?: return
        if (project.tracks.isEmpty() || isExporting) return
        if (isMixPreviewPlaying || isMixPreviewPreparing) {
            mixPreview.stop(); mixVideoController.pause()
            isMixPreviewPlaying = false; isMixPreviewPreparing = false
        }
        isExporting = true
        val tracks = project.tracks.map { it.copy() }
        val trimStart = project.trimStartMs
        val trimEnd = project.trimEndMs.takeIf { it > 0L } ?: videoDurationMs
        val syncOffset = project.syncOffsetMs
        val targetLufs = project.master.targetLufs
        val masteringProject = project.copy(tracks = tracks.toMutableList(), master = project.master.copy())
        val jobId = System.currentTimeMillis()
        val mixOutput = File(outputDir, "export_mix_$jobId.wav")
        val masterOutput = File(outputDir, "export_master_$jobId.wav")
        val videoOutput = File(outputDir, "SocialMaster_$jobId.mp4")
        status = "Rendering mix…"
        scope.launch {
            try {
                val mixLufs = withContext(Dispatchers.IO) { Mixer.mix(tracks, mixOutput, trimStart, trimEnd) }
                if (mixLufs <= -70f) throw IllegalStateException("The mix is silent. Check the track levels and files.")
                status = "Mastering to ${targetLufs.toInt()} LUFS…"
                val finalLufs = withContext(Dispatchers.IO) { Mastering.auto(masteringProject, mixOutput, masterOutput) }
                status = "Exporting video…"
                val muxed = withContext(Dispatchers.IO) {
                    Exporter.export(video, masterOutput, videoOutput, syncOffset, trimStart, trimEnd, if (cameraWasFront) 270 else 90)
                }
                if (!muxed) throw IllegalStateException("Could not combine the video and mastered audio")
                status = "Saving to Gallery…"
                withContext(Dispatchers.IO) { saveVideoToGallery(activity, videoOutput) }
                project.master.makeupGainDb = masteringProject.master.makeupGainDb
                measuredLufs = finalLufs
                trackVersion++
                status = "Export saved to Movies/SocialMaster"
            } catch (e: Exception) {
                status = "Export failed: ${e.message ?: "audio/video processing error"}"
            } finally {
                isExporting = false
                mixOutput.delete(); masterOutput.delete(); videoOutput.delete()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (screen) {
                "record" -> RecordingScreen(
                    controller = camera,
                    isRecording = isRecording,
                    isFinalizing = isFinalizing,
                    isStartingTake = isStartingTake,
                    useFrontCamera = useFront,
                    onToggleCamera = { useFront = !useFront; cameraWasFront = useFront; camera.toggleCamera() },
                    onStartRecording = ::beginTake,
                    onStopRecording = ::finishTake,
                    backingName = backingDisplayName,
                    backingPeaks = backingPeaks,
                    backingProgress = if (backingDurationMs > 0) backingPositionMs.toFloat() / backingDurationMs else 0f,
                    backingPositionMs = backingPositionMs,
                    backingDurationMs = backingDurationMs,
                    backingPlaying = backingPlaying,
                    onPreviewBacking = {
                        val file = backingTrack
                        if (file != null) {
                            if (backingPlaying) { backingPositionMs = backingPlayer.currentPositionMs(); backingPlayer.pause(); backingPlaying = false }
                            else {
                                backingPlayer.play(file, loop = false, startPositionMs = backingPositionMs)
                                backingPlaying = true
                                outputRoute = backingPlayer.routeDescription()
                            }
                        }
                    },
                    onRewindBacking = { backingPositionMs = 0L; backingPlayer.seekTo(0L) },
                    onScrubBacking = { fraction ->
                        backingPositionMs = (backingDurationMs * fraction).toLong()
                        backingPlayer.seekTo(backingPositionMs)
                    },
                    onLoadBacking = { backingLibraryOpen = true },
                    isLoadingBacking = isLoadingBacking,
                    backingLoadProgress = loadProgress,
                    livePeaks = livePeaks,
                    interfaceLevel = maxOf(levels.left, levels.right),
                    routeInfo = routeInfo,
                    outputRoute = outputRoute,
                    status = status,
                    outputDir = outputDir,
                    onOpenSetup = { settingsOpen = true }
                )
                else -> MixingScreen(
                    tracks = remember(trackVersion) { project.tracks.toList() },
                    trackWaveforms = trackWaveforms,
                    videoFile = completedVideo,
                    videoController = mixVideoController,
                    isExporting = isExporting,
                    canExport = completedVideo != null && project.tracks.isNotEmpty(),
                    targetLufs = project.master.targetLufs,
                    isPreviewPlaying = isMixPreviewPlaying,
                    isPreviewPreparing = isMixPreviewPreparing,
                    previewPositionMs = mixPreviewPositionMs,
                    previewDurationMs = mixPreviewDurationMs,
                    trimStartMs = project.trimStartMs,
                    trimEndMs = project.trimEndMs,
                    status = status,
                    onTargetLufsChanged = { target -> project.master.targetLufs = target; trackVersion++ },
                    onTrimChanged = { start, end ->
                        if (project.trimStartMs != start || project.trimEndMs != end) {
                            if (isMixPreviewPlaying || isMixPreviewPreparing) {
                                mixPreview.stop(); mixVideoController.pause(); isMixPreviewPlaying = false; isMixPreviewPreparing = false
                            }
                            project.trimStartMs = start
                            project.trimEndMs = end
                            trackVersion++
                        }
                    },
                    onTrackChange = { updated ->
                        val index = project.tracks.indexOfFirst { it.id == updated.id }
                        if (index >= 0) {
                            project.tracks[index] = updated
                            mixPreview.updateTracks(project.tracks.toList())
                            trackVersion++
                        }
                    },
                    onTogglePreview = ::toggleMixPreview,
                    onExport = ::exportFinalVideo
                )
            }
        }
        if (!isRecording && !isStartingTake) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StageButton("01  Record", screen == "record", Modifier.weight(1f), enabled = !isFinalizing && !isExporting) { screen = "record" }
                StageButton("02  Edit + export", screen == "mix", Modifier.weight(1f), enabled = project.tracks.isNotEmpty() && !isFinalizing && !isExporting) { screen = "mix" }
            }
        }
    }

    if (settingsOpen) {
        InputSetupDialog(
            devices = inputDevices,
            selectedDeviceId = selectedDeviceId,
            onDeviceSelected = { id ->
                if (isMonitoring) { isMonitoring = false; scope.launch(Dispatchers.IO) { capture.stop() } }
                selectedDeviceId = id
                selectedDeviceIdentity = inputDevices.firstOrNull { it.id == id }?.workspaceIdentity().orEmpty()
                userSelectedInput = true
                if (id == null || inputDevices.none { it.id == id && it.isUsbAudioInput() }) {
                    sourceProfile = InputSourceProfile.RAW
                    sourceProfileManuallySelected = false
                }
                levels = InputLevels(0f, 0f)
                routeInfo = ""
                inputDiagnostics = ""
            },
            mode = mode,
            onModeSelected = { selected ->
                if (isMonitoring) { isMonitoring = false; scope.launch(Dispatchers.IO) { capture.stop() } }
                mode = selected
                levels = InputLevels(0f, 0f)
                routeInfo = ""
                inputDiagnostics = ""
            },
            sourceProfile = sourceProfile,
            onSourceProfileSelected = { selected ->
                if (isMonitoring) { isMonitoring = false; scope.launch(Dispatchers.IO) { capture.stop() } }
                levels = InputLevels(0f, 0f)
                routeInfo = ""
                inputDiagnostics = ""
                sourceProfile = selected
                sourceProfileManuallySelected = true
                status = if (selected == InputSourceProfile.USB_DIRECT) "Requesting direct USB permission…" else ""
            },
            isTesting = isMonitoring,
            onToggleTest = ::startInputTest,
            monitorInput = monitorInput,
            onMonitorInputChanged = { monitorInput = it },
            levels = levels,
            routedDevice = routeInfo,
            outputRoute = outputRoute,
            onTestOutput = ::testOutput,
            diagnostics = inputDiagnostics,
            statusMessage = status,
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChanged = { keepScreenOn = it },
            onDismiss = {
                settingsOpen = false
                if (isMonitoring) { isMonitoring = false; scope.launch(Dispatchers.IO) { capture.stop() } }
                status = ""
            }
        )
    }

    if (backingLibraryOpen) {
        BackingLibraryDialog(
            entries = backingLibrary,
            onAddFromPhone = {
                backingLibraryOpen = false
                backingPicker.launch(arrayOf("audio/*"))
            },
            onSelect = { entry ->
                backingLibraryOpen = false
                scope.launch {
                    val file = File(entry.path)
                    if (loadBackingFile(file, entry.name)) rememberBacking(file, entry.name)
                    else backingLibrary = backingLibrary.filterNot { it.path == entry.path }
                }
            },
            onFavoriteChanged = { entry, favorite ->
                backingLibrary = backingLibrary.map {
                    if (it.path == entry.path) it.copy(favorite = favorite) else it
                }
            },
            onDismiss = { backingLibraryOpen = false }
        )
    }
}

private fun AudioDeviceInfo.isUsbAudioInput(): Boolean = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
    type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY

private fun AudioDeviceInfo.workspaceIdentity(): String =
    "${type}|${productName?.toString()?.trim().orEmpty()}|${address.orEmpty()}"

private fun restoredFile(values: Map<String, String>, key: String): File? =
    values[key]?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

private fun saveVideoToGallery(context: Context, file: File) {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/SocialMaster")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Android could not add the video to Gallery")
    try {
        val destination = resolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Android could not open the Gallery file")
        destination.use { output -> file.inputStream().use { input -> input.copyTo(output, 64 * 1024) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}

private fun readVideoDurationMs(file: File): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (_: Exception) { null }
    finally { try { retriever.release() } catch (_: Exception) { } }
}

@Composable
private fun StageButton(label: String, selected: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)
    ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
}
