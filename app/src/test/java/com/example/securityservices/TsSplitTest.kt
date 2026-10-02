package com.example.securityservices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Builds tiny synthetic MPEG-2 TS streams and checks that [TsSplit] cuts them so every part
 * begins on the PAT packet in front of an H.264 keyframe - the property whose absence makes
 * later email parts play sound with no picture.
 */
class TsSplitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** One 188-byte TS packet: sync byte, PID, payload-unit-start flag, payload, 0xFF stuffing. */
    private fun packet(pid: Int, pusi: Boolean, payload: ByteArray): ByteArray {
        require(payload.size <= 184)
        val p = ByteArray(188)
        p[0] = 0x47
        p[1] = ((if (pusi) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x10 // payload only, no adaptation field
        payload.copyInto(p, 4)
        for (i in 4 + payload.size until 188) p[i] = 0xFF.toByte()
        return p
    }

    /** PAT (table_id 0x00) announcing one program on PMT pid 0x200. */
    private fun pat() = byteArrayOf(
        0x00,                    // pointer_field
        0x00,                    // table_id
        0xB0.toByte(), 0x0D,     // section syntax, section_length = 13
        0x00, 0x01,              // transport_stream_id
        0xC1.toByte(),           // version
        0x00, 0x00,              // section_number / last_section_number
        0x00, 0x01,              // program_number
        0xE2.toByte(), 0x00,     // reserved + PMT pid 0x200
        0, 0, 0, 0               // CRC (TsSplit does not check it)
    )

    /** PMT (table_id 0x02) with one H.264 (0x1B) stream on elementary pid 0x101. */
    private fun pmt() = byteArrayOf(
        0x00,                    // pointer_field
        0x02,                    // table_id
        0xB0.toByte(), 0x12,     // section syntax, section_length = 18
        0x00, 0x01,              // program_number
        0xC1.toByte(),           // version
        0x00, 0x00,              // section numbers
        0xE1.toByte(), 0x00,     // reserved + PCR pid 0x100
        0xF0.toByte(), 0x00,     // reserved + program_info_length = 0
        0x1B,                    // stream_type: H.264
        0xE1.toByte(), 0x01,     // reserved + elementary pid 0x101
        0xF0.toByte(), 0x00,     // ES info length 0
        0, 0, 0, 0               // CRC (not checked)
    )

    private fun pesHeader() = byteArrayOf(
        0x00, 0x00, 0x01,        // PES start code
        0xE0.toByte(),           // stream_id: video 0
        0x00, 0x00,              // PES packet length: 0 (unbounded, as in TS)
        0x80.toByte(), 0x00, 0x00 // flags + header_data_length = 0
    )

    /** SPS + PPS + IDR slice in one PES (the usual MediaRecorder layout). */
    private fun idrAccessUnit() = pesHeader() + byteArrayOf(
        0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E,                    // SPS
        0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte(),   // PPS
        0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x21                   // IDR slice
    )

    /** SPS + PPS only; the IDR follows in the next (non-start) packet of the same PES. */
    private fun spsPpsOnly() = pesHeader() + byteArrayOf(
        0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E,
        0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte()
    )

    private fun idrContinuation() = byteArrayOf(
        0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x21
    )

    /** A non-IDR P (skip) slice - never a split point. */
    private fun pSliceAccessUnit() = pesHeader() + byteArrayOf(
        0x00, 0x00, 0x00, 0x01, 0x41, 0x9A.toByte(), 0x10
    )

    private fun writeStream(name: String, packets: List<ByteArray>): java.io.File {
        val f = tmp.newFile(name)
        val out = java.io.ByteArrayOutputStream(packets.size * 188)
        packets.forEach { out.write(it) }
        f.writeBytes(out.toByteArray())
        return f
    }

    @Test
    fun cutsLandOnThePatPacketBeforeEachKeyframe() {
        // Four groups of [PAT, PMT, IDR, P, P] = 5 packets each; two groups per part.
        val group = listOf(
            packet(0x000, true, pat()),
            packet(0x200, true, pmt()),
            packet(0x101, true, idrAccessUnit()),
            packet(0x101, false, pSliceAccessUnit()),
            packet(0x101, false, pSliceAccessUnit())
        )
        val f = writeStream("video.ts", group + group + group + group)
        val groupBytes = 5L * 188L
        val cuts = TsSplit.cuts(f, 2L * groupBytes)
        assertEquals(listOf(2L * groupBytes), cuts)
        // The cut must be a PAT packet: sync byte 0x47 and pid 0x000.
        val bytes = f.readBytes()
        val at = cuts!![0].toInt()
        assertEquals(0x47.toByte(), bytes[at])
        assertEquals(0, bytes[at + 1].toInt() and 0x1F)
    }

    @Test
    fun idrContinuationStillSplitsAtThePatBeforeItsPes() {
        // IDR lives in the second packet of the PES; the cut must still be the PAT (offset 752),
        // not the PES start (1128) or the continuation packet (1316).
        val group = listOf(
            packet(0x000, true, pat()),
            packet(0x200, true, pmt()),
            packet(0x101, true, spsPpsOnly()),
            packet(0x101, false, idrContinuation())
        )
        val f = writeStream("cont.ts", group + group)
        assertEquals(listOf(752L), TsSplit.cuts(f, 752L + 100L))
    }

    @Test
    fun withoutKeyframesPartsStayWholeAndPacketAligned() {
        val packets = (1..10).map { packet(0x101, it == 1, pSliceAccessUnit()) }
        val f = writeStream("nokf.ts", packets)
        // No PAT/PMT and no IDR: fall back to plain cuts of at most 4*188+50 = 802 bytes.
        assertEquals(listOf(752L, 1504L), TsSplit.cuts(f, 4L * 188L + 50L))
    }

    @Test
    fun nonTransportStreamIsRejected() {
        val f = tmp.newFile("junk.ts")
        f.writeBytes(ByteArray(2000) { (it % 251).toByte() }) // first byte is 0x00, not 0x47
        assertNull(TsSplit.cuts(f, 1024L))
    }

    @Test
    fun fileThatFitsOnePartNeedsNoCuts() {
        val group = listOf(
            packet(0x000, true, pat()),
            packet(0x200, true, pmt()),
            packet(0x101, true, idrAccessUnit()),
            packet(0x101, false, pSliceAccessUnit()),
            packet(0x101, false, pSliceAccessUnit())
        )
        val f = writeStream("small.ts", group) // 940 bytes, far below the 10-packet limit
        assertEquals(emptyList<Long>(), TsSplit.cuts(f, 10L * 188L))
    }
}
