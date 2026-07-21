package it.violi.sciomemory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.violi.sciomemory.analysis.FingerprintEngine
import it.violi.sciomemory.ble.BleManager
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

class ScioViewModel(application: Application) : AndroidViewModel(application) {
    val ble = BleManager(application.applicationContext)
    private val database = MaterialDatabase(application.applicationContext)

    private val _materials = MutableStateFlow<List<MaterialProfile>>(emptyList())
    val materials: StateFlow<List<MaterialProfile>> = _materials.asStateFlow()

    private val _storedScans = MutableStateFlow<List<StoredScan>>(emptyList())
    val storedScans: StateFlow<List<StoredScan>> = _storedScans.asStateFlow()

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

    fun clearMessage() {
        _appMessage.value = ""
    }

    private fun refreshDatabase() {
        viewModelScope.launch {
            val (materials, scans) = withContext(Dispatchers.IO) {
                database.listMaterials() to database.listScans()
            }
            _materials.value = materials
            _storedScans.value = scans
            val selected = _selectedMaterialId.value
            if (selected != null && materials.none { it.id == selected }) {
                _selectedMaterialId.value = null
            }
        }
    }

    override fun onCleared() {
        ble.disconnect()
        database.close()
        super.onCleared()
    }
}
