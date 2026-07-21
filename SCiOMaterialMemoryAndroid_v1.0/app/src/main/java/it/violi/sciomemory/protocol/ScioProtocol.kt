package it.violi.sciomemory.protocol

import it.violi.sciomemory.model.ScanProgress
import it.violi.sciomemory.model.ScioScan
import java.io.ByteArrayOutputStream
import java.util.UUID

object ScioProtocol {
    val COMMAND_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("00003492-0000-1000-8000-00805f9b34fb")

    val BATTERY_COMMAND: ByteArray = byteArrayOf(0x01, 0xBA.toByte(), 0x05, 0x00, 0x00)
    val TEMPERATURE_COMMAND: ByteArray = byteArrayOf(0x01, 0xBA.toByte(), 0x04, 0x00, 0x00)
    val SCAN_COMMAND: ByteArray = byteArrayOf(0x01, 0xBA.toByte(), 0x02, 0x00, 0x00)
    val WHITE_REFERENCE_READY_COMMAND: ByteArray = byteArrayOf(0x01, 0xBA.toByte(), 0x0E, 0x00, 0x00)

    const val PROTOCOL_MARKER = 0xBA
    const val RESPONSE_SCAN = 0x02
    const val RESPONSE_TEMPERATURE = 0x04
    const val RESPONSE_BATTERY = 0x05

    /** Decodifica prudente: conserva sempre il pacchetto grezzo. */
    fun parseTemperature(packet: ByteArray): Triple<Double, Double, Double>? {
        if (responseType(packet) != RESPONSE_TEMPERATURE || packet.size < 9) return null
        val values = mutableListOf<Int>()
        var i = 3
        while (i + 1 < packet.size && values.size < 3) {
            val le = unsigned(packet[i]) or (unsigned(packet[i + 1]) shl 8)
            values += if (le >= 0x8000) le - 0x10000 else le
            i += 2
        }
        if (values.size < 3) return null
        return Triple(values[0] / 100.0, values[1] / 100.0, values[2] / 100.0)
    }

    fun parseBatteryPercent(packet: ByteArray): Int? {
        if (responseType(packet) != RESPONSE_BATTERY || packet.size < 4) return null
        return packet.drop(3).map { unsigned(it) }.firstOrNull { it in 0..100 }
    }

    fun commandName(command: ByteArray): String = when {
        command.contentEquals(BATTERY_COMMAND) -> "Batteria"
        command.contentEquals(TEMPERATURE_COMMAND) -> "Temperatura"
        command.contentEquals(SCAN_COMMAND) -> "Scansione"
        command.contentEquals(WHITE_REFERENCE_READY_COMMAND) -> "Preparazione riferimento bianco"
        else -> "Comando sconosciuto"
    }

    fun responseType(packet: ByteArray): Int? {
        if (packet.size < 3) return null
        if (unsigned(packet[0]) != 1 || unsigned(packet[1]) != PROTOCOL_MARKER) return null
        return unsigned(packet[2])
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") {
        "%02X".format(unsigned(it))
    }

    internal fun unsigned(value: Byte): Int = value.toInt() and 0xFF
}

sealed interface AssemblyUpdate {
    data class Progress(val value: ScanProgress) : AssemblyUpdate
    data class Completed(val scan: ScioScan) : AssemblyUpdate
    data class Ignored(val reason: String) : AssemblyUpdate
}

/**
 * Ricompone le notifiche BLE documentate dal progetto SCIO-read.
 *
 * Prima riga: 01 BA 02 LL HH + dati.
 * Righe successive: contatore + dati.
 * Il contatore riparte da 01 per ciascuna delle tre sezioni.
 */
class ScioScanAssembler {
    private val sections = mutableListOf<ByteArrayOutputStream>()
    private val rawPackets = mutableListOf<ByteArray>()

    private var active = false
    private var currentSection = 0
    private var lastSequence = 0
    private var declaredLength: Int? = null
    private var capturedAt = 0L
    private var deviceAddress = ""

    fun isActive(): Boolean = active

    fun reset() {
        sections.clear()
        rawPackets.clear()
        active = false
        currentSection = 0
        lastSequence = 0
        declaredLength = null
        capturedAt = 0L
        deviceAddress = ""
    }

    fun accept(
        packet: ByteArray,
        address: String,
        timestamp: Long = System.currentTimeMillis()
    ): AssemblyUpdate {
        if (packet.isEmpty()) return AssemblyUpdate.Ignored("Pacchetto vuoto")

        val responseType = ScioProtocol.responseType(packet)
        if (responseType == ScioProtocol.RESPONSE_SCAN) {
            startNewScan(packet, address, timestamp)
            return progress("Scansione iniziata")
        }

        if (!active) {
            return AssemblyUpdate.Ignored("Notifica non appartenente a una scansione attiva")
        }

        val sequence = ScioProtocol.unsigned(packet[0])
        if (sequence !in 1..0x7F) {
            return AssemblyUpdate.Ignored("Contatore BLE non plausibile: $sequence")
        }

        rawPackets += packet.copyOf()

        if (sequence == 1 && lastSequence > 1) {
            currentSection += 1
            if (currentSection > 2) {
                return finish(false, "Ricevuta una quarta sezione inattesa")
            }
            sections += ByteArrayOutputStream()
        } else if (lastSequence > 0 && sequence != lastSequence + 1) {
            // Conserviamo comunque i dati: il log deve permettere di diagnosticare pacchetti persi.
        }

        if (packet.size > 1) {
            sections[currentSection].write(packet, 1, packet.size - 1)
        }
        lastSequence = sequence

        if (currentSection == 2 && sequence >= 0x58) {
            return finish(true, "Tre sezioni ricevute")
        }

        return progress("Sezione ${currentSection + 1}, pacchetto $sequence")
    }

    fun finishOnTimeout(): AssemblyUpdate {
        if (!active) return AssemblyUpdate.Ignored("Nessuna scansione attiva")
        return finish(false, "Chiusura per timeout")
    }

    private fun startNewScan(packet: ByteArray, address: String, timestamp: Long) {
        reset()
        active = true
        capturedAt = timestamp
        deviceAddress = address
        currentSection = 0
        lastSequence = 1
        declaredLength = parseDeclaredLength(packet)
        sections += ByteArrayOutputStream()
        rawPackets += packet.copyOf()

        if (packet.size > 5) {
            sections[0].write(packet, 5, packet.size - 5)
        }
    }

    private fun parseDeclaredLength(packet: ByteArray): Int? {
        if (packet.size < 5) return null
        val b3 = ScioProtocol.unsigned(packet[3])
        val b4 = ScioProtocol.unsigned(packet[4])
        val littleEndian = b3 or (b4 shl 8)
        val bigEndian = (b3 shl 8) or b4
        return listOf(littleEndian, bigEndian).firstOrNull { it in 100..20_000 }
            ?: littleEndian.takeIf { it > 0 }
    }

    private fun progress(message: String): AssemblyUpdate.Progress = AssemblyUpdate.Progress(
        ScanProgress(
            active = true,
            sectionIndex = currentSection,
            sequence = lastSequence,
            packetCount = rawPackets.size,
            message = message
        )
    )

    private fun finish(complete: Boolean, reason: String): AssemblyUpdate.Completed {
        val scan = ScioScan(
            capturedAt = capturedAt,
            deviceAddress = deviceAddress,
            declaredLength = declaredLength,
            sections = sections.map { it.toByteArray() },
            rawPackets = rawPackets.map { it.copyOf() },
            complete = complete && sections.size == 3,
            completionReason = reason
        )
        reset()
        return AssemblyUpdate.Completed(scan)
    }
}
