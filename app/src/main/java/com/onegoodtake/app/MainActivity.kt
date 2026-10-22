package com.onegoodtake.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import com.onegoodtake.app.audio.AudioCapture
import com.onegoodtake.app.audio.AudioDecoder
import com.onegoodtake.app.audio.BackingLibraryEntry
import com.onegoodtake.app.audio.BundledDemo
import com.onegoodtake.app.audio.Exporter
import com.onegoodtake.app.audio.InputChannelMode
import com.onegoodtake.app.audio.InputLevels
import com.onegoodtake.app.audio.InputSourceProfile
import com.onegoodtake.app.audio.Mastering
import com.onegoodtake.app.audio.Mixer
import com.onegoodtake.app.audio.Project
import com.onegoodtake.app.audio.RealtimeMixPlayer
import com.onegoodtake.app.audio.Track
import com.onegoodtake.app.audio.WavIO
import com.onegoodtake.app.audio.WorkspaceStore
import com.onegoodtake.app.audio.fileFromUri
import com.onegoodtake.app.audio.restoreProject
import com.onegoodtake.app.audio.restoreBackingLibrary
import com.onegoodtake.app.audio.writeBackingLibrary
import com.onegoodtake.app.audio.writeTo
import com.onegoodtake.app.camera.RecordingController
import com.onegoodtake.app.player.AudioPlayer
import com.onegoodtake.app.ui.VideoPreviewController
import com.onegoodtake.app.ui.components.AppIconButton
import com.onegoodtake.app.ui.screens.InputSetupDialog
import com.onegoodtake.app.ui.screens.BackingLibraryDialog
import com.onegoodtake.app.ui.screens.MixingScreen
import com.onegoodtake.app.ui.screens.RecordingScreen
import com.onegoodtake.app.ui.screens.SetupWizard
import com.onegoodtake.app.ui.screens.WizardStep
import com.onegoodtake.app.ui.theme.AppButtonShape
import com.onegoodtake.app.ui.theme.OneGoodTakeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow
import java.io.File

class MainActivity : ComponentActivity() {
    private var darkTheme by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restored = WorkspaceStore(this).load()
        darkTheme = restored["ui.dark"]?.toBooleanStrictOrNull() ?: true
        setContent {
            OneGoodTakeTheme(darkTheme) {
                SystemBars(darkTheme)
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(this, restored, darkTheme) { darkTheme = !darkTheme }
                }
            }
        }
    }
}

/** Paints the status and navigation bars with the app background so no grey band shows. */
@Composable
private fun SystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    val background = MaterialTheme.colorScheme.background.toArgb()
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context.findActivity())?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = background
            @Suppress("DEPRECATION")
            window.navigationBarColor = background
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

@Composable
private fun AppRoot(activity: MainActivity, restored: Map<String, String>, darkTheme: Boolean, onToggleTheme: () -> Unit) {
    val scope = rememberCoroutineScope()
    val outputDir = remember { activity.filesDir }
    val workspaceStore = remember { WorkspaceStore(activity) }
    val project = remember { restoreProject(restored) }
    var cameraLabel by remember { mutableStateOf("CAMERA") }
    val camera = remember {
        RecordingController(activity).apply {
            onCameraChanged = { label -> cameraLabel = label }
            val savedIndex = restored["camera.index"]?.toIntOrNull()
            if (savedIndex != null) selectCamera(savedIndex)
            else usePreferredFacing(restored["camera.front"]?.toBooleanStrictOrNull() ?: true)
        }
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
    var wizardStep by remember { mutableStateOf(restored["wizard.step"]?.toIntOrNull() ?: WizardStep.WELCOME) }
    var wizardDone by remember { mutableStateOf(restored["wizard.done"]?.toBooleanStrictOrNull() == true) }
    var wizardOpen by remember { mutableStateOf(!wizardDone) }
    var wizardPeaks by remember { mutableStateOf<List<Float>>(emptyList()) }
    var soundHeard by remember {
        mutableStateOf(
            when (restored["wizard.heard"]) {
                "yes" -> true
                "no" -> false
                else -> null
            }
        )
    }
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
    var recordingElapsedMs by remember { mutableLongStateOf(0L) }
    var recordingStartMs by remember { mutableLongStateOf(0L) }
    var masterPeakLevel by remember { mutableFloatStateOf(0f) }

    fun saveWorkspace() {
        val snapshot = linkedMapOf<String, String>()
        snapshot["screen"] = screen
        snapshot["camera.front"] = (camera.cameras.getOrNull(camera.cameraIndex)?.isFront ?: true).toString()
        snapshot["camera.index"] = camera.cameraIndex.toString()
        snapshot["ui.dark"] = darkTheme.toString()
        snapshot["wizard.step"] = wizardStep.toString()
        snapshot["wizard.done"] = wizardDone.toString()
        snapshot["wizard.heard"] = when (soundHeard) {
            true -> "yes"
            false -> "no"
            null -> ""
        }
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
    fun requestPermissions() {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (required.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
            hasPermissions = true
        } else permissionLauncher.launch(required)
    }
    LaunchedEffect(Unit) {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (required.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }) {
            hasPermissions = true
        } else if (!wizardOpen) {
            // The setup wizard asks for these itself, so do not interrupt it.
            permissionLauncher.launch(required)
        }
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
        // On a fresh install there is no backing track yet. Decode the bundled
        // demo once so a new user can play along immediately.
        val file = backingTrack ?: withContext(Dispatchers.IO) {
            val demo = BundledDemo.ensureWav(activity)
            if (demo != null && demo.isFile) {
                // Add it to the library as a favourite so it is easy to find again.
                if (backingLibrary.none { it.path == demo.absolutePath }) {
                    backingLibrary = listOf(BackingLibraryEntry(demo.absolutePath, "Demo backing track", true, System.currentTimeMillis())) + backingLibrary
                }
            }
            demo
        }
        if (file == null || !file.isFile) return@LaunchedEffect
        val duration = withContext(Dispatchers.IO) { WavIO.durationMs(file) }
        val peaks = withContext(Dispatchers.IO) { WavIO.peaks(file, 500) }
        if (duration == null || peaks == null || duration <= 0L) {
            if (backingTrack == file) {
                backingTrack = null
                backingDisplayName = ""
                backingPositionMs = 0L
            }
        } else {
            if (backingTrack == null) backingTrack = file
            if (backingDisplayName.isBlank()) backingDisplayName = "Demo backing track"
            backingPeaks = peaks
            backingDurationMs = duration
            backingPositionMs = backingPositionMs.coerceAtMost(backingDurationMs)
        }
    }
    LaunchedEffect(
        screen, mode, sourceProfile, sourceProfileManuallySelected, selectedDeviceIdentity,
        userSelectedInput, monitorInput, keepScreenOn, backingTrack, backingDisplayName,
        backingLibrary, cameraLabel, darkTheme, wizardStep, wizardDone, soundHeard,
        backingPositionMs, backingPlaying, completedVideo, videoDurationMs, isExporting,
        measuredLufs, trackVersion, project.syncOffsetMs, project.master.makeupGainDb,
        project.trimStartMs, project.trimEndMs, project.master.limiterThresholdDb,
        project.master.targetLufs, project.master.truePeakCeilingDb
    ) {
        kotlinx.coroutines.delay(350)
        saveWorkspace()
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

    LaunchedEffect(Unit) {
        // The wizard shows the same live strip the record screen does, so it needs the peaks.
        while (true) {
            if (wizardOpen && isMonitoring) {
                wizardPeaks = capture.livePeaks()
            } else {
                wizardPeaks = emptyList()
            }
            kotlinx.coroutines.delay(80)
        }
    }

    LaunchedEffect(isMixPreviewPlaying) {
        while (isMixPreviewPlaying) {
            mixPreviewPositionMs = mixPreview.positionMs
            mixPreviewDurationMs = mixPreview.durationMs
            // Estimate master peak from track volumes (rough approximation)
            val peakEstimate = project.tracks.fold(0f) { acc, track ->
                acc + (if (!track.muted) 10.0.pow(track.volumeDb / 20.0).toFloat() else 0f)
            }.coerceIn(0f, 1f)
            masterPeakLevel = peakEstimate
            val videoDuration = mixVideoController.durationMs()
            val videoEnd = project.trimEndMs.takeIf { it > 0L } ?: videoDuration
            if (videoEnd > 0L && mixVideoController.positionMs() >= videoEnd && mixPreview.isPlaying) {
                mixPreview.stop(); mixVideoController.pause()
                isMixPreviewPlaying = false
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(80)
        }
        masterPeakLevel = 0f
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
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingStartMs = System.currentTimeMillis()
            recordingElapsedMs = 0L
        }
        while (isRecording) {
            recordingElapsedMs = System.currentTimeMillis() - recordingStartMs
            kotlinx.coroutines.delay(100)
        }
        if (!isRecording) recordingElapsedMs = 0L
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

    fun selectInputDevice(id: Int?) {
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
    }

    fun wizardNext() {
        if (wizardStep < WizardStep.LAST) {
            wizardStep++
        } else {
            wizardOpen = false
            wizardDone = true
            if (isMonitoring) { isMonitoring = false; scope.launch(Dispatchers.IO) { capture.stop() } }
            if (!hasPermissions) requestPermissions()
        }
    }

    fun wizardSkip() {
        wizardOpen = false
        wizardDone = true
        if (isMonitoring) { isMonitoring = false; scope.launch(Dispatchers.IO) { capture.stop() } }
        if (!hasPermissions) requestPermissions()
    }

    /** Starting at the very end would play nothing, so a finished track restarts. */
    fun backingStartMs(): Long =
        if (backingDurationMs > 0L && backingPositionMs >= backingDurationMs - 500L) 0L else backingPositionMs

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
        val backing = backingTrack
        if (backing == null) {
            isStartingTake = true
            status = "Starting synchronized capture…"
            scope.launch { startCaptureAndCamera(video) }
            return
        }
        isStartingTake = true
        status = "Starting backing playback for synchronized capture…"
        val start = backingStartMs()
        takeBackingStartMs = start
        backingPositionMs = start
        backingPlayer.play(backing, loop = false, startPositionMs = start)
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
        val videoOutput = File(outputDir, "OneGoodTake_$jobId.mp4")
        status = "Rendering mix…"
        scope.launch {
            try {
                val mixLufs = withContext(Dispatchers.IO) { Mixer.mix(tracks, mixOutput, trimStart, trimEnd) }
                if (mixLufs <= -70f) throw IllegalStateException("The mix is silent. Check the track levels and files.")
                status = "Mastering to ${targetLufs.toInt()} LUFS…"
                val finalLufs = withContext(Dispatchers.IO) { Mastering.auto(masteringProject, mixOutput, masterOutput) }
                status = "Exporting video…"
                val muxed = withContext(Dispatchers.IO) {
                    Exporter.export(video, masterOutput, videoOutput, syncOffset, trimStart, trimEnd, camera.rotationHint)
                }
                if (!muxed) throw IllegalStateException("Could not combine the video and mastered audio")
                status = "Saving to Gallery…"
                withContext(Dispatchers.IO) { saveVideoToGallery(activity, videoOutput) }
                project.master.makeupGainDb = masteringProject.master.makeupGainDb
                measuredLufs = finalLufs
                trackVersion++
                status = "Export saved to Movies/OneGoodTake"
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
                    cameraLabel = cameraLabel,
                    onCycleCamera = { camera.nextCamera() },
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
                                val start = backingStartMs()
                                backingPositionMs = start
                                backingPlayer.play(file, loop = false, startPositionMs = start)
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
                    outputDir = outputDir,
                    onOpenSetup = { settingsOpen = true },
                    recordingElapsedMs = recordingElapsedMs
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
                    onExport = ::exportFinalVideo,
                    masterPeakLevel = masterPeakLevel
                )
            }
        }
        if (!isRecording && !isStartingTake) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StageButton("01  Record", screen == "record", Modifier.weight(1f), enabled = !isFinalizing && !isExporting) { screen = "record" }
                StageButton("02  Edit + export", screen == "mix", Modifier.weight(1f), enabled = project.tracks.isNotEmpty() && !isFinalizing && !isExporting) { screen = "mix" }
                AppIconButton(
                    if (darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    if (darkTheme) "Switch to light theme" else "Switch to dark theme",
                    onToggleTheme
                )
            }
        }
    }

    if (settingsOpen) {
        InputSetupDialog(
            devices = inputDevices,
            selectedDeviceId = selectedDeviceId,
            onDeviceSelected = { id ->
                selectInputDevice(id)
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
            diagnostics = inputDiagnostics,
            statusMessage = status,
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChanged = { keepScreenOn = it },
            onRunSetupWizard = {
                settingsOpen = false
                wizardStep = WizardStep.WELCOME
                wizardOpen = true
            },
            onDismiss = {
                settingsOpen = false
                if (isMonitoring) { isMonitoring = false; scope.launch(Dispatchers.IO) { capture.stop() } }
                status = ""
            }
        )
    }

    if (wizardOpen) {
        SetupWizard(
            step = wizardStep,
            hasPermissions = hasPermissions,
            onRequestPermissions = ::requestPermissions,
            devices = inputDevices,
            selectedDeviceId = selectedDeviceId,
            onDeviceSelected = ::selectInputDevice,
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
            monitorInput = monitorInput,
            onMonitorInputChanged = { monitorInput = it },
            soundHeard = soundHeard,
            onSoundHeard = { heard -> soundHeard = heard },
            isTesting = isMonitoring,
            onToggleTest = ::startInputTest,
            levels = levels,
            livePeaks = wizardPeaks,
            routeInfo = routeInfo,
            outputRoute = outputRoute,
            cameras = camera.cameras,
            cameraIndex = camera.cameraIndex,
            onCameraSelected = { index -> camera.selectCamera(index) },
            backingName = backingDisplayName,
            onLoadDemoTrack = {
                // Load the bundled demo track directly
                scope.launch {
                    val demoFile = withContext(Dispatchers.IO) { BundledDemo.ensureWav(activity) }
                    if (demoFile != null && demoFile.isFile) {
                        if (loadBackingFile(demoFile, "Demo backing track")) {
                            rememberBacking(demoFile, "Demo backing track")
                            status = "Demo backing track loaded. Go to next step."
                        }
                    } else {
                        status = "Could not load demo track."
                    }
                }
            },
            onChooseBackingFromPhone = { backingLibraryOpen = true },
            statusMessage = status,
            onBack = { wizardStep = (wizardStep - 1).coerceAtLeast(WizardStep.WELCOME) },
            onNext = ::wizardNext,
            onSkip = ::wizardSkip
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
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/OneGoodTake")
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
        shape = AppButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 9.dp)
    ) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}
