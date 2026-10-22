package com.onegoodtake.app.audio

import java.io.File

object BackingLibrarySelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = File.createTempFile("backing-library", ".wav")
        val favoriteFile = File.createTempFile("backing-favorite", ".wav")
        try {
            val recent = BackingLibraryEntry(file.absolutePath, "Song | live", false, 20L)
            val favorite = BackingLibraryEntry(favoriteFile.absolutePath, "Favorite", true, 10L)
            val values = mutableMapOf<String, String>()
            writeBackingLibrary(listOf(recent, favorite), values)
            val restored = restoreBackingLibrary(WorkspaceCodec.decode(WorkspaceCodec.encode(values)))
            check(restored.size == 2)
            check(restored[0].name == "Song | live" && !restored[0].favorite)
            check(restored[1].name == "Favorite" && restored[1].favorite)
            check(restoreBackingLibrary(mapOf("backingLibrary.count" to "1", "backingLibrary.0.path" to "C:/not/here.wav")).isEmpty())
            println("BackingLibrarySelfTest passed")
        } finally { file.delete(); favoriteFile.delete() }
    }
}
