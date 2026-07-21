package it.violi.sciomemory.model

import android.bluetooth.BluetoothGattCharacteristic

data class BleDeviceInfo(
    val address: String,
    val name: String,
    val rssi: Int,
    val advertisementHex: String
)

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    CONFIGURING,
    READY,
    ERROR
}

data class GattCharacteristicInfo(
    val serviceUuid: String,
    val characteristicUuid: String,
    val properties: Int
) {
    val canRead: Boolean
        get() = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
    val canNotify: Boolean
        get() = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    val canIndicate: Boolean
        get() = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    val canWrite: Boolean
        get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    val canWriteWithoutResponse: Boolean
        get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

    fun propertyLabel(): String = buildList {
        if (canRead) add("READ")
        if (canNotify) add("NOTIFY")
        if (canIndicate) add("INDICATE")
        if (canWrite) add("WRITE")
        if (canWriteWithoutResponse) add("WRITE_NO_RESPONSE")
    }.ifEmpty { listOf("Nessuna proprietà esposta") }.joinToString(" · ")
}

data class PacketEvent(
    val timestamp: Long,
    val kind: String,
    val deviceAddress: String,
    val serviceUuid: String,
    val characteristicUuid: String,
    val payload: ByteArray
) {
    val hex: String
        get() = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}

data class ScanProgress(
    val active: Boolean = false,
    val sectionIndex: Int = 0,
    val sequence: Int = 0,
    val packetCount: Int = 0,
    val message: String = "In attesa"
)

data class ScioScan(
    val capturedAt: Long,
    val deviceAddress: String,
    val declaredLength: Int?,
    val sections: List<ByteArray>,
    val rawPackets: List<ByteArray>,
    val complete: Boolean,
    val completionReason: String
) {
    val totalPayloadBytes: Int
        get() = sections.sumOf { it.size }

    fun combinedPayload(): ByteArray {
        val output = ByteArray(totalPayloadBytes)
        var offset = 0
        sections.forEach { section ->
            section.copyInto(output, offset)
            offset += section.size
        }
        return output
    }
}

data class MaterialProfile(
    val id: Long,
    val name: String,
    val category: String,
    val notes: String,
    val createdAt: Long,
    val scanCount: Int
)

data class StoredScan(
    val id: Long,
    val materialId: Long,
    val capturedAt: Long,
    val deviceAddress: String,
    val declaredLength: Int?,
    val sections: List<ByteArray>,
    val rawPackets: List<ByteArray>,
    val complete: Boolean,
    val completionReason: String,
    val notes: String
) {
    fun asLiveScan(): ScioScan = ScioScan(
        capturedAt = capturedAt,
        deviceAddress = deviceAddress,
        declaredLength = declaredLength,
        sections = sections,
        rawPackets = rawPackets,
        complete = complete,
        completionReason = completionReason
    )
}

data class MatchResult(
    val materialId: Long,
    val materialName: String,
    val category: String,
    val profileScans: Int,
    val scorePercent: Double,
    val repeatabilityPercent: Double,
    val evidencePercent: Double,
    val warning: String?
)


data class TemperatureReading(
    val cmosC: Double,
    val chipC: Double,
    val objectC: Double,
    val capturedAt: Long,
    val rawHex: String
)

data class WhiteReferenceState(
    val scan: ScioScan? = null,
    val capturedAt: Long? = null,
    val message: String = "Riferimento bianco non acquisito"
)
