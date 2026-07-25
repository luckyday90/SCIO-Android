package it.violi.sciomemory

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.violi.sciomemory.analysis.FingerprintEngine
import it.violi.sciomemory.ble.BleManager
import it.violi.sciomemory.csv.CsvImportSummary
import it.violi.sciomemory.csv.ScioCsvImporter
import it.violi.sciomemory.data.MaterialDatabase
import it.violi.sciomemory.model.MatchResult
import it.violi.sciomemory.model.MaterialProfile
import it.violi.sciomemory.model.StoredScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ScioViewModel(application: Application) : AndroidViewModel(application) {
    val ble = BleManager(application.applicationContext)
    private val database = MaterialDatabase(application.applicationContext)

    private val _materials = MutableStateFlow<List<MaterialProfile>>(emptyList())
    val materials: StateFlow<List<MaterialProfile>> = _materials.asStateFlow()

    private val _storedScans = MutableStateFlow<List<StoredScan>>(emptyList())
    val storedScans: StateFlow<List<StoredScan>> = _storedScans.asStateFlow()

    private val _csvImports = MutableStateFlow<List<CsvImportSummary>>(emptyList())
    val csvImports: StateFlow<List<CsvImportSummary>> = _csvImports.asStateFlow()

    private val _csvImporting = MutableStateFlow(false)
    val csvImporting: StateFlow<Boolean> = _csvImporting.asStateFlow()

    private val _selectedMaterialId = MutableStateFlow<Long?>(null)
    val selectedMaterialId: StateFlow<Long?> = _selectedMaterialId.asStateFlow()

    private val _matchResults = MutableStateFlow<List<MatchResult>>(emptyList())
    val matchResults: StateFlow<List<MatchResult>> = _matchResults.asStateFlow()

    private val _appMessage = MutableStateFlow("")
    val appMessage: StateFlow<String> = _appMessage.asStateFlow()

    init {
        refreshDatabase()
    }

    fun createMaterial(name: String, category: String, notes: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { database.createMaterial(name, category, notes) }
            }.onSuccess { id ->
                _selectedMaterialId.value = id
                _appMessage.value = "Materiale creato"
                refreshDatabase()
            }.onFailure { _appMessage.value = it.message ?: "Errore nella creazione" }
        }
    }

    fun deleteMaterial(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { database.deleteMaterial(id) }
            if (_selectedMaterialId.value == id) _selectedMaterialId.value = null
            _appMessage.value = "Materiale eliminato"
            refreshDatabase()
        }
    }

    fun selectMaterial(id: Long) {
        _selectedMaterialId.value = id
    }

    fun saveLatestScan(notes: String) {
        val materialId = _selectedMaterialId.value
        val scan = ble.latestScan.value
        if (materialId == null) {
            _appMessage.value = "Selezionare prima un materiale"
            return
        }
        if (scan == null) {
            _appMessage.value = "Nessuna scansione da salvare"
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { database.addScan(materialId, scan, notes) }
            }.onSuccess {
                _appMessage.value = if (scan.complete) {
                    "Scansione salvata nel materiale"
                } else {
                    "Scansione incompleta salvata per diagnosi"
                }
                refreshDatabase()
            }.onFailure { _appMessage.value = it.message ?: "Errore di salvataggio" }
        }
    }

    fun compareLatestScan() {
        val scan = ble.latestScan.value
        if (scan == null) {
            _appMessage.value = "Eseguire prima una scansione"
            return
        }
        _matchResults.value = FingerprintEngine.compare(scan, _materials.value, _storedScans.value)
        if (_matchResults.value.isEmpty()) {
            _appMessage.value = "La banca dati non contiene scansioni complete confrontabili"
        }
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) { database.exportJson() }

    fun importCsv(uri: Uri) {
        if (_csvImporting.value) return
        _csvImporting.value = true
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val fileName = resolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment ?: "import-scio.csv"
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= ScioCsvImporter.MAX_SOURCE_BYTES) {
                                "Il CSV supera il limite di 25 MB."
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Impossibile aprire il file selezionato.")
                    val digest = MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    require(!database.hasCsvImport(digest)) {
                        "Questo file CSV è già stato importato."
                    }
                    val csvImport = ScioCsvImporter.parse(fileName, bytes.toString(Charsets.UTF_8))
                    database.addCsvImport(csvImport, digest)
                    csvImport
                }
            }.onSuccess { csvImport ->
                _appMessage.value =
                    "Importati ${csvImport.records.size} record CSV (${csvImport.groups.size} gruppi)"
                refreshDatabase()
            }.onFailure { error ->
                _appMessage.value = error.message ?: "Errore durante l'importazione CSV"
            }
            _csvImporting.value = false
        }
    }

    fun deleteCsvImport(id: Long) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { database.deleteCsvImport(id) }
            }.onSuccess {
                _appMessage.value = "Importazione CSV eliminata"
                refreshDatabase()
            }.onFailure { _appMessage.value = it.message ?: "Errore durante l'eliminazione" }
        }
    }

    fun clearMessage() {
        _appMessage.value = ""
    }

    private fun refreshDatabase() {
        viewModelScope.launch {
            val (materials, scans, csvImports) = withContext(Dispatchers.IO) {
                Triple(
                    database.listMaterials(),
                    database.listScans(),
                    database.listCsvImports()
                )
            }
            _materials.value = materials
            _storedScans.value = scans
            _csvImports.value = csvImports
            val selected = _selectedMaterialId.value
            if (selected != null && materials.none { it.id == selected }) {
                _selectedMaterialId.value = null
            }
        }
    }

    override fun onCleared() {
        ble.close()
        database.close()
        super.onCleared()
    }
}
