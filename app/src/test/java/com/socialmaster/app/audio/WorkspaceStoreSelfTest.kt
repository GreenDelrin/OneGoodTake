package com.socialmaster.app.audio

import java.io.File

object WorkspaceStoreSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = File.createTempFile("socialmaster-source", ".wav")
        try {
            val project = Project("session-2", "Coffee set", syncOffsetMs = 120, trimStartMs = 750L, trimEndMs = 8_250L)
            project.master.makeupGainDb = 3.5f
            project.master.targetLufs = -12f
            project.tracks += Track(
                id = "backing", name = "Song = one", sourceUri = source.toURI().toString(),
                sourceOffsetMs = 250, volumeDb = -4f, pan = 0.2f,
                eqLowDb = 2f, eqMidDb = -1f, eqHighDb = 3f,
                reverbSend = 0.25f, compressorAmount = 0.5f, muted = true, solo = false
            )
            val encoded = mutableMapOf<String, String>().also(project::writeTo)
            val restored = restoreProject(WorkspaceCodec.decode(WorkspaceCodec.encode(encoded)))
            check(restored.id == "session-2" && restored.title == "Coffee set")
            check(restored.syncOffsetMs == 120 && restored.master.makeupGainDb == 3.5f)
            check(restored.trimStartMs == 750L && restored.trimEndMs == 8_250L)
            check(restored.master.targetLufs == -12f && restored.tracks.single().name == "Song = one")
            check(restored.tracks.single().sourceUri == source.toURI().toString())
            check(restored.tracks.single().muted && restored.tracks.single().compressorAmount == 0.5f)

            val missingSource = mapOf(
                "track.count" to "1", "track.0.id" to "missing", "track.0.sourceUri" to "file:///not/a/real/file.wav"
            )
            check(restoreProject(missingSource).tracks.isEmpty())
            println("WorkspaceStoreSelfTest passed")
        } finally {
            source.delete()
        }
    }
}
