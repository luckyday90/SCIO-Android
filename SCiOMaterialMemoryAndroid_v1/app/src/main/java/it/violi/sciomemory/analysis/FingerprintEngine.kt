package it.violi.sciomemory.analysis

import it.violi.sciomemory.model.MatchResult
import it.violi.sciomemory.model.MaterialProfile
import it.violi.sciomemory.model.ScioScan
import it.violi.sciomemory.model.StoredScan
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Confronto sperimentale dei blocchi grezzi.
 * Non equivale a una probabilità chimica o a uno spettro calibrato.
 */
object FingerprintEngine {
    private const val BLOCK_FEATURES = 192
    private const val HISTOGRAM_BINS = 64

    fun compare(
        sample: ScioScan,
        materials: List<MaterialProfile>,
        scans: List<StoredScan>
    ): List<MatchResult> {
        if (sample.sections.isEmpty() || sample.totalPayloadBytes == 0) return emptyList()
        val materialById = materials.associateBy { it.id }
        val sampleFeature = featureVector(sample)

        return scans
            .filter { it.complete }
            .groupBy { it.materialId }
            .mapNotNull { (materialId, group) ->
                val material = materialById[materialId] ?: return@mapNotNull null
                val vectors = group.map { featureVector(it.asLiveScan()) }
                if (vectors.isEmpty()) return@mapNotNull null

                val centroid = centroid(vectors)
                val direct = combinedSimilarity(sampleFeature, centroid)
                val repeatability = vectors.map { combinedSimilarity(it, centroid) }.average()
                val evidence = min(1.0, group.size / 30.0)
                val finalScore = (direct * 0.84) + (repeatability * 0.11) + (evidence * 0.05)

                val warning = when {
                    group.size < 5 -> "Profilo troppo piccolo: servono almeno 10 letture."
                    group.size < 10 -> "Profilo preliminare: aumentare le letture."
                    !sample.complete -> "La scansione da confrontare non è completa."
                    else -> null
                }

                MatchResult(
                    materialId = material.id,
                    materialName = material.name,
                    category = material.category,
                    profileScans = group.size,
                    scorePercent = finalScore.coerceIn(0.0, 1.0) * 100.0,
                    repeatabilityPercent = repeatability.coerceIn(0.0, 1.0) * 100.0,
                    evidencePercent = evidence * 100.0,
                    warning = warning
                )
            }
            .sortedByDescending { it.scorePercent }
    }

    fun featureVector(scan: ScioScan): DoubleArray {
        val payload = scan.combinedPayload()
        if (payload.isEmpty()) return DoubleArray(BLOCK_FEATURES + HISTOGRAM_BINS + 3)

        val blockMeans = downsample(payload, BLOCK_FEATURES)
        val normalizedBlocks = zScore(blockMeans)
        val histogram = histogram(payload, HISTOGRAM_BINS)
        val sectionRatios = DoubleArray(3) { index ->
            val size = scan.sections.getOrNull(index)?.size ?: 0
            size.toDouble() / max(1, payload.size)
        }

        return normalizedBlocks + histogram + sectionRatios
    }

    private fun downsample(bytes: ByteArray, bins: Int): DoubleArray {
        val output = DoubleArray(bins)
        val counts = IntArray(bins)
        for (index in bytes.indices) {
            val bin = min(bins - 1, (index.toLong() * bins / bytes.size).toInt())
            output[bin] += unsigned(bytes[index]) / 255.0
            counts[bin] += 1
        }
        for (i in output.indices) {
            if (counts[i] > 0) output[i] = output[i] / counts[i].toDouble()
        }
        return output
    }

    private fun histogram(bytes: ByteArray, bins: Int): DoubleArray {
        val output = DoubleArray(bins)
        bytes.forEach { value ->
            val bin = min(bins - 1, unsigned(value).toInt() * bins / 256)
            output[bin] += 1.0
        }
        if (bytes.isNotEmpty()) {
            for (i in output.indices) output[i] = output[i] / bytes.size.toDouble()
        }
        return output
    }

    private fun zScore(values: DoubleArray): DoubleArray {
        if (values.isEmpty()) return values
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val standardDeviation = sqrt(variance)
        if (standardDeviation < 1e-12) return DoubleArray(values.size)
        return DoubleArray(values.size) { (values[it] - mean) / standardDeviation }
    }

    private fun centroid(vectors: List<DoubleArray>): DoubleArray {
        val size = vectors.first().size
        val result = DoubleArray(size)
        vectors.forEach { vector ->
            for (i in 0 until min(size, vector.size)) result[i] += vector[i]
        }
        for (i in result.indices) result[i] = result[i] / vectors.size.toDouble()
        return result
    }

    private fun combinedSimilarity(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        val cosine01 = (cosine(a, b) + 1.0) / 2.0
        val mae = a.indices.sumOf { abs(a[it] - b[it]) } / a.size
        val closeness = 1.0 / (1.0 + mae)
        return (cosine01 * 0.72) + (closeness * 0.28)
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA * normB)
        if (denominator < 1e-12) return if (a.contentEquals(b)) 1.0 else 0.0
        return max(-1.0, min(1.0, dot / denominator))
    }

    private fun unsigned(value: Byte): Double = (value.toInt() and 0xFF).toDouble()
}
