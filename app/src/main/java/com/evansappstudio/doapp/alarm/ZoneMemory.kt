package com.evansappstudio.doapp.alarm

import android.content.Context
import java.io.File
import java.time.ZoneId

/**
 * Last zone whose wall time is stored on Room rows, and the zone the mirror
 * instants were last rewritten for. Device-protected so a timezone change
 * before unlock can still move mirror dues.
 */
class ZoneMemory(context: Context) {
    private val file: File = File(
        context.createDeviceProtectedStorageContext().filesDir,
        FILE_NAME,
    ).also { it.parentFile?.mkdirs() }

    private val lock = Any()

    fun roomZone(): ZoneId? = read(KEY_ROOM)

    fun mirrorZone(): ZoneId? = read(KEY_MIRROR)

    fun setRoomZone(zone: ZoneId) = write(KEY_ROOM, zone)

    fun setMirrorZone(zone: ZoneId) = write(KEY_MIRROR, zone)

    private fun read(key: String): ZoneId? = synchronized(lock) {
        if (!file.exists()) return null
        val line = file.readLines().firstOrNull { it.startsWith("$key=") } ?: return null
        val id = line.substringAfter("=", "")
        if (id.isBlank()) return null
        runCatching { ZoneId.of(id) }.getOrNull()
    }

    private fun write(key: String, zone: ZoneId) = synchronized(lock) {
        val kept = if (file.exists()) {
            file.readLines().filter { it.isNotBlank() && !it.startsWith("$key=") }
        } else {
            emptyList()
        }
        file.writeText((kept + "$key=${zone.id}").joinToString("\n") + "\n")
    }

    companion object {
        private const val FILE_NAME = "zone-memory.txt"
        private const val KEY_ROOM = "room"
        private const val KEY_MIRROR = "mirror"
    }
}
