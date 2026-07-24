package it.violi.sciomemory.csv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScioCsvImporterTest {
    @Test
    fun parsesBand740ThroughBand1070WithQuotedMetadata() {
        val wavelengths = 740..1070
        val header = listOf("sample_id", "description") + wavelengths.map { "band$it" }
        val values = listOf("sample-1", "\"Hard cheese, aged\"") +
            wavelengths.map { wavelength -> ((wavelength - 700) / 100.0).toString() }
        val csv = header.joinToString(",") + "\r\n" + values.joinToString(",") + "\r\n"

        val result = ScioCsvImporter.parse("band-export.csv", csv)

        assertEquals(ScioCsvLayout.BAND, result.layout)
        assertEquals(1, result.records.size)
        assertEquals("sample-1", result.records.single().sampleId)
        assertEquals("Hard cheese, aged", result.records.single().metadata["description"])
        val spectrum = requireNotNull(result.records.single().series[SpectralGroup.SPECTRUM])
        assertEquals(331, spectrum.values.size)
        assertEquals(740.0, spectrum.wavelengths.first(), 0.0)
        assertEquals(1070.0, spectrum.wavelengths.last(), 0.0)
        assertFalse(result.records.single().metadata.containsKey("band740"))
    }

    @Test
    fun parsesDeveloperExportPreambleTypeRowAndAllSignalGroups() {
        val wavelengths = 740..1070
        val metadataHeaders = listOf("id", "sample_id", "comment")
        val spectralHeaders = SpectralGroup.entries.flatMap { group ->
            wavelengths.map { wavelength -> "${group.csvPrefix}_${wavelength}.0" }
        }
        val header = metadataHeaders + spectralHeaders
        val typeRow = listOf("int", "unicode", "str") + List(spectralHeaders.size) { "float" }
        val dataRow = listOf("17", "cheese-17", "\"ripe, room temperature\"") +
            SpectralGroup.entries.flatMapIndexed { groupIndex, _ ->
                wavelengths.mapIndexed { wavelengthIndex, _ ->
                    (groupIndex * 1_000 + wavelengthIndex).toString()
                }
            }
        val csv = buildString {
            appendLine("name,HARD CHEESE")
            appendLine("num_records,1")
            appendLine("num_wavelengths,331")
            appendLine("wavelengths_start,740")
            appendLine(header.joinToString(","))
            appendLine(typeRow.joinToString(","))
            appendLine(dataRow.joinToString(","))
        }

        val result = ScioCsvImporter.parse("developer-export.csv", csv)

        assertEquals(ScioCsvLayout.GROUPED, result.layout)
        assertEquals("331", result.preamble["num_wavelengths"])
        assertEquals(SpectralGroup.entries.toSet(), result.groups)
        assertEquals(1, result.records.size)
        assertEquals("cheese-17", result.records.single().sampleId)
        assertEquals("ripe, room temperature", result.records.single().metadata["comment"])
        assertEquals(331, result.records.single().series[SpectralGroup.WR_RAW]?.values?.size)
        assertArrayEquals(
            doubleArrayOf(2_000.0, 2_001.0, 2_002.0),
            result.records.single().series.getValue(SpectralGroup.SAMPLE_RAW).values.take(3).toDoubleArray(),
            0.0
        )
        assertTrue(result.records.single().metadata.keys.none { it.startsWith("spectrum_") })
    }

    @Test
    fun reportsMissingValueWithSourceRowAndWavelength() {
        val wavelengths = 740..1070
        val header = wavelengths.joinToString(",") { "band$it" }
        val values = wavelengths.joinToString(",") { if (it == 800) "" else "0.5" }

        val error = runCatching {
            ScioCsvImporter.parse("broken.csv", "$header\n$values\n")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("Riga 2"))
        assertTrue(error?.message.orEmpty().contains("800 nm"))
    }
}
