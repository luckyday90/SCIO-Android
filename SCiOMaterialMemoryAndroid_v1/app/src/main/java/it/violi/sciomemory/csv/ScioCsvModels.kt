package it.violi.sciomemory.csv

enum class ScioCsvLayout(val displayName: String) {
    BAND("band"),
    GROUPED("gruppi"),
    AXIS_FIRST("asse-valore")
}

enum class SpectralGroup(val csvPrefix: String) {
    SPECTRUM("spectrum"),
    WR_RAW("wr_raw"),
    SAMPLE_RAW("sample_raw")
}

data class SpectralSeries(
    val wavelengths: DoubleArray,
    val values: DoubleArray
) {
    init {
        require(wavelengths.size == values.size) {
            "L'asse delle lunghezze d'onda non coincide con il numero di valori."
        }
    }
}

data class ScioCsvRecord(
    val sourceRow: Int,
    val sampleId: String,
    val metadata: Map<String, String>,
    val series: Map<SpectralGroup, SpectralSeries>
)

data class ScioCsvImport(
    val sourceName: String,
    val layout: ScioCsvLayout,
    val preamble: Map<String, String>,
    val records: List<ScioCsvRecord>
) {
    val groups: Set<SpectralGroup>
        get() = records.flatMapTo(linkedSetOf()) { it.series.keys }

    val wavelengthStart: Double
        get() = records.asSequence()
            .flatMap { it.series.values.asSequence() }
            .mapNotNull { it.wavelengths.minOrNull() }
            .minOrNull()
            ?: Double.NaN

    val wavelengthEnd: Double
        get() = records.asSequence()
            .flatMap { it.series.values.asSequence() }
            .mapNotNull { it.wavelengths.maxOrNull() }
            .maxOrNull()
            ?: Double.NaN
}

data class CsvImportSummary(
    val id: Long,
    val fileName: String,
    val layout: ScioCsvLayout,
    val importedAt: Long,
    val recordCount: Int,
    val wavelengthStart: Double,
    val wavelengthEnd: Double,
    val groups: Set<SpectralGroup>
)
