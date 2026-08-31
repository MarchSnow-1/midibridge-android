package com.marchsnow.midibridge.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the standard MIDI byte-stream state machine (AND-M1/M2/M3).
 *
 * Assertions compare hex dumps because ByteArray uses identity equality.
 */
class MidiParserTest {

    private fun parse(vararg chunks: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val parser = MidiParser()
        for (chunk in chunks) {
            parser.feed(chunk, 0, chunk.size) { msg -> out.add(msg) }
        }
        return out
    }

    private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()

    private fun hex(data: ByteArray) = data.joinToString(" ") { "%02X".format(it) }

    private fun assertMessages(vararg expected: IntArray, actual: List<ByteArray>) {
        val expectedHex = expected.map { arr -> arr.joinToString(" ") { "%02X".format(it) } }
        val actualHex = actual.map { hex(it) }
        assertEquals(expectedHex, actualHex)
    }

    // ─── Basic message framing ───

    @Test
    fun `single note on message`() {
        assertMessages(intArrayOf(0x90, 0x3C, 0x64), actual = parse(bytes(0x90, 0x3C, 0x64)))
    }

    @Test
    fun `multiple messages concatenated in one chunk`() {
        assertMessages(
            intArrayOf(0x90, 0x3C, 0x64),
            intArrayOf(0x80, 0x3C, 0x40),
            intArrayOf(0xB0, 0x07, 0x64),
            actual = parse(bytes(0x90, 0x3C, 0x64, 0x80, 0x3C, 0x40, 0xB0, 0x07, 0x64))
        )
    }

    @Test
    fun `program change has one data byte`() {
        assertMessages(intArrayOf(0xC0, 0x05), actual = parse(bytes(0xC0, 0x05)))
    }

    @Test
    fun `channel pressure has one data byte`() {
        assertMessages(intArrayOf(0xD5, 0x40), actual = parse(bytes(0xD5, 0x40)))
    }

    @Test
    fun `incomplete message is not emitted`() {
        assertMessages(actual = parse(bytes(0x90, 0x3C)))
    }

    @Test
    fun `message split across chunks is reassembled`() {
        assertMessages(intArrayOf(0x90, 0x3C, 0x64), actual = parse(bytes(0x90, 0x3C), bytes(0x64)))
    }

    @Test
    fun `new status byte aborts incomplete message`() {
        assertMessages(
            intArrayOf(0x80, 0x40, 0x40),
            actual = parse(bytes(0x90, 0x3C, 0x80, 0x40, 0x40))
        )
    }

    // ─── count / offset guards ───

    @Test
    fun `count zero and negative are ignored`() {
        val parser = MidiParser()
        val out = mutableListOf<ByteArray>()
        parser.feed(bytes(0x90, 0x3C, 0x64), 0, 0) { out.add(it) }
        parser.feed(bytes(0x90, 0x3C, 0x64), 0, -1) { out.add(it) }
        assertMessages(actual = out)
    }

    @Test
    fun `offset and count are respected`() {
        val parser = MidiParser()
        val out = mutableListOf<ByteArray>()
        // Chunk starts at index 2, length 3: [90 3C 64]
        parser.feed(bytes(0xF8, 0xF8, 0x90, 0x3C, 0x64, 0xF8), 2, 3) { out.add(it) }
        assertMessages(intArrayOf(0x90, 0x3C, 0x64), actual = out)
    }

    // ─── Running Status ───

    @Test
    fun `running status reuses previous channel status`() {
        // Note On C4, then Note On E4 with status byte omitted
        assertMessages(
            intArrayOf(0x90, 0x3C, 0x64),
            intArrayOf(0x90, 0x3E, 0x40),
            actual = parse(bytes(0x90, 0x3C, 0x64, 0x3E, 0x40))
        )
    }

    @Test
    fun `running status works across chunks`() {
        assertMessages(
            intArrayOf(0x90, 0x3C, 0x64),
            intArrayOf(0x90, 0x3E, 0x40),
            actual = parse(bytes(0x90, 0x3C, 0x64), bytes(0x3E, 0x40))
        )
    }

    @Test
    fun `running status with single-data-byte status`() {
        // Program Change 5, then running-status Program Change 6
        assertMessages(
            intArrayOf(0xC0, 0x05),
            intArrayOf(0xC0, 0x06),
            actual = parse(bytes(0xC0, 0x05, 0x06))
        )
    }

    @Test
    fun `system common clears running status`() {
        // Song Position Pointer is system common; the following bare data
        // bytes must NOT be interpreted as running-status channel data
        assertMessages(
            intArrayOf(0x90, 0x3C, 0x64),
            intArrayOf(0xF2, 0x00, 0x00),
            actual = parse(bytes(0x90, 0x3C, 0x64, 0xF2, 0x00, 0x00, 0x3C, 0x40))
        )
    }

    @Test
    fun `realtime message does not clear running status`() {
        assertMessages(
            intArrayOf(0x90, 0x3C, 0x64),
            intArrayOf(0xF8),
            intArrayOf(0x90, 0x3E, 0x40),
            actual = parse(bytes(0x90, 0x3C, 0x64, 0xF8, 0x3E, 0x40))
        )
    }

    @Test
    fun `stray data bytes with no status are dropped`() {
        assertMessages(actual = parse(bytes(0x3C, 0x40, 0x7F)))
    }

    // ─── Realtime messages ───

    @Test
    fun `realtime messages extracted at arbitrary positions`() {
        assertMessages(
            intArrayOf(0xF8),
            intArrayOf(0x90, 0x3C, 0x64),
            intArrayOf(0xFE),
            intArrayOf(0xFF),
            actual = parse(bytes(0xF8, 0x90, 0x3C, 0x64, 0xFE, 0xFF))
        )
    }

    @Test
    fun `realtime message inside an assembling message does not corrupt it`() {
        // Clock byte arrives between the two data bytes of a note-on
        assertMessages(
            intArrayOf(0xF8),
            intArrayOf(0x90, 0x3C, 0x64),
            actual = parse(bytes(0x90, 0x3C, 0xF8, 0x64))
        )
    }

    // ─── System common ───

    @Test
    fun `tune request is a standalone message`() {
        assertMessages(intArrayOf(0xF6), actual = parse(bytes(0xF6)))
    }

    @Test
    fun `song position pointer has two data bytes`() {
        assertMessages(intArrayOf(0xF2, 0x00, 0x40), actual = parse(bytes(0xF2, 0x00, 0x40)))
    }

    @Test
    fun `mtc quarter frame has one data byte`() {
        assertMessages(intArrayOf(0xF1, 0x5E), actual = parse(bytes(0xF1, 0x5E)))
    }

    // ─── SysEx ───

    @Test
    fun `complete sysex in one chunk`() {
        assertMessages(
            intArrayOf(0xF0, 0x7E, 0x7F, 0x06, 0x01, 0xF7),
            actual = parse(bytes(0xF0, 0x7E, 0x7F, 0x06, 0x01, 0xF7))
        )
    }

    @Test
    fun `sysex split across chunks is reassembled`() {
        assertMessages(
            intArrayOf(0xF0, 0x7E, 0x7F, 0x06, 0x01, 0xF7),
            actual = parse(bytes(0xF0, 0x7E, 0x7F, 0x06, 0x01), bytes(0xF7))
        )
    }

    @Test
    fun `realtime inside sysex is extracted separately`() {
        // MIDI clock may legally be interleaved inside a SysEx transfer
        assertMessages(
            intArrayOf(0xF8),
            intArrayOf(0xF0, 0x01, 0x02, 0xF7),
            actual = parse(bytes(0xF0, 0x01, 0xF8, 0x02, 0xF7))
        )
    }

    @Test
    fun `channel message after sysex parses normally`() {
        assertMessages(
            intArrayOf(0xF0, 0x01, 0xF7),
            intArrayOf(0x90, 0x3C, 0x64),
            actual = parse(bytes(0xF0, 0x01, 0xF7, 0x90, 0x3C, 0x64))
        )
    }

    @Test
    fun `oversized sysex is discarded until EOX`() {
        val parser = MidiParser(maxSysexBytes = 8)
        val out = mutableListOf<ByteArray>()
        val big = ByteArray(20) { 0x01 }
        parser.feed(bytes(0xF0), 0, 1) { out.add(it) }
        parser.feed(big, 0, big.size) { out.add(it) }        // exceeds cap → discard mode
        parser.feed(bytes(0x02, 0x03), 0, 2) { out.add(it) } // swallowed
        parser.feed(bytes(0xF7), 0, 1) { out.add(it) }       // EOX ends discard
        parser.feed(bytes(0x90, 0x3C, 0x64), 0, 3) { out.add(it) } // normal parsing resumes
        assertMessages(intArrayOf(0x90, 0x3C, 0x64), actual = out)
    }

    @Test
    fun `status byte inside unterminated sysex discards the partial sysex`() {
        assertMessages(
            intArrayOf(0x90, 0x3C, 0x64),
            actual = parse(bytes(0xF0, 0x01, 0x02, 0x90, 0x3C, 0x64))
        )
    }

    // ─── reset ───

    @Test
    fun `reset clears running status and partial state`() {
        val parser = MidiParser()
        val out = mutableListOf<ByteArray>()
        parser.feed(bytes(0x90, 0x3C, 0x64), 0, 3) { out.add(it) }
        parser.reset()
        // After reset, bare data bytes must be dropped (no running status)
        parser.feed(bytes(0x3E, 0x40), 0, 2) { out.add(it) }
        assertMessages(intArrayOf(0x90, 0x3C, 0x64), actual = out)
    }

    // ─── Regression: the removed CIN heuristic ───

    @Test
    fun `four-byte stream is never mangled by CIN stripping`() {
        // Old bug: a 4-byte chunk not starting with 0xF0 had its first byte
        // stripped as a "CIN header". It must be parsed as a real stream:
        // a complete 3-byte note-on plus the first data byte of a
        // running-status follow-up (which waits for its second data byte).
        assertMessages(
            intArrayOf(0x90, 0x3C, 0x64),
            actual = parse(bytes(0x90, 0x3C, 0x64, 0x3E))
        )
    }
}
