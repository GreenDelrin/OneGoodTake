package com.onegoodtake.app.audio

/**
 * One-button mastering for social media. Measures integrated LUFS of the
 * mixed bus, applies gain to hit targetLufs, then brickwall-limits at
 * truePeakCeilingDb. Returns the applied gain in dB.
 *
 * TODO: implement with a real K-weighted LUFS meter (ITU-R BS.1770) and
 * a lookahead limiter. For now returns a placeholder gain.
 */
object AutoMaster {
    fun apply(project: Project, measuredLufs: Float): Float {
        val gain = project.master.targetLufs - measuredLufs
        project.master.makeupGainDb = gain.coerceIn(-12f, 12f)
        return project.master.makeupGainDb
    }
}