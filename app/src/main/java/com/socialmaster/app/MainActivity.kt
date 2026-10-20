package com.socialmaster.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Bundle
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
import androidx.core.content.FileProvider
import com.socialmaster.app.audio.AudioCapture
import com.socialmaster.app.audio.AudioDecoder
import com.socialmaster.app.audio.Dsp
import com.socialmaster.app.audio.Exporter
import com.socialmaster.app.audio.InputChannelMode
import com.socialmaster.app.audio.InputLevels
import com.socialmaster.app.audio.InputSourceProfile
import com.socialmaster.app.audio.Mastering
import com.socialmaster.app.audio.Mixer
import com.socialmaster.app.audio.Project
import com.socialmaster.app.audio.Track
import com.socialmaster.app.audio.WavIO
import com.socialmaster.app.audio.WorkspaceStore
import com.socialmaster.app.audio.fileFromUri
import com.socialmaster.app.audio.restoreProject
import com.socialmaster.app.audio.writeTo
import com.socialmaster.app.camera.RecordingController
import com.socialmaster.app.player.AudioPlayer
import com.socialmaster.app.ui.screens.InputSetupDialog
import com.socialmaster.app.ui.screens.MasteringScreen
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
    val backingPlayer = remember { AudioPlayer(activity) }

    var screen by remember {
        mutableStateOf(when (restored["screen"]) {
            "mix" -> if (project.tracks.isNotEmpty()) "mix" else "record"
            "master" -> if (restoredFile(restored, "media.mixed") != null) "master" else if (project.tracks.isNotEmpty()) "mix" else "record"
            else -> "record"
        })
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
    var isMixing by remember { mutableStateOf(false) }
    var isMastering by remember { mutableStateOf(false) }
    var keepScreenOn by remember { mutableStateOf(restored["capture.keepScreenOn"]?.toBooleanStrictOrNull() ?: true) }
    var useFront by remember { mutableStateOf(restored["camera.front"]?.toBooleanStrictOrNull() ?: true) }
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
    var status by remember { mutableStateOf("") }
    var livePeaks by remember { mutableStateOf<List<Float>>(emptyList()) }
    var backingPeaks by remember { mutableStateOf<List<Float>>(emptyList()) }
    var backingTrack by remember { mutableStateOf(restoredFile(restored, "backing.path")) }
    var backingDisplayName by remember { mutableStateOf(restored["backing.name"].orEmpty()) }
    var backingPlaying by remember { mutableStateOf(false) }
    var backingPositionMs by remember { mutableLongStateOf(restored["backing.positionMs"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L) }
    var backingDurationMs by remember { mutableLongStateOf(0L) }
    var takeBackingStartMs by remember { mutableLongStateOf(0L) }
    val restoredVideo = restoredFile(restored, "media.video")
    var recordedVideo by remember { mutableStateOf(restoredVideo) }
    var completedVideo by remember { mutableStateOf(restoredVideo) }
    var recordedAudio by remember {
        mutableStateOf(project.tracks.firstOrNull { it.id == "interface" }?.sourceUri?.let(::fileFromUri)?.takeIf { it.isFile })
    }
    var mixedFile by remember { mutableStateOf(restoredFile(restored, "media.mixed")) }
    var masteredFile by remember { mutableStateOf(restoredFile(restored, "media.mastered")) }
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
        snapshot["media.mixed"] = mixedFile?.absolutePath.orEmpty()
        snapshot["media.mastered"] = masteredFile?.absolutePath.orEmpty()
        snapshot["master.measuredLufs"] = measuredLufs.toString()
        project.writeTo(snapshot)
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
        val pcm = withContext(Dispatchers.IO) { WavIO.read(file) }
        if (pcm == null || pcm.samples.isEmpty()) {
            backingTrack = null
            backingDisplayName = ""
            backingPositionMs = 0L
        } else {
            backingPeaks = withContext(Dispatchers.Default) { com.socialmaster.app.ui.peaksFromSamples(pcm.samples, 500) }
            backingDurationMs = (pcm.samples.size / pcm.channels * 1000L) / pcm.sampleRate
            backingPositionMs = backingPositionMs.coerceAtMost(backingDurationMs)
        }
    }
    LaunchedEffect(
        screen, useFront, mode, sourceProfile, sourceProfileManuallySelected, selectedDeviceIdentity,
        userSelectedInput, monitorInput, keepScreenOn, backingTrack, backingDisplayName,
        backingPositionMs, backingPlaying, completedVideo, mixedFile, masteredFile,
        measuredLufs, trackVersion, project.syncOffsetMs, project.master.makeupGainDb,
        project.master.limiterThresholdDb, project.master.targetLufs, project.master.truePeakCeilingDb
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

    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(isRecording, isMonitoring) {
        while (isRecording || isMonitoring) {
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

    val backingPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { selected ->
            backingPlayer.stop()
            backingPlaying = false
            backingTrack = null
            backingPeaks = emptyList()
            backingPositionMs = 0L
            backingDurationMs = 0L
            isLoadingBacking = true
            loadProgress = 0f
            status = "Preparing backing track…"
            backingDisplayName = activity.contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "Backing track"
            scope.launch {
                val output = File(outputDir, "backing_${System.currentTimeMillis()}.wav")
                val decoded = withContext(Dispatchers.IO) {
                    AudioDecoder.decodeToWav(activity, selected, output) { progress ->
                        activity.runOnUiThread { loadProgress = progress }
                    }
                }
                if (decoded) {
                    val pcm = withContext(Dispatchers.IO) { WavIO.read(output) }
                    if (pcm != null && pcm.samples.isNotEmpty()) {
                        backingTrack = output
                        backingPeaks = withContext(Dispatchers.Default) { com.socialmaster.app.ui.peaksFromSamples(pcm.samples, 500) }
                        backingDurationMs = (pcm.samples.size / pcm.channels * 1000L) / pcm.sampleRate
                        status = "Backing track ready"
                    } else status = "The selected file contains no readable PCM audio."
                } else status = "Could not decode that audio file. Try a PCM WAV or MP3."
                isLoadingBacking = false
            }
        }
    }

    fun startInputTest() {
        if (isMonitoring) {
            isMonitoring = false; levels = InputLevels(0f, 0f); routeInfo = ""
            scope.launch(Dispatchers.IO) { capture.stop() }
            return
        }
        val started = capture.startMonitor(selectedDeviceId, mode,
            onLevels = { levels = it },
            onRoute = { routeInfo = it },
            onError = { status = it; if (isMonitoring) isMonitoring = false },
            onDiagnostics = { inputDiagnostics = it },
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
            trackVersion++
            mixedFile = null
            masteredFile = null
            status = "Take saved — mixing is ready."
            isFinalizing = false
            screen = "mix"
        }
    }

    fun applyMix() {
        if (project.tracks.isEmpty() || isMixing) return
        isMixing = true
        status = "Applying track levels and effects…"
        scope.launch {
            try {
                val output = File(outputDir, "mix_${System.currentTimeMillis()}.wav")
                measuredLufs = withContext(Dispatchers.IO) { Mixer.mix(project.tracks.toList(), output) }
                mixedFile = output
                masteredFile = null
                status = if (measuredLufs <= -60f) "Mix is digital silence. Check the input test and each source WAV." else "Mix ready — use the preview controls above to listen with video."
            } catch (e: Exception) {
                status = "Mix failed: ${e.message ?: "unsupported or unreadable track"}"
            } finally { isMixing = false }
        }
    }

    fun autoMaster() {
        val input = mixedFile ?: return
        if (isMastering) return
        if (measuredLufs <= -60f) {
            status = "Auto-master stopped: the current mix is silent. Fix the source tracks first."
            return
        }
        isMastering = true
        status = "Measuring and mastering…"
        scope.launch {
            try {
                val output = File(outputDir, "master_${System.currentTimeMillis()}.wav")
                measuredLufs = withContext(Dispatchers.IO) { Mastering.auto(project, input, output) }
                masteredFile = output
                status = "Master created. Listen in the preview, then export."
            } catch (e: Exception) {
                status = "Mastering failed: ${e.message ?: "audio processing error"}"
            } finally { isMastering = false }
        }
    }

    fun exportVideo() {
        val video = completedVideo ?: return
        val input = mixedFile ?: return
        status = "Exporting MP4…"
        scope.launch {
            val output = File(outputDir, "SocialMaster_${System.currentTimeMillis()}.mp4")
            val audio = masteredFile ?: File(outputDir, "master_export_${System.currentTimeMillis()}.wav").also { masterOutput ->
                withContext(Dispatchers.IO) {
                    Mastering.process(input, masterOutput, project.master.makeupGainDb, project.master.limiterThresholdDb)
                }
                masteredFile = masterOutput
            }
            val success = withContext(Dispatchers.IO) { Exporter.export(video, audio, output, project.syncOffsetMs) }
            if (success) {
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", output)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(send, "Share your video"))
                status = "Export complete"
            } else status = "Export failed. Check the recorded video/audio and try again."
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
                    onToggleCamera = { useFront = !useFront; camera.toggleCamera() },
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
                            else { backingPlayer.play(file, loop = false, startPositionMs = backingPositionMs); backingPlaying = true }
                        }
                    },
                    onScrubBacking = { fraction ->
                        backingPositionMs = (backingDurationMs * fraction).toLong()
                        backingPlayer.seekTo(backingPositionMs)
                    },
                    onLoadBacking = { backingPicker.launch(arrayOf("audio/*")) },
                    isLoadingBacking = isLoadingBacking,
                    backingLoadProgress = loadProgress,
                    livePeaks = livePeaks,
                    interfaceLevel = maxOf(levels.left, levels.right),
                    routeInfo = routeInfo,
                    status = status,
                    outputDir = outputDir,
                    onOpenSetup = { settingsOpen = true }
                )
                "mix" -> MixingScreen(
                    tracks = remember(trackVersion) { project.tracks.toList() },
                    videoFile = completedVideo,
                    mixedFile = mixedFile,
                    isMixing = isMixing,
                    status = status,
                    onTrackChange = { updated ->
                        val index = project.tracks.indexOfFirst { it.id == updated.id }
                        if (index >= 0) {
                            project.tracks[index] = updated
                            trackVersion++
                            mixedFile = null
                            masteredFile = null
                        }
                    },
                    onApplyMix = ::applyMix,
                    onGoMaster = { screen = "master" }
                )
                else -> MasteringScreen(
                    project = project,
                    videoFile = completedVideo,
                    previewAudioFile = masteredFile ?: mixedFile,
                    measuredLufs = measuredLufs,
                    onMeasure = {
                        val file = masteredFile ?: mixedFile
                        if (file != null) scope.launch {
                            val pcm = withContext(Dispatchers.IO) { WavIO.read(file) }
                            if (pcm != null) measuredLufs = Dsp.lufs(pcm.samples, pcm.sampleRate, pcm.channels)
                        }
                    },
                    onAutoMaster = ::autoMaster,
                    onMasterSettingsChanged = { masteredFile = null; trackVersion++ },
                    onExport = ::exportVideo,
                    isMastering = isMastering,
                    canExport = completedVideo != null && (masteredFile != null || mixedFile != null),
                    status = status,
                    onBack = { screen = "mix" }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StageButton("01  Record", screen == "record", Modifier.weight(1f), enabled = !isRecording && !isStartingTake && !isFinalizing) { screen = "record" }
            StageButton("02  Mix", screen == "mix", Modifier.weight(1f), enabled = project.tracks.isNotEmpty() && !isRecording && !isStartingTake && !isFinalizing) { screen = "mix" }
            StageButton("03  Master", screen == "master", Modifier.weight(1f), enabled = mixedFile != null && !isRecording && !isStartingTake && !isFinalizing) { screen = "master" }
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
            diagnostics = inputDiagnostics,
            statusMessage = status,
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChanged = { keepScreenOn = it },
            onDismiss = {
                settingsOpen = false
                if (isMonitoring) { isMonitoring = false; scope.launch(Dispatchers.IO) { capture.stop() } }
            }
        )
    }
}

private fun AudioDeviceInfo.isUsbAudioInput(): Boolean = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
    type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY

private fun AudioDeviceInfo.workspaceIdentity(): String =
    "${type}|${productName?.toString()?.trim().orEmpty()}|${address.orEmpty()}"

private fun restoredFile(values: Map<String, String>, key: String): File? =
    values[key]?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

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
