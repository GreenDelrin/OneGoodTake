package com.socialmaster.app.audio

import android.content.Context
import java.io.File
import java.net.URI

class WorkspaceStore(context: Context) {
    private val preferences = context.getSharedPreferences("socialmaster_workspace", Context.MODE_PRIVATE)

    fun load(): Map<String, String> = WorkspaceCodec.decode(preferences.getString("snapshot", null))

    fun save(values: Map<String, String>) {
        preferences.edit().putString("snapshot", WorkspaceCodec.encode(values)).apply()
    }
}

data class BackingLibraryEntry(val path: String, val name: String, val favorite: Boolean, val lastUsedMs: Long)

fun restoreBackingLibrary(values: Map<String, String>): List<BackingLibraryEntry> {
    val count = values["backingLibrary.count"]?.toIntOrNull()?.coerceIn(0, 20) ?: 0
    return (0 until count).mapNotNull { index ->
        val prefix = "backingLibrary.$index."
        val path = values[prefix + "path"] ?: return@mapNotNull null
        val file = File(path)
        if (!file.isFile) return@mapNotNull null
        BackingLibraryEntry(
            path = path,
            name = values[prefix + "name"]?.takeIf { it.isNotBlank() } ?: file.name,
            favorite = values[prefix + "favorite"]?.toBooleanStrictOrNull() ?: false,
            lastUsedMs = values[prefix + "lastUsedMs"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        )
    }.sortedByDescending { it.lastUsedMs }
}

fun writeBackingLibrary(entries: List<BackingLibraryEntry>, values: MutableMap<String, String>) {
    val saved = entries.distinctBy { it.path }.take(20)
    values["backingLibrary.count"] = saved.size.toString()
    saved.forEachIndexed { index, entry ->
        val prefix = "backingLibrary.$index."
        values[prefix + "path"] = entry.path
        values[prefix + "name"] = entry.name
        values[prefix + "favorite"] = entry.favorite.toString()
        values[prefix + "lastUsedMs"] = entry.lastUsedMs.toString()
    }
}

fun restoreProject(values: Map<String, String>): Project {
    val savedTrimStart = values["project.trimStartMs"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val savedTrimEnd = values["project.trimEndMs"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    val project = Project(
        id = values["project.id"] ?: "p1",
        title = values["project.title"] ?: "Untitled",
        videoUri = values["project.videoUri"],
        syncOffsetMs = values["project.syncOffsetMs"]?.toIntOrNull()?.coerceIn(-200, 200) ?: 0,
        trimStartMs = if (savedTrimEnd == 0L || savedTrimEnd > savedTrimStart) savedTrimStart else 0L,
        trimEndMs = if (savedTrimEnd > savedTrimStart) savedTrimEnd else 0L
    )
    project.master.makeupGainDb = values.floatValue("master.makeupGainDb", 0f, -12f..12f)
    project.master.limiterThresholdDb = values.floatValue("master.limiterThresholdDb", -1f, -6f..0f)
    project.master.targetLufs = values.floatValue("master.targetLufs", -14f, -24f..-8f)
    project.master.truePeakCeilingDb = values.floatValue("master.truePeakCeilingDb", -1f, -6f..0f)

    val count = values["track.count"]?.toIntOrNull()?.coerceIn(0, 8) ?: 0
    for (index in 0 until count) {
        val prefix = "track.$index."
        val source = values[prefix + "sourceUri"] ?: continue
        if (fileFromUri(source)?.isFile != true) continue
        project.tracks += Track(
            id = values[prefix + "id"] ?: continue,
            name = values[prefix + "name"] ?: "Audio track",
            sourceUri = source,
            sourceOffsetMs = values[prefix + "sourceOffsetMs"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            volumeDb = values.floatValue(prefix + "volumeDb", 0f, -60f..6f),
            pan = values.floatValue(prefix + "pan", 0f, -1f..1f),
            eqLowDb = values.floatValue(prefix + "eqLowDb", 0f, -12f..12f),
            eqMidDb = values.floatValue(prefix + "eqMidDb", 0f, -12f..12f),
            eqHighDb = values.floatValue(prefix + "eqHighDb", 0f, -12f..12f),
            reverbSend = values.floatValue(prefix + "reverbSend", 0f, 0f..1f),
            compressorAmount = values.floatValue(prefix + "compressorAmount", 0f, 0f..1f),
            muted = values[prefix + "muted"]?.toBooleanStrictOrNull() ?: false,
            solo = values[prefix + "solo"]?.toBooleanStrictOrNull() ?: false
        )
    }
    return project
}

fun Project.writeTo(values: MutableMap<String, String>) {
    values["project.id"] = id
    values["project.title"] = title
    videoUri?.let { values["project.videoUri"] = it }
    values["project.syncOffsetMs"] = syncOffsetMs.toString()
    values["project.trimStartMs"] = trimStartMs.toString()
    values["project.trimEndMs"] = trimEndMs.toString()
    values["master.makeupGainDb"] = master.makeupGainDb.toString()
    values["master.limiterThresholdDb"] = master.limiterThresholdDb.toString()
    values["master.targetLufs"] = master.targetLufs.toString()
    values["master.truePeakCeilingDb"] = master.truePeakCeilingDb.toString()
    values["track.count"] = tracks.size.toString()
    tracks.forEachIndexed { index, track ->
        val prefix = "track.$index."
        values[prefix + "id"] = track.id
        values[prefix + "name"] = track.name
        values[prefix + "sourceUri"] = track.sourceUri
        values[prefix + "sourceOffsetMs"] = track.sourceOffsetMs.toString()
        values[prefix + "volumeDb"] = track.volumeDb.toString()
        values[prefix + "pan"] = track.pan.toString()
        values[prefix + "eqLowDb"] = track.eqLowDb.toString()
        values[prefix + "eqMidDb"] = track.eqMidDb.toString()
        values[prefix + "eqHighDb"] = track.eqHighDb.toString()
        values[prefix + "reverbSend"] = track.reverbSend.toString()
        values[prefix + "compressorAmount"] = track.compressorAmount.toString()
        values[prefix + "muted"] = track.muted.toString()
        values[prefix + "solo"] = track.solo.toString()
    }
}

fun fileFromUri(value: String): File? = try {
    when {
        value.startsWith("file://") -> File(value.removePrefix("file://"))
        value.startsWith("file:") -> File(URI(value))
        else -> File(value)
    }
} catch (_: Exception) { null }

private fun Map<String, String>.floatValue(key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
    this[key]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(range) ?: default
