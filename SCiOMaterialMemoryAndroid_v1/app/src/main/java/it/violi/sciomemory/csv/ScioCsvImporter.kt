package it.violi.sciomemory.csv

import kotlin.math.abs

object ScioCsvImporter {
    const val MAX_SOURCE_BYTES = 25 * 1024 * 1024

    private const val MIN_SPECTRAL_COLUMNS = 32
    private const val FIRST_SCIO_WAVELENGTH = 740.0
    private const val LAST_SCIO_WAVELENGTH = 1070.0
    private const val WAVELENGTH_TOLERANCE = 0.01
    private const val MAX_ROWS = 10_000
    private const val MAX_COLUMNS = 2_000

    private val bandHeader = Regex("""^band(\d+(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)
    private val groupedHeader = Regex(
        """^(spectrum|wr_raw|sample_raw)_(\d+(?:\.\d+)?)$""",
        RegexOption.IGNORE_CASE
    )
    private val wavelengthHeaders = setOf(
        "wavelength",
        "wavelength_nm",
        "wavelengths",
        "nm"
    )
    private val signalHeaders = setOf(
        "reflectance",
        "spectrum",
        "absorbance",
        "intensity"
    )
    private val knownTypeNames = setOf(
        "bool",
        "boolean",
        "double",
        "float",
        "float32",
        "float64",
        "int",
        "int16",
        "int32",
        "int64",
        "long",
        "none",
        "nonetype",
        "str",
        "string",
        "unicode"
    )

    fun parse(sourceName: String, content: String): ScioCsvImport {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
            "Il CSV supera il limite di 25 MB."
        }
        val rows = parseCsv(content)
        require(rows.isNotEmpty()) { "Il file CSV è vuoto." }

        val groupedIndex = rows.indexOfFirst { extractGroupedColumns(it.fields).size >= MIN_SPECTRAL_COLUMNS }
        val bandIndex = rows.indexOfFirst { extractBandColumns(it.fields).size >= MIN_SPECTRAL_COLUMNS }
        val axisFirstIndex = rows.indexOfFirst { extractAxisFirstColumns(it.fields) != null }

        return when {
            groupedIndex >= 0 &&
                (bandIndex < 0 || groupedIndex <= bandIndex) &&
                (axisFirstIndex < 0 || groupedIndex <= axisFirstIndex) ->
                parseGrouped(sourceName, rows, groupedIndex)
            bandIndex >= 0 && (axisFirstIndex < 0 || bandIndex <= axisFirstIndex) ->
                parseBand(sourceName, rows, bandIndex)
            axisFirstIndex >= 0 -> parseAxisFirst(sourceName, rows, axisFirstIndex)
            else -> throw IllegalArgumentException(
                "Formato SCiO non riconosciuto: servono le colonne band740–band1070 " +
                    "oppure i gruppi spectrum_*, wr_raw_* e sample_raw_*, " +
                    "o una tabella wavelength/reflectance."
            )
        }
    }

    private fun parseBand(sourceName: String, rows: List<CsvRow>, headerIndex: Int): ScioCsvImport {
        val header = rows[headerIndex].fields
        val columns = extractBandColumns(header)
            .sortedBy { it.wavelength }
            .also { validateAxis("band", it) }
        val spectralIndexes = columns.mapTo(hashSetOf()) { it.index }
        val records = parseDataRows(rows, headerIndex, header) { row, metadata, recordNumber ->
            val series = parseSeries(row, columns, SpectralGroup.SPECTRUM)
                ?: throw row.error("I valori band740–band1070 sono assenti.")
            ScioCsvRecord(
                sourceRow = row.lineNumber,
                sampleId = sampleId(metadata, recordNumber),
                metadata = metadata.filterKeys { name ->
                    header.indexOfFirst { it.trim() == name } !in spectralIndexes
                },
                series = mapOf(SpectralGroup.SPECTRUM to series)
            )
        }
        require(records.isNotEmpty()) { "Il CSV SCiO non contiene righe di dati." }
        return ScioCsvImport(sourceName, ScioCsvLayout.BAND, emptyMap(), records)
    }

    private fun parseGrouped(sourceName: String, rows: List<CsvRow>, headerIndex: Int): ScioCsvImport {
        val header = rows[headerIndex].fields
        val columnsByGroup = extractGroupedColumns(header)
            .groupBy { it.group }
            .mapValues { (group, columns) ->
                columns.sortedBy { it.wavelength }.also { validateAxis(group.csvPrefix, it) }
            }
        require(columnsByGroup.keys.containsAll(SpectralGroup.entries)) {
            "L'export sviluppatore deve contenere spectrum_*, wr_raw_* e sample_raw_*."
        }

        val spectralIndexes = columnsByGroup.values.flatten().mapTo(hashSetOf()) { it.index }
        val preamble = extractPreamble(rows, headerIndex)

        val records = parseDataRows(rows, headerIndex, header) { row, metadata, recordNumber ->
            val series = columnsByGroup.mapValues { (group, columns) ->
                parseSeries(row, columns, group)
                    ?: throw row.error("Il gruppo ${group.csvPrefix}_* è vuoto.")
            }
            ScioCsvRecord(
                sourceRow = row.lineNumber,
                sampleId = sampleId(metadata, recordNumber),
                metadata = metadata.filterKeys { name ->
                    header.indexOfFirst { it.trim() == name } !in spectralIndexes
                },
                series = series
            )
        }
        require(records.isNotEmpty()) { "L'export sviluppatore SCiO non contiene righe di dati." }
        return ScioCsvImport(sourceName, ScioCsvLayout.GROUPED, preamble, records)
    }

    private fun parseAxisFirst(
        sourceName: String,
        rows: List<CsvRow>,
        headerIndex: Int
    ): ScioCsvImport {
        val header = rows[headerIndex].fields
        val columns = requireNotNull(extractAxisFirstColumns(header))
        val points = mutableListOf<SpectralPoint>()

        rows.drop(headerIndex + 1).forEach { row ->
            if (row.fields.all { it.isBlank() }) return@forEach
            val rawWavelength = row.fields.getOrNull(columns.wavelengthIndex)?.trim().orEmpty()
            val rawValue = row.fields.getOrNull(columns.valueIndex)?.trim().orEmpty()
            if (rawWavelength.isBlank() || rawValue.isBlank()) {
                throw row.error("Lunghezza d'onda o valore spettrale assente.")
            }
            val wavelength = rawWavelength.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: throw row.error("Lunghezza d'onda non numerica o non valida.")
            val value = rawValue.toDoubleOrNull()
                ?.takeIf { it.isFinite() }
                ?: throw row.error(
                    "Valore ${columns.valueHeader} non numerico a ${formatWavelength(wavelength)} nm."
                )
            points += SpectralPoint(row.lineNumber, wavelength, value)
        }

        require(points.size >= 2) {
            "La tabella asse-valore deve contenere almeno due punti spettrali."
        }
        require(points.map { it.wavelength }.distinct().size == points.size) {
            "La tabella asse-valore contiene lunghezze d'onda duplicate."
        }

        val sortedPoints = points.sortedBy { it.wavelength }
        val preamble = extractPreamble(rows, headerIndex)
        val sampleId = sourceName
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .ifBlank { "record-1" }
        val record = ScioCsvRecord(
            sourceRow = points.first().lineNumber,
            sampleId = sampleId,
            metadata = mapOf("value_column" to columns.valueHeader),
            series = mapOf(
                SpectralGroup.SPECTRUM to SpectralSeries(
                    wavelengths = sortedPoints.map { it.wavelength }.toDoubleArray(),
                    values = sortedPoints.map { it.value }.toDoubleArray()
                )
            )
        )
        return ScioCsvImport(
            sourceName = sourceName,
            layout = ScioCsvLayout.AXIS_FIRST,
            preamble = preamble,
            records = listOf(record)
        )
    }

    private fun parseDataRows(
        rows: List<CsvRow>,
        headerIndex: Int,
        header: List<String>,
        transform: (CsvRow, Map<String, String>, Int) -> ScioCsvRecord
    ): List<ScioCsvRecord> {
        val records = mutableListOf<ScioCsvRecord>()
        rows.drop(headerIndex + 1).forEach { row ->
            if (row.fields.all { it.isBlank() } || isTypeRow(row.fields)) return@forEach
            val metadata = linkedMapOf<String, String>()
            header.forEachIndexed { index, rawName ->
                val name = rawName.trim()
                val value = row.fields.getOrNull(index)?.trim().orEmpty()
                if (name.isNotBlank() && value.isNotBlank()) metadata[name] = value
            }
            records += transform(row, metadata, records.size + 1)
        }
        return records
    }

    private fun parseSeries(
        row: CsvRow,
        columns: List<SpectralColumn>,
        group: SpectralGroup
    ): SpectralSeries? {
        val rawValues = columns.map { row.fields.getOrNull(it.index)?.trim().orEmpty() }
        if (rawValues.all { it.isBlank() }) return null
        val values = DoubleArray(columns.size) { valueIndex ->
            val raw = rawValues[valueIndex]
            raw.toDoubleOrNull()
                ?.takeIf { it.isFinite() }
                ?: throw row.error(
                    "Valore ${group.csvPrefix} non numerico a ${formatWavelength(columns[valueIndex].wavelength)} nm."
                )
        }
        return SpectralSeries(
            wavelengths = columns.map { it.wavelength }.toDoubleArray(),
            values = values
        )
    }

    private fun validateAxis(label: String, columns: List<SpectralColumn>) {
        require(columns.size >= MIN_SPECTRAL_COLUMNS) {
            "Il gruppo $label contiene meno di $MIN_SPECTRAL_COLUMNS lunghezze d'onda."
        }
        require(columns.map { it.wavelength }.distinct().size == columns.size) {
            "Il gruppo $label contiene lunghezze d'onda duplicate."
        }
        val missingWavelength = (FIRST_SCIO_WAVELENGTH.toInt()..LAST_SCIO_WAVELENGTH.toInt())
            .firstOrNull { expected ->
                columns.none { abs(it.wavelength - expected) <= WAVELENGTH_TOLERANCE }
            }
        require(missingWavelength == null) {
            "Nel gruppo $label manca la lunghezza d'onda $missingWavelength nm."
        }
    }

    private fun extractBandColumns(header: List<String>): List<SpectralColumn> =
        header.mapIndexedNotNull { index, raw ->
            val wavelength = bandHeader.matchEntire(raw.trim())
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull()
                ?: return@mapIndexedNotNull null
            SpectralColumn(index, wavelength, SpectralGroup.SPECTRUM)
        }

    private fun extractGroupedColumns(header: List<String>): List<SpectralColumn> =
        header.mapIndexedNotNull { index, raw ->
            val match = groupedHeader.matchEntire(raw.trim()) ?: return@mapIndexedNotNull null
            val group = when (match.groupValues[1].lowercase()) {
                "spectrum" -> SpectralGroup.SPECTRUM
                "wr_raw" -> SpectralGroup.WR_RAW
                "sample_raw" -> SpectralGroup.SAMPLE_RAW
                else -> return@mapIndexedNotNull null
            }
            val wavelength = match.groupValues[2].toDoubleOrNull() ?: return@mapIndexedNotNull null
            SpectralColumn(index, wavelength, group)
        }

    private fun extractAxisFirstColumns(header: List<String>): AxisFirstColumns? {
        val normalized = header.map(::normalizeHeader)
        val wavelengthIndex = normalized.indexOfFirst { it in wavelengthHeaders }
        val valueIndex = normalized.indexOfFirst { it in signalHeaders }
        if (wavelengthIndex < 0 || valueIndex < 0 || wavelengthIndex == valueIndex) return null
        return AxisFirstColumns(
            wavelengthIndex = wavelengthIndex,
            valueIndex = valueIndex,
            valueHeader = normalized[valueIndex]
        )
    }

    private fun normalizeHeader(value: String): String =
        value.trim()
            .lowercase()
            .replace(Regex("""[\s-]+"""), "_")

    private fun extractPreamble(rows: List<CsvRow>, headerIndex: Int): Map<String, String> =
        linkedMapOf<String, String>().apply {
            rows.take(headerIndex).forEach { row ->
                val key = row.fields.getOrNull(0)?.trim().orEmpty()
                val value = row.fields.getOrNull(1)?.trim().orEmpty()
                if (key.isNotBlank()) this[key] = value
            }
        }

    private fun sampleId(metadata: Map<String, String>, recordNumber: Int): String =
        metadata.entries.firstOrNull { it.key.equals("sample_id", ignoreCase = true) }?.value
            ?: metadata.entries.firstOrNull { it.key.equals("id", ignoreCase = true) }?.value
            ?: "record-$recordNumber"

    private fun isTypeRow(fields: List<String>): Boolean {
        val values = fields.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (values.size < MIN_SPECTRAL_COLUMNS) return false
        val typeValues = values.count { value ->
            value in knownTypeNames ||
                value.startsWith("datetime") ||
                value.startsWith("numpy.") ||
                value.endsWith("type")
        }
        return typeValues.toDouble() / values.size >= 0.9
    }

    private fun parseCsv(content: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        var lineNumber = 1
        var rowStartLine = 1

        fun finishField() {
            fields += field.toString()
            field.setLength(0)
        }

        fun finishRow() {
            finishField()
            if (fields.any { it.isNotBlank() }) {
                require(fields.size <= MAX_COLUMNS) { "Il CSV supera il limite di $MAX_COLUMNS colonne." }
                val cleanFields = fields.toMutableList()
                if (rows.isEmpty() && cleanFields.isNotEmpty()) {
                    cleanFields[0] = cleanFields[0].removePrefix("\uFEFF")
                }
                rows += CsvRow(rowStartLine, cleanFields)
                require(rows.size <= MAX_ROWS) { "Il CSV supera il limite di $MAX_ROWS righe." }
            }
            fields.clear()
        }

        while (index < content.length) {
            val character = content[index]
            when {
                character == '"' && inQuotes && content.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                character == '"' -> inQuotes = !inQuotes
                character == ',' && !inQuotes -> finishField()
                (character == '\n' || character == '\r') && !inQuotes -> {
                    finishRow()
                    if (character == '\r' && content.getOrNull(index + 1) == '\n') index++
                    lineNumber++
                    rowStartLine = lineNumber
                }
                else -> {
                    field.append(character)
                    if (character == '\n') lineNumber++
                }
            }
            index++
        }
        require(!inQuotes) { "Il CSV contiene un campo tra virgolette non terminato." }
        if (field.isNotEmpty() || fields.isNotEmpty()) finishRow()
        return rows
    }

    private fun CsvRow.error(message: String): IllegalArgumentException =
        IllegalArgumentException("Riga $lineNumber: $message")

    private fun formatWavelength(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private data class CsvRow(val lineNumber: Int, val fields: List<String>)

    private data class SpectralColumn(
        val index: Int,
        val wavelength: Double,
        val group: SpectralGroup
    )

    private data class AxisFirstColumns(
        val wavelengthIndex: Int,
        val valueIndex: Int,
        val valueHeader: String
    )

    private data class SpectralPoint(
        val lineNumber: Int,
        val wavelength: Double,
        val value: Double
    )
}
