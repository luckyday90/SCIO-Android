package it.violi.sciomemory.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import it.violi.sciomemory.csv.CsvImportSummary
import it.violi.sciomemory.csv.ScioCsvImport
import it.violi.sciomemory.csv.ScioCsvLayout
import it.violi.sciomemory.csv.SpectralGroup
import it.violi.sciomemory.model.MaterialProfile
import it.violi.sciomemory.model.ScioScan
import it.violi.sciomemory.model.StoredScan
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class MaterialDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE materials (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        createScansTable(db)
        createCsvImportsTable(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createScansTable(db)
        }
        if (oldVersion < 3) {
            createCsvImportsTable(db)
        }
    }

    private fun createScansTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                material_id INTEGER NOT NULL,
                captured_at INTEGER NOT NULL,
                device_address TEXT NOT NULL,
                declared_length INTEGER,
                section_1 BLOB NOT NULL,
                section_2 BLOB NOT NULL,
                section_3 BLOB NOT NULL,
                raw_packets BLOB NOT NULL,
                is_complete INTEGER NOT NULL,
                completion_reason TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(material_id) REFERENCES materials(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scans_material ON scans(material_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scans_date ON scans(captured_at)")
    }

    private fun createCsvImportsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS csv_imports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_name TEXT NOT NULL,
                layout TEXT NOT NULL,
                imported_at INTEGER NOT NULL,
                record_count INTEGER NOT NULL,
                wavelength_start REAL NOT NULL,
                wavelength_end REAL NOT NULL,
                groups_csv TEXT NOT NULL,
                source_sha256 TEXT NOT NULL UNIQUE,
                payload_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_csv_imports_date ON csv_imports(imported_at)")
    }

    fun createMaterial(name: String, category: String, notes: String): Long {
        require(name.isNotBlank()) { "Il nome del materiale è obbligatorio." }
        val values = ContentValues().apply {
            put("name", name.trim())
            put("category", category.trim())
            put("notes", notes.trim())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("materials", null, values)
    }

    fun deleteMaterial(materialId: Long) {
        writableDatabase.delete("materials", "id = ?", arrayOf(materialId.toString()))
    }

    fun listMaterials(): List<MaterialProfile> {
        val sql = """
            SELECT m.id, m.name, m.category, m.notes, m.created_at,
                   COUNT(s.id) AS scan_count
            FROM materials m
            LEFT JOIN scans s ON s.material_id = m.id
            GROUP BY m.id
            ORDER BY m.name COLLATE NOCASE
        """.trimIndent()

        return readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MaterialProfile(
                            id = cursor.getLong(0),
                            name = cursor.getString(1),
                            category = cursor.getString(2),
                            notes = cursor.getString(3),
                            createdAt = cursor.getLong(4),
                            scanCount = cursor.getInt(5)
                        )
                    )
                }
            }
        }
    }

    fun addScan(materialId: Long, scan: ScioScan, notes: String): Long {
        val sections = List(3) { index -> scan.sections.getOrNull(index) ?: ByteArray(0) }
        val values = ContentValues().apply {
            put("material_id", materialId)
            put("captured_at", scan.capturedAt)
            put("device_address", scan.deviceAddress)
            if (scan.declaredLength == null) putNull("declared_length") else put("declared_length", scan.declaredLength)
            put("section_1", sections[0])
            put("section_2", sections[1])
            put("section_3", sections[2])
            put("raw_packets", encodePackets(scan.rawPackets))
            put("is_complete", if (scan.complete) 1 else 0)
            put("completion_reason", scan.completionReason)
            put("notes", notes.trim())
        }
        return writableDatabase.insertOrThrow("scans", null, values)
    }

    fun listScans(): List<StoredScan> {
        val sql = """
            SELECT id, material_id, captured_at, device_address, declared_length,
                   section_1, section_2, section_3, raw_packets,
                   is_complete, completion_reason, notes
            FROM scans
            ORDER BY captured_at DESC
        """.trimIndent()

        return readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StoredScan(
                            id = cursor.getLong(0),
                            materialId = cursor.getLong(1),
                            capturedAt = cursor.getLong(2),
                            deviceAddress = cursor.getString(3),
                            declaredLength = if (cursor.isNull(4)) null else cursor.getInt(4),
                            sections = listOf(cursor.getBlob(5), cursor.getBlob(6), cursor.getBlob(7)),
                            rawPackets = decodePackets(cursor.getBlob(8)),
                            complete = cursor.getInt(9) == 1,
                            completionReason = cursor.getString(10),
                            notes = cursor.getString(11)
                        )
                    )
                }
            }
        }
    }

    fun hasCsvImport(sourceSha256: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM csv_imports WHERE source_sha256 = ? LIMIT 1",
            arrayOf(sourceSha256)
        ).use { it.moveToFirst() }

    fun addCsvImport(csvImport: ScioCsvImport, sourceSha256: String): Long {
        require(csvImport.records.isNotEmpty()) { "Nessun record CSV da archiviare." }
        val values = ContentValues().apply {
            put("file_name", csvImport.sourceName)
            put("layout", csvImport.layout.name)
            put("imported_at", System.currentTimeMillis())
            put("record_count", csvImport.records.size)
            put("wavelength_start", csvImport.wavelengthStart)
            put("wavelength_end", csvImport.wavelengthEnd)
            put("groups_csv", csvImport.groups.joinToString(",") { it.name })
            put("source_sha256", sourceSha256)
            put("payload_json", csvImport.toJson().toString())
        }
        return writableDatabase.insertOrThrow("csv_imports", null, values)
    }

    fun listCsvImports(): List<CsvImportSummary> {
        val sql = """
            SELECT id, file_name, layout, imported_at, record_count,
                   wavelength_start, wavelength_end, groups_csv
            FROM csv_imports
            ORDER BY imported_at DESC
        """.trimIndent()
        return readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val groups = cursor.getString(7)
                        .split(',')
                        .mapNotNull { name -> runCatching { SpectralGroup.valueOf(name) }.getOrNull() }
                        .toSet()
                    add(
                        CsvImportSummary(
                            id = cursor.getLong(0),
                            fileName = cursor.getString(1),
                            layout = ScioCsvLayout.valueOf(cursor.getString(2)),
                            importedAt = cursor.getLong(3),
                            recordCount = cursor.getInt(4),
                            wavelengthStart = cursor.getDouble(5),
                            wavelengthEnd = cursor.getDouble(6),
                            groups = groups
                        )
                    )
                }
            }
        }
    }

    fun deleteCsvImport(importId: Long) {
        writableDatabase.delete("csv_imports", "id = ?", arrayOf(importId.toString()))
    }

    fun exportJson(): String {
        val materials = listMaterials()
        val scans = listScans()
        val root = JSONObject()
            .put("format", "SCiO Material Memory")
            .put("version", 3)
            .put("exported_at", System.currentTimeMillis())

        val materialArray = JSONArray()
        materials.forEach { material ->
            val scanArray = JSONArray()
            scans.filter { it.materialId == material.id }.forEach { scan ->
                scanArray.put(
                    JSONObject()
                        .put("id", scan.id)
                        .put("captured_at", scan.capturedAt)
                        .put("device_address", scan.deviceAddress)
                        .put("declared_length", scan.declaredLength ?: JSONObject.NULL)
                        .put("complete", scan.complete)
                        .put("completion_reason", scan.completionReason)
                        .put("notes", scan.notes)
                        .put("sections_base64", JSONArray().apply {
                            scan.sections.forEach { put(Base64.encodeToString(it, Base64.NO_WRAP)) }
                        })
                        .put("raw_packets_base64", JSONArray().apply {
                            scan.rawPackets.forEach { put(Base64.encodeToString(it, Base64.NO_WRAP)) }
                        })
                )
            }
            materialArray.put(
                JSONObject()
                    .put("id", material.id)
                    .put("name", material.name)
                    .put("category", material.category)
                    .put("notes", material.notes)
                    .put("created_at", material.createdAt)
                    .put("scans", scanArray)
            )
        }
        root.put("materials", materialArray)
        val csvImports = JSONArray()
        readableDatabase.rawQuery(
            "SELECT id, imported_at, source_sha256, payload_json FROM csv_imports ORDER BY imported_at",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                csvImports.put(
                    JSONObject(cursor.getString(3))
                        .put("id", cursor.getLong(0))
                        .put("imported_at", cursor.getLong(1))
                        .put("source_sha256", cursor.getString(2))
                )
            }
        }
        root.put("csv_imports", csvImports)
        return root.toString(2)
    }

    private fun ScioCsvImport.toJson(): JSONObject {
        val preambleJson = JSONObject()
        preamble.forEach { (key, value) -> preambleJson.put(key, value) }
        val recordsJson = JSONArray()
        records.forEach { record ->
            val metadataJson = JSONObject()
            record.metadata.forEach { (key, value) -> metadataJson.put(key, value) }
            val seriesJson = JSONObject()
            record.series.forEach { (group, series) ->
                seriesJson.put(
                    group.csvPrefix,
                    JSONObject()
                        .put("wavelengths_nm", series.wavelengths.toJsonArray())
                        .put("values", series.values.toJsonArray())
                )
            }
            recordsJson.put(
                JSONObject()
                    .put("source_row", record.sourceRow)
                    .put("sample_id", record.sampleId)
                    .put("metadata", metadataJson)
                    .put("series", seriesJson)
            )
        }
        return JSONObject()
            .put("source_name", sourceName)
            .put("layout", layout.name)
            .put("preamble", preambleJson)
            .put("records", recordsJson)
    }

    private fun DoubleArray.toJsonArray(): JSONArray =
        JSONArray().also { array -> forEach { value -> array.put(value) } }

    private fun encodePackets(packets: List<ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(packets.size)
            packets.forEach { packet ->
                data.writeInt(packet.size)
                data.write(packet)
            }
        }
        return output.toByteArray()
    }

    private fun decodePackets(blob: ByteArray): List<ByteArray> {
        if (blob.isEmpty()) return emptyList()
        return runCatching {
            DataInputStream(ByteArrayInputStream(blob)).use { data ->
                val count = data.readInt().coerceIn(0, 10_000)
                buildList {
                    repeat(count) {
                        val length = data.readInt().coerceIn(0, 1_000_000)
                        add(ByteArray(length).also { data.readFully(it) })
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val DATABASE_NAME = "scio_material_memory.db"
        private const val DATABASE_VERSION = 3
    }
}
