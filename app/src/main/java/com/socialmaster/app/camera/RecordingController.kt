package com.socialmaster.app.camera

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

class RecordingController(private val context: Context) {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var provider: ProcessCameraProvider? = null
    private var activeRecording: androidx.camera.video.Recording? = null
    private var lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null
    private var previewView: PreviewView? = null

    var isRecording: Boolean = false
        private set
    var useFrontCamera: Boolean = true

    /** Bind once; provider resolved async then selector applied. */
    fun bind(owner: androidx.lifecycle.LifecycleOwner, view: PreviewView) {
        lifecycleOwner = owner
        previewView = view
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = future.get()
            applyCamera()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun applyCamera() {
        val p = provider ?: return
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return
        val recorder = Recorder.Builder().build()
        videoCapture = VideoCapture.withOutput(recorder)
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(view.surfaceProvider)
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            p.unbindAll()
            p.bindToLifecycle(owner, selector, preview, videoCapture)
        } catch (_: Exception) { }
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

    fun toggleCamera() {
        useFrontCamera = !useFrontCamera
        applyCamera()
    }
}
