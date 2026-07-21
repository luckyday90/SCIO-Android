package it.violi.sciomemory.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScioScanAssemblerTest {
    @Test
    fun reconstructsThreeSections() {
        val assembler = ScioScanAssembler()
        var completed: AssemblyUpdate.Completed? = null

        val first = ByteArray(20)
        first[0] = 0x01
        first[1] = 0xBA.toByte()
        first[2] = 0x02
        first[3] = 0x00
        first[4] = 0x15
        for (i in 5 until first.size) first[i] = i.toByte()
        assembler.accept(first, "AA:BB")

        fun packet(sequence: Int, marker: Int): ByteArray = ByteArray(20).also { bytes ->
            bytes[0] = sequence.toByte()
            for (i in 1 until bytes.size) bytes[i] = (marker + i).toByte()
        }

        for (seq in 2..0x5F) assembler.accept(packet(seq, 10), "AA:BB")
        for (seq in 1..0x5F) assembler.accept(packet(seq, 20), "AA:BB")
        for (seq in 1..0x58) {
            val update = assembler.accept(packet(seq, 30), "AA:BB")
            if (update is AssemblyUpdate.Completed) completed = update
        }

        val scan = requireNotNull(completed).scan
        assertTrue(scan.complete)
        assertEquals(3, scan.sections.size)
        assertEquals(1 + 94 + 95 + 88, scan.rawPackets.size)
        assertTrue(scan.sections.all { it.isNotEmpty() })
    }

    @Test
    fun timeoutKeepsIncompleteData() {
        val assembler = ScioScanAssembler()
        val first = byteArrayOf(0x01, 0xBA.toByte(), 0x02, 0x10, 0x00, 1, 2, 3)
        assembler.accept(first, "AA:BB")
        val result = assembler.finishOnTimeout()
        assertTrue(result is AssemblyUpdate.Completed)
        val scan = (result as AssemblyUpdate.Completed).scan
        assertTrue(!scan.complete)
        assertEquals(1, scan.sections.size)
    }
}
