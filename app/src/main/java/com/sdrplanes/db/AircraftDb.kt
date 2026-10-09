package com.sdrplanes.db

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** What the offline database knows about an ICAO address. */
data class AircraftInfo(
    val registration: String? = null,
    /** ICAO type designator, e.g. "A320". */
    val typeCode: String? = null,
    /** Model, e.g. "AIRBUS A-320". */
    val description: String? = null,
    val year: Int? = null,
    val military: Boolean = false,
    /** Flagged "interesting" by the database maintainers (government, special liveries, ...). */
    val interesting: Boolean = false,
    /** Country of registration from the ICAO address block (not the operator). */
    val country: String? = null,
    /** ISO 3166-1 alpha-2, e.g. "DE". */
    val countryCode: String? = null,
)

/**
 * Offline aircraft database built by `tools/build_aircraft_db.py` from tar1090-db (Mictronics)
 * plus the ICAO address allocation table. Records are fixed-size and sorted by address, so a
 * lookup is a binary search on the memory-mapped file; nothing is loaded onto the heap except
 * the small type/description/country tables.
 */
class AircraftDb private constructor(private val buf: ByteBuffer) {
    val source: String
    private val types: Array<String>
    private val descs: Array<String>
    private val rangeStart: IntArray
    private val rangeEnd: IntArray
    private val rangeCountry: Array<String>
    private val rangeCode: Array<String>
    private val recordsAt: Int
    val size: Int

    init {
        val b = buf.duplicate()
        val magic = ByteArray(4).also { b.get(it) }
        if (String(magic, Charsets.US_ASCII) != "SPAC") throw IOException("Not an aircraft database")
        val version = b.short.toInt()
        if (version != VERSION) throw IOException("Unsupported aircraft database version $version")
        source = b.str()
        types = Array(b.short.toInt() and 0xFFFF) { b.str() }
        descs = Array(b.short.toInt() and 0xFFFF) { b.str() }
        val n = b.short.toInt() and 0xFFFF
        rangeStart = IntArray(n)
        rangeEnd = IntArray(n)
        rangeCountry = Array(n) { "" }
        rangeCode = Array(n) { "" }
        for (i in 0 until n) {
            rangeStart[i] = b.u24()
            rangeEnd[i] = b.u24()
            rangeCountry[i] = b.str()
            rangeCode[i] = String(ByteArray(2).also { b.get(it) }, Charsets.US_ASCII)
        }
        size = b.int
        recordsAt = b.position()
        if (recordsAt.toLong() + size.toLong() * RECORD > buf.limit()) throw IOException("Aircraft database truncated")
    }

    /** Database entry and/or registration country for [icao]; null if nothing is known. */
    fun lookup(icao: Int): AircraftInfo? {
        val r = findRange(icao)
        val country = r?.let { rangeCountry[it] }
        val code = r?.let { rangeCode[it] }
        val i = findRecord(icao)
        if (i < 0) return if (country != null) AircraftInfo(country = country, countryCode = code) else null
        val at = recordsAt + i * RECORD
        val type = buf.getShort(at + 3).toInt() and 0xFFFF
        val desc = buf.getShort(at + 5).toInt() and 0xFFFF
        val flags = buf.get(at + 7).toInt()
        val year = buf.get(at + 8).toInt() and 0xFF
        val regBytes = ByteArray(REG_LEN)
        for (k in 0 until REG_LEN) regBytes[k] = buf.get(at + 9 + k)
        val reg = String(regBytes, Charsets.US_ASCII).trimEnd('\u0000').trim()
        return AircraftInfo(
            registration = reg.ifEmpty { null },
            typeCode = types[type].ifEmpty { null }?.takeIf { it != "ZZZZ" },
            description = descs[desc].ifEmpty { null },
            year = if (year == 0) null else 1900 + year,
            military = flags and 1 != 0,
            interesting = flags and 2 != 0,
            country = country,
            countryCode = code,
        )
    }

    private fun addressAt(i: Int): Int {
        val at = recordsAt + i * RECORD
        return ((buf.get(at).toInt() and 0xFF) shl 16) or ((buf.get(at + 1).toInt() and 0xFF) shl 8) or
            (buf.get(at + 2).toInt() and 0xFF)
    }

    private fun findRecord(icao: Int): Int {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val a = addressAt(mid)
            when {
                a < icao -> lo = mid + 1
                a > icao -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun findRange(icao: Int): Int? {
        // Last range starting at or below the address; ranges don't overlap.
        var lo = 0
        var hi = rangeStart.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (rangeStart[mid] <= icao) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (best >= 0 && icao <= rangeEnd[best]) best else null
    }

    companion object {
        private const val VERSION = 1
        private const val REG_LEN = 10
        private const val RECORD = 3 + 2 + 2 + 1 + 1 + REG_LEN
        /** Built as aircraft.bin.gz; the Android build un-gzips .gz assets (still compressed in the APK). */
        private const val ASSET = "aircraft.bin"

        @Volatile private var instance: AircraftDb? = null

        /** Parses an uncompressed database (tests, or an already mapped file). */
        fun fromBuffer(buf: ByteBuffer) = AircraftDb(buf)

        /**
         * Opens the bundled database, copying it into private storage on first use or after
         * an app update (~11 MB, about a second), then memory-maps it. Blocking: call off the
         * main thread.
         */
        fun open(context: Context): AircraftDb {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val app = context.applicationContext
                val file = File(app.filesDir, "aircraft.bin")
                val stamp = File(app.filesDir, "aircraft.bin.stamp")
                val version = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
                if (!file.exists() || !stamp.exists() || stamp.readText() != version) {
                    val tmp = File(app.filesDir, "aircraft.bin.tmp")
                    app.assets.open(ASSET).buffered().use { input ->
                        tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
                    }
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        if (!tmp.renameTo(file)) throw IOException("Cannot install aircraft database")
                    }
                    stamp.writeText(version)
                }
                val mapped = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
                return AircraftDb(mapped).also { instance = it }
            }
        }

        private fun ByteBuffer.str(): String {
            val n = get().toInt() and 0xFF
            val bytes = ByteArray(n).also { get(it) }
            return String(bytes, Charsets.UTF_8)
        }

        private fun ByteBuffer.u24(): Int =
            ((get().toInt() and 0xFF) shl 16) or ((get().toInt() and 0xFF) shl 8) or (get().toInt() and 0xFF)
    }
}
