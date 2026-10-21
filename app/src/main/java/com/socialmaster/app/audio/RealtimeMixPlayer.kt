package com.socialmaster.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

class RealtimeMixPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val latestTracks = AtomicReference<List<Track>>(emptyList())
    @Volatile private var core: RealtimeMixCore? = null
    @Volatile private var output: AudioTrack? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var generation = 0L
    @Volatile private var startSignal: CountDownLatch? = null
    @Volatile private var warmedUp = false
    @Volatile var isPlaying = false
        private set
    @Volatile var isPreparing = false
        private set
    @Volatile var positionMs = 0L
        private set
    @Volatile var durationMs = 0L
        private set

fun warmUp() {
        if (warmedUp) return
        warmedUp = true
        try {
            val sampleRate = RealtimeMixCore.SAMPLE_RATE
            val channelMask = AudioFormat.CHANNEL_OUT_STEREO
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (bufferSize <= 0) return
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                val silence = ShortArray(sampleRate / 10) // 100ms of silence
                track.write(silence, 0, silence.size)
                Thread.sleep(50)
                track.stop()
                track.release()
            }
        } catch (_: Exception) {
            // Ignore warmup failures
        }
    }

    @Synchronized
    fun play(
        tracks: List<Track>, trimStartMs: Long, trimEndMs: Long,
        onStarted: () -> Unit, onFinished: (String?) -> Unit, onRoute: (String) -> Unit = {}
    ) {
        stop()
        val runId = ++generation
        latestTracks.set(tracks.map { it.copy() })
        positionMs = 0L
        durationMs = 0L
        running = true
        isPreparing = true
        worker = Thread({
            val sources = mutableListOf<RealtimeMixSource>()
            var audio: AudioTrack? = null
            var error: String? = null
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                for (track in latestTracks.get()) {
                    val file = fileFromUri(track.sourceUri) ?: throw IllegalArgumentException("${track.name} is not a local audio file")
                    sources += RealtimeWavSource(track.id, file, track.sourceOffsetMs + trimStartMs)
                }
                check(sources.isNotEmpty()) { "No readable audio tracks were found" }
                val trimFrames = if (trimEndMs > 0L) {
                    ((trimEndMs - trimStartMs).coerceAtLeast(0L) * RealtimeMixCore.SAMPLE_RATE / 1000L)
                } else null
                val engine = RealtimeMixCore(sources, latestTracks.get(), trimFrames)
                core = engine
                engine.updateTracks(latestTracks.get())
                durationMs = engine.durationMs
                check(durationMs > 0L) { "The selected tracks contain no playable audio" }

                val channelMask = AudioFormat.CHANNEL_OUT_STEREO
                val minimum = AudioTrack.getMinBufferSize(RealtimeMixCore.SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Android could not create an audio output" }
                audio = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RealtimeMixCore.SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                check(audio.state == AudioTrack.STATE_INITIALIZED) { "Android could not initialize audio output" }
                OutputRouter.apply(audio, appContext)
                val readyAudio = checkNotNull(audio)
                output = readyAudio
                val signal = CountDownLatch(1)
                startSignal = signal
                var startError: Exception? = null
                main.post {
                    try {
                        if (generation == runId && running) {
                            onStarted()
                            if (generation == runId && running) {
                                readyAudio.play()
                                isPreparing = false
                                isPlaying = true
                            }
                        }
                    } catch (e: Exception) {
                        startError = e
                    } finally {
                        signal.countDown()
                    }
                }
                if (!signal.await(3, TimeUnit.SECONDS)) throw IllegalStateException("Preview start timed out")
                if (generation == runId) startSignal = null
                if (startError != null) throw startError as Exception
                if (!running || generation != runId) return@Thread

                val block = ShortArray(BLOCK_FRAMES * 2)
                var routeReported = false
                while (running && generation == runId) {
                    val frames = engine.render(block, BLOCK_FRAMES)
                    if (frames == 0) break
                    var written = 0
                    val count = frames * 2
                    while (running && written < count) {
                        val result = audio.write(block, written, count - written, AudioTrack.WRITE_BLOCKING)
                        if (result < 0) throw IllegalStateException("Audio output failed ($result)")
                        if (result == 0) Thread.sleep(2)
                        written += result
                    }
                    if (!routeReported) {
                        routeReported = true
                        val ready = checkNotNull(audio)
                        main.post { if (generation == runId) onRoute("Mix out: ${OutputRouter.describe(ready)}") }
                    }
                    positionMs = engine.positionMs
                }
                if (positionMs < durationMs && !running) positionMs = engine.positionMs
                if (running && generation == runId) positionMs = durationMs
            } catch (e: Exception) {
                error = e.message ?: "Could not preview this mix"
            } finally {
                if (generation == runId) {
                    isPlaying = false
                    isPreparing = false
                    running = false
                    core = null
                    output = null
                    startSignal = null
                }
                try { audio?.pause(); audio?.flush(); audio?.release() } catch (_: Exception) { }
                sources.forEach { try { it.close() } catch (_: Exception) { } }
                main.post { if (generation == runId) onFinished(error) }
            }
        }, "SocialMasterRealtimeMix")
        worker?.start()
    }

    fun updateTracks(tracks: List<Track>) {
        val snapshot = tracks.map { it.copy() }
        latestTracks.set(snapshot)
        core?.updateTracks(snapshot)
    }

    @Synchronized
    fun stop() {
        generation++
        running = false
        isPreparing = false
        startSignal?.countDown()
        try { output?.stop() } catch (_: Exception) { }
        try { worker?.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        worker = null
        isPlaying = false
    }

    fun release() = stop()

    companion object { private const val BLOCK_FRAMES = 512 }
}

internal class RealtimeWavSource(
    override val id: String,
    file: File,
    offsetMs: Long
) : RealtimeMixSource {
    private val sourceName = file.name
    private val wav = RandomAccessFile(file, "r")
    private val channels: Int
    private val sampleRate: Int
    private val frameBytes: Int
    private val dataOffset: Long
    private val dataFrames: Long
    private val current = FloatArray(2)
    private val next = FloatArray(2)
    private val pcmBuffer = ByteArray(64 * 1024)
    private var bufferPosition = 0
    private var bufferSize = 0
    private var unreadDataBytes = 0L
    private var nextReadFrame = 0L
    private var currentFrame: Long
    private var nextFrame: Long
    private var sourcePosition: Double
    private var outputFrame = 0L
    private val step: Double
    override val durationFrames: Long

    init {
        try {
            val format = readFormat(wav)
            channels = format.channels
            sampleRate = format.sampleRate
            frameBytes = channels * 2
            dataOffset = format.dataOffset
            require(format.dataBytes % frameBytes == 0L) { "$sourceName has incomplete PCM frames" }
            dataFrames = format.dataBytes / frameBytes
            val skipFrames = (sampleRate.toLong() * offsetMs.coerceAtLeast(0L) / 1000L).coerceAtMost(dataFrames)
            durationFrames = ceil((dataFrames - skipFrames).toDouble() * RealtimeMixCore.SAMPLE_RATE / sampleRate).toLong()
            step = sampleRate.toDouble() / RealtimeMixCore.SAMPLE_RATE
            currentFrame = skipFrames
            nextFrame = skipFrames + 1
            sourcePosition = skipFrames.toDouble()
            wav.seek(dataOffset + skipFrames * frameBytes)
            unreadDataBytes = format.dataBytes - skipFrames * frameBytes
            nextReadFrame = skipFrames
            readSourceFrame(current)
            if (!readSourceFrame(next)) current.copyInto(next)
        } catch (e: Exception) {
            try { wav.close() } catch (_: Exception) { }
            throw e
        }
    }

    override fun readFrame(out: FloatArray): Boolean {
        if (outputFrame >= durationFrames) return false
        val fraction = (sourcePosition - currentFrame).toFloat().coerceIn(0f, 1f)
        out[0] = current[0] + (next[0] - current[0]) * fraction
        out[1] = current[1] + (next[1] - current[1]) * fraction
        outputFrame++
        sourcePosition += step
        while (sourcePosition >= nextFrame) {
            next.copyInto(current)
            currentFrame = nextFrame
            nextFrame++
            if (!readSourceFrame(next)) current.copyInto(next)
        }
        return true
    }

    private fun readSourceFrame(out: FloatArray): Boolean {
        if (nextReadFrame >= dataFrames) return false
        if (bufferPosition + frameBytes > bufferSize && !refillBuffer()) return false
        val left = readShortFromBuffer().toFloat()
        out[0] = left
        out[1] = if (channels == 1) left else readShortFromBuffer().toFloat()
        nextReadFrame++
        return true
    }

    private fun refillBuffer(): Boolean {
        val requested = minOf(pcmBuffer.size.toLong(), unreadDataBytes).toInt()
        val aligned = requested - requested % frameBytes
        if (aligned <= 0) return false
        wav.readFully(pcmBuffer, 0, aligned)
        unreadDataBytes -= aligned
        bufferPosition = 0
        bufferSize = aligned
        return true
    }

    private fun readShortFromBuffer(): Short {
        val low = pcmBuffer[bufferPosition++].toInt() and 0xff
        val high = pcmBuffer[bufferPosition++].toInt() and 0xff
        return (low or (high shl 8)).toShort()
    }

    override fun close() = wav.close()

    private data class WavFormat(val channels: Int, val sampleRate: Int, val dataOffset: Long, val dataBytes: Long)

    private fun readFormat(file: RandomAccessFile): WavFormat {
        require(file.length() >= 12) { "$sourceName is not a WAV file" }
        val header = ByteArray(12); file.seek(0); file.readFully(header)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "$sourceName is not a WAV file" }
        var code = 0; var channelCount = 0; var rate = 0; var bits = 0
        var dataStart = -1L; var dataSize = 0L
        while (file.filePointer + 8 <= file.length()) {
            val chunkId = ByteArray(4); file.readFully(chunkId)
            val size = readUnsignedIntLittleEndian(file)
            val chunkStart = file.filePointer
            require(size <= file.length() - chunkStart) { "$sourceName has a truncated WAV chunk" }
            when (String(chunkId, Charsets.US_ASCII)) {
                "fmt " -> {
                    require(size >= 16) { "$sourceName has an invalid WAV format chunk" }
                    code = readUnsignedShortLittleEndian(file)
                    channelCount = readUnsignedShortLittleEndian(file)
                    rate = readUnsignedIntLittleEndian(file).toInt()
                    file.seek(chunkStart + 12)
                    readUnsignedShortLittleEndian(file)
                    bits = readUnsignedShortLittleEndian(file)
                }
                "data" -> { dataStart = chunkStart; dataSize = size }
            }
            file.seek(chunkStart + size + (size and 1L))
            if (dataStart >= 0 && code != 0) break
        }
        require(code == 1 && bits == 16 && channelCount in 1..2 && rate in 8_000..192_000 && dataStart >= 0) {
            "$sourceName must be PCM 16-bit mono/stereo WAV"
        }
        return WavFormat(channelCount, rate, dataStart, dataSize)
    }

    private fun readUnsignedShortLittleEndian(file: RandomAccessFile): Int =
        file.readUnsignedByte() or (file.readUnsignedByte() shl 8)

    private fun readUnsignedIntLittleEndian(file: RandomAccessFile): Long =
        (file.readUnsignedByte().toLong() or
            (file.readUnsignedByte().toLong() shl 8) or
            (file.readUnsignedByte().toLong() shl 16) or
            (file.readUnsignedByte().toLong() shl 24))
}
