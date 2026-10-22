package com.onegoodtake.app.audio

import android.content.Context
import java.io.File

/**
 * A bundled, licence-clean backing track so a new install has something to play along
 * with immediately, without the user having to pick a file first.
 *
 * The source ships as a compressed file (MP3) in res/raw so the Play Store download
 * stays small; it is decoded to a 16-bit WAV in the app's private storage on first use,
 * the same format the live mix and the offline mixer read directly.
 */
object BundledDemo {
    private const val DEMO_NAME = "Demo backing track"

    /** Decodes the bundled demo to a WAV in the app's files dir (once) and returns it. */
    fun ensureWav(context: Context): File? {
        val out = File(context.filesDir, "demo_backing.wav")
        if (out.isFile && out.length() > 0) return out
        return try {
            val uri = android.net.Uri.parse("android.resource://${context.packageName}/${com.onegoodtake.app.R.raw.demo_backing}")
            val ok = AudioDecoder.decodeToWav(context, uri, out)
            if (ok) out else null
        } catch (_: Exception) {
            null
        }
    }

    fun isDemo(file: File?): Boolean = file?.name == "demo_backing.wav"
}
