package com.shilapi.xcertplay.network

import android.util.AtomicFile
import java.io.*
import java.security.MessageDigest

/** Shell-private, bounded, versioned journal; never use app external storage or diagnostic export. */
internal class HotspotJoinJournal<C>(directory: File, private val firmware: String,
    private val codec: Codec<C>, private val syncFile: (FileOutputStream) -> Unit = { it.fd.sync() },
    // AtomicFile commits with rename(2), which a host cannot do over an existing file (Windows):
    // the previous generation then stays in place while AtomicFile only logs the failure, and the
    // commit check in save() detects exactly that. A test whose host behaves that way may pass a
    // replacing move used only for this recovery; production passes nothing, so an uncommitted
    // write is still never reported as durable.
    private val replaceUncommittedFile: ((File, File) -> Boolean)? = null,
    private val syncDirectory: () -> Unit = {}) : HotspotJoinTransaction.Store<C> {
    interface Codec<C> { fun encode(config: C): ByteArray; fun decode(bytes: ByteArray): C }
    class FirmwareChanged : IOException()
    private val file = AtomicFile(File(directory, "journal"))

    override fun load(): HotspotJoinTransaction.Record<C>? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.openRead().use { it.readBytesBounded(MAX_BYTES) }
        if (bytes.size < 32) throw IOException()
        val body = bytes.copyOf(bytes.size - 32)
        if (!MessageDigest.isEqual(digest(body), bytes.copyOfRange(body.size, bytes.size))) throw IOException()
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException()
            // Check BEFORE unparcelling: Android parcels cannot be safely migrated across firmware.
            if (input.readUTF() != firmware) throw FirmwareChanged()
            val token = input.readUTF()
            val stage = HotspotJoinTransaction.Stage.entries.getOrNull(input.readInt()) ?: throw IOException()
            fun snapshot(): C {
                val size = input.readInt()
                if (size !in 1..MAX_SNAPSHOT || size > input.available()) throw IOException()
                return codec.decode(ByteArray(size).also { input.readFully(it) })
            }
            val original = snapshot(); val target = snapshot()
            if (input.available() != 0) throw IOException()
            HotspotJoinTransaction.Record(token, firmware, original, target, stage)
        }
    }

    override fun save(record: HotspotJoinTransaction.Record<C>) {
        if (record.firmware != firmware) throw FirmwareChanged()
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC); output.writeInt(VERSION); output.writeUTF(firmware)
            output.writeUTF(record.token); output.writeInt(record.stage.ordinal)
            for (config in listOf(record.original, record.target)) {
                val bytes = codec.encode(config)
                if (bytes.size !in 1..MAX_SNAPSHOT) throw IOException()
                output.writeInt(bytes.size); output.write(bytes)
            }
        }
        val bytes = buffer.toByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes); stream.write(digest(bytes))
            syncFile(stream) // AtomicFile can suppress fsync failure; require a throwing sync first.
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
        syncDirectory() // Persist the rename itself, not only the snapshot file contents.
        // AtomicFile commits with rename(2), which a host cannot do over an existing file
        // (Windows): it then only logs the failure and leaves its temporary file behind, and the
        // next openRead() deletes that temp file. So detect the uncommitted write HERE, and either
        // recover with the caller's replacement or fall through to the verification below.
        val uncommitted = File(file.baseFile.path + ".new")
        if (uncommitted.exists()) {
            val replace = replaceUncommittedFile ?: throw IOException()
            if (!replace(uncommitted, file.baseFile)) throw IOException()
            syncDirectory()
        }
        // Verification is mandatory even if an OEM AtomicFile implementation silently fails.
        val saved = load() ?: throw IOException()
        if (!sameGeneration(saved, record)) throw IOException()
    }

    private fun sameGeneration(saved: HotspotJoinTransaction.Record<C>,
        record: HotspotJoinTransaction.Record<C>): Boolean =
        saved.token == record.token && saved.stage == record.stage &&
            saved.original == record.original && saved.target == record.target

    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val size = read(buffer)
            if (size < 0) break
            if (out.size() + size > limit) throw IOException()
            out.write(buffer, 0, size)
        }
        return out.toByteArray()
    }
    companion object {
        private const val MAGIC = 0x44485031
        private const val VERSION = 1
        private const val MAX_SNAPSHOT = 262144
        private const val MAX_BYTES = MAX_SNAPSHOT * 2 + 8192
        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
