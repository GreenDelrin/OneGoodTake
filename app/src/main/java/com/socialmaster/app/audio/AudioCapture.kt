package com.socialmaster.app.audio

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.app.PendingIntent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import androidx.core.content.ContextCompat

class AudioCapture(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val peaks = Collections.synchronizedList(mutableListOf<Float>())
    @Volatile private var warmedUp = false

    fun warmUp() {
        if (warmedUp) return
        warmedUp = true
        AudioWarmUp.warm(context)
    }

    fun livePeaks(): List<Float> = synchronized(peaks) { peaks.toList() }

    fun availableInputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).toList()

    fun registerDeviceCallback(callback: AudioDeviceCallback) =
        audioManager.registerAudioDeviceCallback(callback, mainHandler)

    fun unregisterDeviceCallback(callback: AudioDeviceCallback) =
        audioManager.unregisterAudioDeviceCallback(callback)

    fun requestDirectUsbPermission(deviceId: Int?, onResult: (String?) -> Unit) {
        val usbDevice = resolveUsbDevice(deviceId) ?: run {
            onResult("Could not match the selected Android input to one USB audio device.")
            return
        }
        if (usbManager.hasPermission(usbDevice)) {
            onResult(null)
            return
        }
        val action = "${context.packageName}.USB_AUDIO_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiveContext: Context, intent: Intent) {
                try { context.unregisterReceiver(this) } catch (_: Exception) { }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                onResult(if (granted) null else "USB permission was denied for ${usbDevice.productName ?: "the interface"}.")
            }
        }
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val request = Intent(action).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, usbDevice.deviceId, request, flags)
            usbManager.requestPermission(usbDevice, pending)
        } catch (e: Exception) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
            onResult("Could not request USB access: ${e.message ?: "unknown error"}")
        }
    }

    fun startMonitor(
        deviceId: Int?, mode: InputChannelMode,
        onLevels: (InputLevels) -> Unit,
        onRoute: (String) -> Unit,
        onError: (String) -> Unit,
        onDiagnostics: (String) -> Unit = {},
        onOutput: (String) -> Unit = {},
        monitorInput: Boolean = false,
        sourceProfile: InputSourceProfile = InputSourceProfile.RAW
    ): Boolean = startCapture(null, deviceId, mode, onLevels, onRoute, onError, onDiagnostics, onOutput, monitorInput, sourceProfile)

    fun startRecording(
        output: File, deviceId: Int?, mode: InputChannelMode,
        onLevels: (InputLevels) -> Unit,
        onRoute: (String) -> Unit,
        onError: (String) -> Unit,
        onDiagnostics: (String) -> Unit = {},
        onOutput: (String) -> Unit = {},
        monitorInput: Boolean = false,
        sourceProfile: InputSourceProfile = InputSourceProfile.RAW
    ): Boolean = startCapture(output, deviceId, mode, onLevels, onRoute, onError, onDiagnostics, onOutput, monitorInput, sourceProfile)

    private fun startCapture(
        output: File?, deviceId: Int?, mode: InputChannelMode,
        onLevels: (InputLevels) -> Unit,
        onRoute: (String) -> Unit,
        onError: (String) -> Unit,
        onDiagnostics: (String) -> Unit,
        onOutput: (String) -> Unit,
        monitorInput: Boolean,
        sourceProfile: InputSourceProfile
    ): Boolean {
        stop()
        synchronized(peaks) { peaks.clear() }
        // Some interfaces keep their output asleep until real samples arrive through the
        // device the app plays to, and the monitoring stays silent until something else
        // plays. Warm it before the capture starts so the user hears themselves at once.
        if (AudioWarmUp.outputIsUsb(context)) AudioWarmUp.warm(context)
        if (sourceProfile == InputSourceProfile.USB_DIRECT) {
            var directError = "UAC2 direct capture could not start."
            if (startUsbCapture(output, deviceId, mode, onLevels, onRoute, { directError = it }, onDiagnostics, onOutput, monitorInput)) return true
            return startCapture(
                output, deviceId, mode, onLevels, onRoute,
                { onError("Direct USB failed ($directError); Android backup failed: $it") },
                { onDiagnostics("Direct USB unavailable ($directError); Android AudioRecord backup: $it") },
                onOutput, monitorInput, InputSourceProfile.RAW
            )
        }
        val device = deviceId?.let { id -> availableInputDevices().firstOrNull { it.id == id } }
        if (deviceId != null && device == null) {
            onError("Selected interface disconnected. Reopen setup and select it again.")
            return false
        }

        val isUsb = device != null && device.type in intArrayOf(
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY
        )
        val plan = try {
            InputCapturePlan.create(mode.channelSelection, isUsb, device?.channelIndexMasks,
                device?.channelCounts?.takeIf { it.isNotEmpty() }?.any { it >= 2 } != false)
        } catch (e: IllegalArgumentException) {
            onError(e.message ?: "Selected channel mode is not supported by this input.")
            return false
        }
        val channelsToCapture = plan.captureChannels

        val rates = device?.sampleRates?.filter { it in intArrayOf(48000, 44100, 96000, 32000) }
            ?.distinct()?.sortedBy { if (it == 48000) 0 else if (it == 44100) 1 else 2 }
            ?.takeIf { it.isNotEmpty() } ?: listOf(48000, 44100, 96000, 32000)
        val mask = if (channelsToCapture == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        var selected: AudioRecord? = null
        var sampleRate = 0
        for (rate in rates) {
            val baseMinBuffer = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (baseMinBuffer <= 0) continue
            val minBuffer = baseMinBuffer * maxOf(1, (channelsToCapture + 1) / 2)
            val candidate = try {
                val formatBuilder = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                if (plan.usesChannelIndexMask) formatBuilder.setChannelIndexMask(plan.channelIndexMask)
                else formatBuilder.setChannelMask(mask)
                AudioRecord.Builder()
                    .setAudioSource(sourceProfile.audioSource ?: MediaRecorder.AudioSource.UNPROCESSED)
                    .setAudioFormat(formatBuilder.build())
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
            } catch (_: Exception) { null }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                selected = candidate
                sampleRate = rate
                break
            }
            candidate?.release()
        }
        val ar = selected ?: run {
            onError("Could not open ${mode.label} on ${device?.productName ?: "the default input"}. Check USB permissions/device support.")
            return false
        }
        if (device != null && !ar.setPreferredDevice(device)) {
            ar.release()
            onError("Android rejected the selected input route. Reconnect the interface and try again.")
            return false
        }

        val inputChannels = channelsToCapture
        val outputChannels = mode.outputChannels
        val monitorTrack = if (monitorInput) {
            val outputMask = AudioFormat.CHANNEL_OUT_STEREO
            val outputMinBuffer = AudioTrack.getMinBufferSize(sampleRate, outputMask, AudioFormat.ENCODING_PCM_16BIT)
            if (outputMinBuffer <= 0) null else try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(outputMask)
                        .build())
                    .setBufferSizeInBytes(outputMinBuffer * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                    .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
                    ?.also { OutputRouter.apply(it, context, avoidSpeaker = false) }
            } catch (_: Exception) { null }
        } else null
        if (monitorInput && monitorTrack == null) post { onError("Input capture is ready, but Android could not start headphone monitoring. Recording will continue.") }
        post {
            val requested = if (plan.usesChannelIndexMask) "USB channel-index mask 0x${plan.channelIndexMask.toString(16)}" else "standard ${channelsToCapture}-channel mask"
            onDiagnostics("AudioRecord initialized · ${sourceProfile.label} · $sampleRate Hz · $requested${if (monitorTrack != null) " · monitor on" else ""}")
        }
        try {
            ar.startRecording()
            monitorTrack?.play()
        } catch (e: Exception) {
            ar.release()
            monitorTrack?.release()
            onError("Could not start audio input: ${e.message ?: "unknown audio error"}")
            return false
        }
        recorder = ar
        running = true
        worker = Thread({
            var wav: RandomAccessFile? = null
            var dataBytes = 0L
            try {
                if (output != null) {
                    wav = RandomAccessFile(output, "rw")
                    wav.setLength(0)
                    writeWavHeader(wav, sampleRate, outputChannels, 0)
                }
                val buffer = ShortArray(maxOf(256, sampleRate / 100) * inputChannels)
                var routeReported = false
                var routeWaitReads = 0
                var totalFramesRead = 0L
                var lastDiagnosticsAt = 0L
                var outputReported = false
                while (running) {
                    val count = ar.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count < 0) {
                        if (running) post { onError("Audio input read failed ($count). Check the interface connection.") }
                        break
                    }
                    val alignedCount = count - count % inputChannels
                    if (alignedCount == 0) continue
                    if (!routeReported) {
                        val actual = ar.routedDevice
                        routeWaitReads++
                        if (actual != null) {
                            routeReported = true
                            val routeName = actual.productName?.toString() ?: "Audio input"
                            val routeId = actual.id
                            if (deviceId != null && routeId != deviceId) {
                                post { onRoute("Wrong route: requested ${device?.productName}, Android routed to $routeName") }
                                post { onError("Input was not recorded: Android selected a different device.") }
                                break
                            }
                            post { onRoute("$routeName · ${sampleRate / 1000f} kHz · ${mode.label}") }
                            post { onDiagnostics("Android routed to $routeName (id=$routeId, type=${actual.type})") }
                        } else if (routeWaitReads >= 20) {
                            routeReported = true
                            if (deviceId != null) {
                                post { onRoute("Android did not confirm the selected USB input") }
                                post { onError("Input was not recorded: Android could not confirm the interface route.") }
                                break
                            }
                            post { onRoute("System default input · ${sampleRate / 1000f} kHz · ${mode.label}") }
                        }
                    }
                    val input = buffer.copyOf(alignedCount)
                    if (!outputReported) {
                        outputReported = true
                        val route = monitorTrack?.let { "Monitor out: ${OutputRouter.describe(it)}" } ?: "Headphone monitoring is off"
                        post { onOutput(route) }
                    }
                    val channelPeakValues = PcmLevelAnalyzer.peaks(input, inputChannels)
                    val leftPeak = if (inputChannels > 1 || mode.channelSelection != PcmChannelMapper.RIGHT) channelPeakValues[0] else 0
                    val rightPeak = when {
                        inputChannels > 1 -> channelPeakValues[1]
                        mode.channelSelection == PcmChannelMapper.RIGHT -> channelPeakValues[0]
                        else -> 0
                    }
                    var sumSquares = 0.0
                    for (sample in input) sumSquares += sample.toDouble() * sample
                    totalFramesRead += alignedCount / inputChannels
                    val selectedSamples = try {
                        PcmChannelMapper.select(input, inputChannels, plan.mapperSelection)
                    } catch (e: IllegalArgumentException) {
                        post { onError(e.message ?: "The selected channel is unavailable") }
                        break
                    }
                    if (monitorTrack != null) {
                        val stereoMonitor = if (outputChannels == 2) selectedSamples else ShortArray(selectedSamples.size * 2).also { stereo ->
                            for (i in selectedSamples.indices) { stereo[i * 2] = selectedSamples[i]; stereo[i * 2 + 1] = selectedSamples[i] }
                        }
                        val written = monitorTrack.write(stereoMonitor, 0, stereoMonitor.size, AudioTrack.WRITE_NON_BLOCKING)
                        if (written < 0) post { onError("Headphone monitor output failed ($written); input recording continues.") }
                    }
                    if (wav != null) {
                        val bytes = ByteArray(selectedSamples.size * 2)
                        for (i in selectedSamples.indices) {
                            val sample = selectedSamples[i].toInt()
                            bytes[i * 2] = sample.toByte()
                            bytes[i * 2 + 1] = (sample shr 8).toByte()
                        }
                        wav.write(bytes)
                        dataBytes += bytes.size
                    }
                    val peak = selectedSamples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                    synchronized(peaks) {
                        peaks.add((peak / 32768f).coerceIn(0f, 1f))
                        if (peaks.size > 900) peaks.removeAt(0)
                    }
                    val perChannelLevels = channelPeakValues.map { (it / 32768f).coerceIn(0f, 1f) }
                    post { onLevels(InputLevels(leftPeak / 32768f, rightPeak / 32768f, perChannelLevels)) }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastDiagnosticsAt >= 250) {
                        lastDiagnosticsAt = now
                        val blockPeak = maxOf(leftPeak, rightPeak) / 32768f
                        val rms = kotlin.math.sqrt(sumSquares / alignedCount.coerceAtLeast(1)) / 32768.0
                        val detail = if (blockPeak == 0f) "Read $totalFramesRead frames · digital silence · ${channelsToCapture} ch · ${sourceProfile.label}"
                            else "Read $totalFramesRead frames · peak ${(blockPeak * 100).toInt()}% · RMS ${(rms * 100).toInt()}% · ${channelsToCapture} ch · ${sourceProfile.label}"
                        post { onDiagnostics(detail) }
                    }
                }
            } catch (e: Exception) {
                post { onError("Audio capture stopped: ${e.message ?: "unknown error"}") }
            } finally {
                try { wav?.let { writeWavHeader(it, sampleRate, outputChannels, dataBytes); it.close() } } catch (_: Exception) { }
                try { ar.release() } catch (_: Exception) { }
                try { monitorTrack?.pause(); monitorTrack?.flush(); monitorTrack?.release() } catch (_: Exception) { }
                if (recorder === ar) recorder = null
                running = false
            }
        }, "SocialMasterAudioCapture").apply { priority = Thread.MAX_PRIORITY }
        worker?.start()
        return true
    }

    private fun startUsbCapture(
        output: File?, deviceId: Int?, mode: InputChannelMode,
        onLevels: (InputLevels) -> Unit,
        onRoute: (String) -> Unit,
        onError: (String) -> Unit,
        onDiagnostics: (String) -> Unit,
        onOutput: (String) -> Unit,
        monitorInput: Boolean
    ): Boolean {
        val usbDevice = resolveUsbDevice(deviceId) ?: run {
            onError("Could not match the selected Android input to one USB audio device.")
            return false
        }
        if (!usbManager.hasPermission(usbDevice)) {
            onError("Grant Direct USB access in recording setup, then test the input again.")
            return false
        }
        val connection = usbManager.openDevice(usbDevice) ?: run {
            onError("Could not open ${usbDevice.productName ?: "the USB interface"}.")
            return false
        }
        val descriptors = connection.rawDescriptors
        val captureInterfaceId = try { NativeUsb.captureInterface(descriptors) } catch (e: LinkageError) {
            connection.close()
            onError("Direct USB capture library is unavailable for this phone's CPU architecture.")
            return false
        }
        val captureInterface = (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .filter { it.id == captureInterfaceId && it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }
            .minByOrNull { it.alternateSetting }
        if (captureInterface == null) {
            connection.close()
            onError("No UAC2 input interface was found on the selected USB device.")
            return false
        }
        if (!connection.claimInterface(captureInterface, true)) {
            connection.close()
            onError("Could not claim USB capture interface ${captureInterface.id}.")
            return false
        }
        val claimed = listOf(captureInterface)

        val nativeStatus = try {
            NativeUsb.start(connection.fileDescriptor, descriptors, 48000)
        } catch (e: LinkageError) {
            releaseUsbConnection(connection, claimed)
            onError("Direct USB capture library is unavailable for this phone's CPU architecture.")
            return false
        } catch (e: Exception) {
            releaseUsbConnection(connection, claimed)
            onError("Could not start direct USB capture: ${e.message ?: "unknown error"}")
            return false
        }
        if (!nativeStatus.startsWith("OK")) {
            NativeUsb.stop()
            releaseUsbConnection(connection, claimed)
            onError(nativeStatus.trim())
            return false
        }

        val inputChannels = NativeUsb.channels()
        val minimumChannels = if (mode.channelSelection == PcmChannelMapper.STEREO || mode.channelSelection == PcmChannelMapper.RIGHT) 2 else 1
        if (inputChannels < minimumChannels) {
            NativeUsb.stop()
            releaseUsbConnection(connection, claimed)
            onError("Direct USB exposed $inputChannels channel(s); ${mode.label} needs $minimumChannels.")
            return false
        }

        val sampleRate = 48000
        val outputChannels = mode.outputChannels
        val monitorTrack = if (monitorInput) {
            val outputMask = AudioFormat.CHANNEL_OUT_STEREO
            val outputMinBuffer = AudioTrack.getMinBufferSize(sampleRate, outputMask, AudioFormat.ENCODING_PCM_16BIT)
            if (outputMinBuffer <= 0) null else try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(outputMask)
                        .build())
                    .setBufferSizeInBytes(outputMinBuffer * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                    .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
                    ?.also { OutputRouter.apply(it, context, avoidSpeaker = false) }
            } catch (_: Exception) { null }
        } else null
        if (monitorInput && monitorTrack == null) post { onError("Input capture is ready, but Android could not start headphone monitoring. Recording will continue.") }
        post {
            onRoute("${usbDevice.productName ?: "USB interface"} · Direct UAC2 · 48 kHz · ${mode.label}")
            onDiagnostics(nativeStatus.trim())
        }
        try {
            monitorTrack?.play()
        } catch (e: Exception) {
            monitorTrack?.release()
            NativeUsb.stop()
            releaseUsbConnection(connection, claimed)
            onError("Could not start headphone monitoring: ${e.message ?: "unknown error"}")
            return false
        }

        running = true
        worker = Thread({
            var wav: RandomAccessFile? = null
            var dataBytes = 0L
            var lastDiagnosticsAt = 0L
            var lastUsbStatsAt = 0L
            try {
                if (output != null) {
                    wav = RandomAccessFile(output, "rw")
                    wav.setLength(0)
                    writeWavHeader(wav, sampleRate, outputChannels, 0)
                }
                val framesPerRead = maxOf(256, sampleRate / 100)
                val buffer = FloatArray(framesPerRead * inputChannels)
                val channelPeaks = FloatArray(inputChannels)
                var outputReported = false
                while (running) {
                    val framesRead = NativeUsb.read(buffer, framesPerRead)
                    if (framesRead <= 0) {
                        Thread.sleep(3)
                        continue
                    }
                    if (!outputReported) {
                        outputReported = true
                        val route = monitorTrack?.let { "Monitor out: ${OutputRouter.describe(it)}" } ?: "Headphone monitoring is off"
                        post { onOutput(route) }
                    }
                    val sampleCount = framesRead * inputChannels
                    channelPeaks.fill(0f)
                    var sumSquares = 0.0
                    for (frame in 0 until framesRead) {
                        for (channel in 0 until inputChannels) {
                            val value = buffer[frame * inputChannels + channel]
                            channelPeaks[channel] = maxOf(channelPeaks[channel], kotlin.math.abs(value))
                            sumSquares += value.toDouble() * value
                        }
                    }
                    val selectedSamples = PcmFloatChannelMapper.select(buffer.copyOf(sampleCount), inputChannels, mode.channelSelection)
                    if (monitorTrack != null) {
                        val stereoMonitor = if (outputChannels == 2) selectedSamples else ShortArray(selectedSamples.size * 2).also { stereo ->
                            for (i in selectedSamples.indices) { stereo[i * 2] = selectedSamples[i]; stereo[i * 2 + 1] = selectedSamples[i] }
                        }
                        val written = monitorTrack.write(stereoMonitor, 0, stereoMonitor.size, AudioTrack.WRITE_NON_BLOCKING)
                        if (written < 0) post { onError("Headphone monitor output failed ($written); input recording continues.") }
                    }
                    if (wav != null) {
                        val bytes = ByteArray(selectedSamples.size * 2)
                        for (i in selectedSamples.indices) {
                            val sample = selectedSamples[i].toInt()
                            bytes[i * 2] = sample.toByte()
                            bytes[i * 2 + 1] = (sample shr 8).toByte()
                        }
                        wav.write(bytes)
                        dataBytes += bytes.size
                    }
                    val peak = selectedSamples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                    synchronized(peaks) {
                        peaks.add((peak / 32768f).coerceIn(0f, 1f))
                        if (peaks.size > 900) peaks.removeAt(0)
                    }
                    val left = channelPeaks[0].coerceIn(0f, 1f)
                    val right = if (inputChannels > 1) channelPeaks[1].coerceIn(0f, 1f) else 0f
                    val levels = channelPeaks.map { it.coerceIn(0f, 1f) }
                    post { onLevels(InputLevels(left, right, levels)) }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastDiagnosticsAt >= 250) {
                        lastDiagnosticsAt = now
                        val blockPeak = channelPeaks.maxOrNull() ?: 0f
                        val rms = kotlin.math.sqrt(sumSquares / sampleCount.coerceAtLeast(1))
                        val detail = if (blockPeak == 0f) "Direct USB: digital silence · $inputChannels ch · ${mode.label}"
                            else "Direct USB: peak ${(blockPeak * 100).toInt()}% · RMS ${(rms * 100).toInt()}% · $inputChannels ch · ${mode.label}"
                        post { onDiagnostics(detail) }
                    }
                    if (now - lastUsbStatsAt >= 1000) {
                        lastUsbStatsAt = now
                        val stats = NativeUsb.stats()
                        if (stats.size >= 3 && (stats[1] > 0 || stats[2] > 0)) {
                            post { onDiagnostics("Direct USB warnings · overruns=${stats[1]} · USB errors=${stats[2]}") }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) post { onError("Direct USB capture stopped: ${e.message ?: "unknown error"}") }
            } finally {
                try { wav?.let { writeWavHeader(it, sampleRate, outputChannels, dataBytes); it.close() } } catch (_: Exception) { }
                try { monitorTrack?.pause(); monitorTrack?.flush(); monitorTrack?.release() } catch (_: Exception) { }
                NativeUsb.stop()
                releaseUsbConnection(connection, claimed)
                running = false
            }
        }, "SocialMasterUsbCapture").apply { priority = Thread.MAX_PRIORITY }
        worker?.start()
        return true
    }

    private fun resolveUsbDevice(deviceId: Int?): UsbDevice? {
        val audioDevice = deviceId?.let { id -> availableInputDevices().firstOrNull { it.id == id } } ?: return null
        val candidates = usbManager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        }
        val selectedName = audioDevice.productName?.toString()
        val named = candidates.filter { it.productName?.toString() == selectedName }
        return when {
            named.size == 1 -> named.first()
            named.isEmpty() && candidates.size == 1 -> candidates.first()
            else -> null
        }
    }

    private fun releaseUsbConnection(connection: UsbDeviceConnection, claimed: List<UsbInterface>) {
        claimed.asReversed().forEach { try { connection.releaseInterface(it) } catch (_: Exception) { } }
        try { connection.close() } catch (_: Exception) { }
    }

    fun stop() {
        running = false
        val active = recorder
        try { active?.stop() } catch (_: Exception) { }
        try { worker?.join(3000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        worker = null
    }

    private fun writeWavHeader(file: RandomAccessFile, rate: Int, channels: Int, dataBytes: Long) {
        require(dataBytes <= 0x7fffffffL - 36)
        file.seek(0)
        file.write("RIFF".toByteArray(Charsets.US_ASCII)); writeIntLE(file, (36 + dataBytes).toInt())
        file.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); writeIntLE(file, 16)
        writeShortLE(file, 1); writeShortLE(file, channels)
        writeIntLE(file, rate); writeIntLE(file, rate * channels * 2)
        writeShortLE(file, channels * 2); writeShortLE(file, 16)
        file.write("data".toByteArray(Charsets.US_ASCII)); writeIntLE(file, dataBytes.toInt())
        file.seek(file.length())
    }

    private fun writeIntLE(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff); file.write(value ushr 8 and 0xff)
        file.write(value ushr 16 and 0xff); file.write(value ushr 24 and 0xff)
    }

    private fun writeShortLE(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff); file.write(value ushr 8 and 0xff)
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
