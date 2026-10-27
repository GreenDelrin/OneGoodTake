package com.onegoodtake.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

/** One physical camera of the device. */
class CameraOption(
    val id: String,
    val label: String,
    val isFront: Boolean,
    val sensorOrientation: Int
)

class RecordingController(private val context: Context) {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var provider: ProcessCameraProvider? = null
    private var activeRecording: androidx.camera.video.Recording? = null
    private var lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null
    private var previewView: PreviewView? = null

    var isRecording: Boolean = false
        private set

    /** Every camera the device exposes, selfie lenses first. */
    var cameras: List<CameraOption> = emptyList()
        private set
    var cameraIndex: Int = 0
        private set

    var videoQuality: VideoQuality = VideoQuality.FHD_1080
    var supportedVideoQualities: List<VideoQuality> = VideoQuality.values().toList()
        private set
    var onSupportedQualitiesChanged: ((List<VideoQuality>) -> Unit)? = null

    private var requestedIndex: Int = -1
    private var preferFront: Boolean = true

    /** Reports the label of the camera in use so the UI can show it. */
    var onCameraChanged: ((String) -> Unit)? = null

    init {
        // The setup wizard needs the camera list before the preview is bound.
        cameras = listCameras()
    }

    /** Rotation the exported video needs so the picture is upright. */
    var rotationHint: Int = 90
        private set

    /** Bind once; provider resolved async then selector applied. */
    fun bind(owner: androidx.lifecycle.LifecycleOwner, view: PreviewView) {
        lifecycleOwner = owner
        previewView = view
        if (cameras.isEmpty()) cameras = listCameras()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = future.get()
            applySelection()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Pick a camera by index; -1 means "use the preferred facing". */
    fun selectCamera(index: Int) {
        requestedIndex = index
        applySelection()
    }

    /** Pick the first camera with this facing, used when nothing was chosen yet. */
    fun usePreferredFacing(front: Boolean) {
        preferFront = front
        requestedIndex = -1
        applySelection()
    }

    fun updateVideoQuality(q: VideoQuality) {
        videoQuality = q
        applyCamera()
    }

    /** Move to the next camera: rear lenses first, then the selfie ones. */
    fun nextCamera() = selectCamera(if (cameras.isEmpty()) 0 else (cameraIndex + 1) % cameras.size)

    fun currentLabel(): String = cameras.getOrNull(cameraIndex)?.label ?: "CAMERA"

    private fun applySelection() {
        if (cameras.isEmpty()) return
        cameraIndex = when {
            requestedIndex >= 0 -> requestedIndex.coerceIn(0, cameras.lastIndex)
            preferFront -> cameras.indexOfFirst { it.isFront }.takeIf { it >= 0 } ?: 0
            else -> cameras.indexOfFirst { !it.isFront }.takeIf { it >= 0 } ?: 0
        }
        applyCamera()
        onCameraChanged?.invoke(currentLabel())
    }

    private fun listCameras(): List<CameraOption> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        val found = mutableListOf<CameraOption>()
        for (id in manager.cameraIdList) {
            val characteristics = try { manager.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val kind = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_BACK -> "REAR"
                else -> "OTHER"
            }
            found += CameraOption(id, kind, facing == CameraCharacteristics.LENS_FACING_FRONT, orientation)
        }
        val ordered = found.sortedBy { if (it.isFront) 0 else 1 }
        val totals = ordered.groupingBy { it.label }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return ordered.map { option ->
            val number = seen.merge(option.label, 1, Int::plus)!!
            val label = if ((totals[option.label] ?: 0) > 1) "${option.label} $number" else option.label
            CameraOption(option.id, label, option.isFront, option.sensorOrientation)
        }
    }

    private fun applyCamera() {
        val p = provider ?: return
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return
        val option = cameras.getOrNull(cameraIndex) ?: return
        val qualitySelector = QualitySelector.from(
            videoQuality.cameraQuality,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setAspectRatio(AspectRatio.RATIO_16_9)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(displayRotationConstant())
            .build()
        preview.setSurfaceProvider(view.surfaceProvider)
        try {
            p.unbindAll()
            p.bindToLifecycle(owner, selectorFor(option.id), preview, videoCapture)
            rotationHint = computeRotation(option)
            detectSupportedQualities(p, option.id)
        } catch (_: Exception) { }
    }

    private fun detectSupportedQualities(provider: ProcessCameraProvider, cameraId: String) {
        val cameraInfo = provider.availableCameraInfos.firstOrNull { info ->
            runCatching { Camera2CameraInfo.from(info).cameraId == cameraId }.getOrDefault(false)
        } ?: return
        val supported = runCatching { QualitySelector.getSupportedQualities(cameraInfo) }.getOrNull() ?: return
        val mapped = VideoQuality.values().filter { vq -> vq.cameraQuality in supported }
        if (mapped.isEmpty()) return
        supportedVideoQualities = mapped
        onSupportedQualitiesChanged?.invoke(mapped)
        // If current selection isn't supported, fall back to highest supported
        if (videoQuality !in mapped) {
            videoQuality = mapped.maxByOrNull { it.ordinal } ?: mapped.last()
            onSupportedQualitiesChanged?.invoke(mapped)
        }
    }

    private fun selectorFor(id: String): CameraSelector = CameraSelector.Builder()
        .addCameraFilter { infos ->
            infos.filter { info -> runCatching { Camera2CameraInfo.from(info).cameraId == id }.getOrDefault(false) }
        }
        .build()

    private fun computeRotation(option: CameraOption): Int {
        val display = displayRotationDegrees()
        return if (option.isFront) (option.sensorOrientation + display) % 360
        else (option.sensorOrientation - display + 360) % 360
    }

    @Suppress("DEPRECATION")
    private fun displayRotationDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display?.rotation
        else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** The display rotation as Surface.ROTATION_*, which is what CameraX expects. */
    @Suppress("DEPRECATION")
    private fun displayRotationConstant(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display?.rotation
        else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        return rotation ?: Surface.ROTATION_0
    }

    fun start(outputFile: File, onEvent: (VideoRecordEvent) -> Unit): Uri {
        val recorder = videoCapture!!.output
        val outputOpts = FileOutputOptions.Builder(outputFile).build()
        activeRecording = recorder.prepareRecording(context, outputOpts)
            .start(ContextCompat.getMainExecutor(context), onEvent)
        isRecording = true
        return Uri.fromFile(outputFile)
    }

    fun stop() {
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
    }
}
