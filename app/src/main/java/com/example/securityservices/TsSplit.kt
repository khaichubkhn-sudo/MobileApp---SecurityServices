package com.example.securityservices

import java.io.File
import java.io.RandomAccessFile

/**
 * Works out where an MPEG-2 transport stream (.ts) may be cut so that every resulting part still
 * plays on its own - with picture, not only sound.
 *
 * A plain byte cut, even one that lands on a 188-byte packet border, starts a part in the middle of
 * a group of pictures: AAC audio frames decode on their own, so sound plays, but an H.264 decoder
 * has no stream tables (PAT/PMT) and no keyframe to start from, so the picture stays black.
 *
 * Each cut returned here lands on the PAT packet in front of an IDR keyframe - the same situation
 * as the very start of the recording - so a part opened on its own immediately gets its tables,
 * the SPS/PPS parameter sets and a keyframe: picture AND sound.
 *
 * [cuts] returns null when the file is not a parseable transport stream; the caller then falls
 * back to plain packet-border cutting.
 */
object TsSplit {

    /** MPEG-2 TS packet size in bytes. */
    private const val PACKET = 188

    /**
     * How far before a keyframe the most recent PAT/PMT packet may sit and still be treated as
     * belonging to that keyframe. PSI tables repeat several times per second, so this window
     * (~200 KB, a few hundred milliseconds at 5 Mbps) keeps the cut close to the keyframe.
     */
    private const val PSI_RECENT_BYTES = 200L * 1024L

    /** Read buffer for the single pass over the file. */
    private const val READ_BYTES = 512 * 1024

    /**
     * Returns the interior cut offsets for parts of at most [maxPartBytes] bytes: ascending,
     * strictly between 0 and the file length, every one on a 188-byte packet border and on the
     * PAT packet before an H.264 keyframe. Returns null when [file] is not a parseable TS.
     */
    fun cuts(file: File, maxPartBytes: Long): List<Long>? {
        if (maxPartBytes < PACKET * 2L) return null
        return try {
            val total = file.length()
            if (total <= PACKET * 4L) return null
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(PACKET * 3)
                raf.readFully(head)
                for (i in 0 until 2) if (head[i * PACKET] != 0x47.toByte()) return null
                val candidates = keyFramePackets(raf) ?: return null
                chooseCuts(candidates, total, maxPartBytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Single pass over the stream. Returns the packet offsets where a part may start - the PAT
     * packet preceding each H.264 keyframe - or null when a packet does not start with the 0x47
     * sync byte (i.e. the file is not a transport stream).
     */
    private fun keyFramePackets(raf: RandomAccessFile): List<Long>? {
        val candidates = ArrayList<Long>()
        var pmtPid = -1
        var videoPid = -1
        var lastPatAt = -1L
        var lastPmtAt = -1L
        var pesStartAt = -1L
        val buf = ByteArray(READ_BYTES)
        var base = 0L       // absolute file offset of buf[0]
        var fill = 0        // valid bytes in buf
        raf.seek(0)
        while (true) {
            val r = raf.read(buf, fill, buf.size - fill)
            val avail = if (r > 0) fill + r else fill
            var pos = 0
            var malformed = false
            while (pos + PACKET <= avail) {
                val abs = base + pos.toLong()
                if (buf[pos] != 0x47.toByte()) {
                    malformed = true
                    break
                }
                val b1 = buf[pos + 1].toInt()
                val pid = ((b1 and 0x1F) shl 8) or (buf[pos + 2].toInt() and 0xFF)
                val pusi = (b1 and 0x40) != 0
                val afc = (buf[pos + 3].toInt() shr 4) and 0x03
                var p = 4
                if (afc == 2 || afc == 3) p += 1 + (buf[pos + p].toInt() and 0xFF)
                if (afc != 0 && p < PACKET) {
                    when {
                        // PAT: learn the PMT pid (single-section packets, as the spec requires).
                        pid == 0 && pusi -> {
                            lastPatAt = abs
                            val q = p + 1 + (buf[pos + p].toInt() and 0xFF)   // pointer_field
                            if (q + 12 <= PACKET && (buf[pos + q].toInt() and 0xFF) == 0x00) {
                                val program = ((buf[pos + q + 8].toInt() and 0xFF) shl 8) or
                                    (buf[pos + q + 9].toInt() and 0xFF)
                                if (program != 0) {
                                    pmtPid = ((buf[pos + q + 10].toInt() and 0x1F) shl 8) or
                                        (buf[pos + q + 11].toInt() and 0xFF)
                                }
                            }
                        }
                        // PMT: learn the video elementary stream pid (H.264/HEVC/MPEG-2 video).
                        pmtPid >= 0 && pid == pmtPid && pusi -> {
                            lastPmtAt = abs
                            if (videoPid < 0) {
                                val q = p + 1 + (buf[pos + p].toInt() and 0xFF)   // pointer_field
                                if (q + 12 <= PACKET && (buf[pos + q].toInt() and 0xFF) == 0x02) {
                                    val infoLen = ((buf[pos + q + 10].toInt() and 0x0F) shl 8) or
                                        (buf[pos + q + 11].toInt() and 0xFF)
                                    var s = q + 12 + infoLen
                                    while (s + 5 <= PACKET) {
                                        val type = buf[pos + s].toInt() and 0xFF
                                        val ePid = ((buf[pos + s + 1].toInt() and 0x1F) shl 8) or
                                            (buf[pos + s + 2].toInt() and 0xFF)
                                        val esLen = ((buf[pos + s + 3].toInt() and 0x0F) shl 8) or
                                            (buf[pos + s + 4].toInt() and 0xFF)
                                        if (type == 0x1B || type == 0x24 || type == 0x10) {
                                            videoPid = ePid
                                            break
                                        }
                                        s += 5 + esLen
                                    }
                                }
                            }
                        }
                        // Video: watch every packet of the current PES for an IDR NAL, whether the
                        // access unit starts here (PES start) or continues from an earlier packet.
                        videoPid >= 0 && pid == videoPid -> {
                            var nalFrom = p
                            if (pusi) {
                                pesStartAt = abs
                                if (p + 9 <= PACKET && buf[pos + p].toInt() == 0 &&
                                    buf[pos + p + 1].toInt() == 0 && buf[pos + p + 2].toInt() == 1
                                ) {
                                    nalFrom = p + 9 + (buf[pos + p + 8].toInt() and 0xFF)
                                }
                            }
                            if (pesStartAt >= 0 && nalFrom < PACKET &&
                                containsIdr(buf, pos + nalFrom, pos + PACKET)
                            ) {
                                // Prefer the PAT packet just before this access unit so the part
                                // opens with its stream tables; otherwise the PES start is still
                                // a keyframe-aligned cut (the tables repeat within ~100 ms).
                                var cut = pesStartAt
                                if (lastPatAt >= 0 && lastPatAt <= pesStartAt &&
                                    lastPmtAt >= lastPatAt && abs - lastPatAt <= PSI_RECENT_BYTES
                                ) {
                                    cut = lastPatAt
                                }
                                if (cut > 0 && (candidates.isEmpty() || candidates.last() < cut)) {
                                    candidates.add(cut)
                                }
                            }
                        }
                    }
                }
                pos += PACKET
            }
            if (malformed) return null
            val rest = avail - pos
            if (rest > 0) System.arraycopy(buf, pos, buf, 0, rest)
            fill = rest
            base += pos.toLong()
            if (r <= 0) break
        }
        return candidates
    }

    /** True when the payload region [from, to) contains an H.264 IDR NAL (unit type 5). */
    private fun containsIdr(d: ByteArray, from: Int, to: Int): Boolean {
        if (from < 0 || from >= to) return false
        // Raw NAL unit without a start code prefix (uncommon, but cheap to detect).
        val h = d[from].toInt() and 0xFF
        if (h and 0x80 == 0 && h and 0x60 != 0 && h and 0x1F == 5) return true
        var i = from
        while (i + 3 < to) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                if (d[i + 2].toInt() == 1) {
                    if ((d[i + 3].toInt() and 0x1F) == 5) return true
                    i += 4
                    continue
                }
                if (i + 4 < to && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                    if ((d[i + 4].toInt() and 0x1F) == 5) return true
                    i += 5
                    continue
                }
            }
            i++
        }
        return false
    }

    /**
     * Greedily picks, for each part, the last keyframe that still keeps the part within
     * [maxPartBytes]. When the window contains no keyframe (very long GOPs), a plain
     * packet-aligned cut keeps the size guarantee.
     */
    private fun chooseCuts(candidates: List<Long>, total: Long, maxPartBytes: Long): List<Long> {
        val cuts = ArrayList<Long>()
        var start = 0L
        var idx = 0
        while (start + maxPartBytes < total) {
            var chosen = -1L
            while (idx < candidates.size && candidates[idx] <= start + maxPartBytes) {
                if (candidates[idx] > start) chosen = candidates[idx]
                idx++
            }
            if (chosen <= 0L) {
                val aligned = ((start + maxPartBytes) / PACKET) * PACKET
                if (aligned <= start || aligned >= total) break
                chosen = aligned
            }
            cuts.add(chosen)
            start = chosen
        }
        return cuts
    }

}
