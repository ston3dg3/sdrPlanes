package com.sdrplanes.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/** Reads the bundled asset (unit tests run with the module directory as working directory). */
class AircraftDbTest {
    private val db by lazy {
        val bytes = GZIPInputStream(File("src/main/assets/aircraft.bin.gz").inputStream()).use { it.readBytes() }
        AircraftDb.fromBuffer(ByteBuffer.wrap(bytes))
    }

    @Test
    fun hasAircraft() {
        assertTrue("size ${db.size}", db.size > 400_000)
    }

    @Test
    fun knownAirliner() {
        val a = db.lookup(0x3C656F)!!
        assertEquals("D-AIKO", a.registration)
        assertEquals("A333", a.typeCode)
        assertEquals("Germany", a.country)
        assertEquals("DE", a.countryCode)
        assertFalse(a.military)
    }

    @Test
    fun militaryFlag() {
        val a = db.lookup(0x3F402A)!!
        assertEquals("NH90", a.typeCode)
        assertTrue(a.military)
    }

    @Test
    fun unknownAddressStillHasCountry() {
        // Inside the German block but (almost certainly) unassigned.
        val a = db.lookup(0x3FFFFF)
        assertEquals("Germany", a?.country)
        assertNull(a?.registration)
    }

    @Test
    fun outsideAllBlocks() {
        assertNull(db.lookup(0x000000))
    }

    @Test
    fun firstAndLastRecordsAreReachable() {
        // Binary search edges: the lowest and highest addresses in the database.
        assertEquals("BALL", db.lookup(0x0000BA)?.typeCode)
        assertEquals("96-26712", db.lookup(0xFEDD2F)?.registration)
    }
}
