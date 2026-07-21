package it.violi.sciomemory

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import it.violi.sciomemory.model.BleConnectionState
import it.violi.sciomemory.model.GattCharacteristicInfo
import it.violi.sciomemory.model.PacketEvent
import it.violi.sciomemory.model.ScioScan
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScioMaterialMemoryApp()
                }
            }
        }
    }
}

private enum class AppTab(val label: String) {
    SCIO("SCiO"),
    MATERIALS("Materiali"),
    MATCH("Confronto"),
    DATA("Dati e log")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScioMaterialMemoryApp(vm: ScioViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.appMessage.collectAsState()

    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            snackbar.showSnackbar(message)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SCiO Material Memory", fontWeight = FontWeight.Bold)
                        Text("Acquisizione BLE sperimentale 1.0", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                AppTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.label) }
                    )
                }
            }
            when (AppTab.entries[selectedTab]) {
                AppTab.SCIO -> ScioScreen(vm)
                AppTab.MATERIALS -> MaterialsScreen(vm)
                AppTab.MATCH -> MatchScreen(vm)
                AppTab.DATA -> DataScreen(vm)
            }
        }
    }
}

@Composable
private fun ScioScreen(vm: ScioViewModel) {
    val context = LocalContext.current
    val devices by vm.ble.devices.collectAsState()
    val scanning by vm.ble.isScanning.collectAsState()
    val state by vm.ble.connectionState.collectAsState()
    val address by vm.ble.connectedAddress.collectAsState()
    val status by vm.ble.statusMessage.collectAsState()
    val progress by vm.ble.scanProgress.collectAsState()
    val latestScan by vm.ble.latestScan.collectAsState()
    val characteristics by vm.ble.characteristics.collectAsState()
    val temperature by vm.ble.temperature.collectAsState()
    val battery by vm.ble.batteryPercent.collectAsState()
    val whiteReference by vm.ble.whiteReference.collectAsState()

    var hasPermissions by remember {
        mutableStateOf(requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermissions = requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Stato: ${state.name}", fontWeight = FontWeight.Bold)
                    address?.let { Text("Dispositivo: $it") }
                    Text(status)
                    battery?.let { Text("Batteria interpretata: $it%") }
                    temperature?.let {
                        Text("Temperatura CMOS: ${"%.2f".format(it.cmosC)} °C")
                        Text("Temperatura chip: ${"%.2f".format(it.chipC)} °C")
                        Text("Temperatura oggetto: ${"%.2f".format(it.objectC)} °C")
                    }
                    Text(whiteReference.message, style = MaterialTheme.typography.bodySmall)
                    if (progress.active || progress.packetCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(progress.message)
                        Text("Pacchetti: ${progress.packetCount}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hasPermissions) {
                    Button(onClick = { permissionLauncher.launch(requiredPermissions()) }) {
                        Text("Autorizza Bluetooth")
                    }
                } else if (!scanning) {
                    Button(onClick = vm.ble::startScan) { Text("Cerca dispositivi") }
                } else {
                    Button(onClick = vm.ble::stopScan) { Text("Ferma ricerca") }
                }
                if (state != BleConnectionState.DISCONNECTED && state != BleConnectionState.SCANNING) {
                    OutlinedButton(onClick = vm.ble::disconnect) { Text("Disconnetti") }
                }
            }
        }

        if (state == BleConnectionState.READY) {
            item {
                SectionTitle("Comandi SCiO")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm.ble::requestBattery) { Text("Batteria") }
                        OutlinedButton(onClick = vm.ble::requestTemperature) { Text("Temperatura") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm.ble::startScioScan, enabled = !progress.active) {
                            Text(if (progress.active) "In corso..." else "Avvia scansione")
                        }
                        OutlinedButton(onClick = vm.ble::acquireWhiteReference, enabled = !progress.active) {
                            Text("Riferimento bianco")
                        }
                    }
                }
            }
        }

        item { SectionTitle("Dispositivi rilevati") }
        if (devices.isEmpty()) {
            item { Text("Nessun dispositivo rilevato.") }
        } else {
            items(devices, key = { it.address }) { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(device.name, fontWeight = FontWeight.Bold)
                        Text("${device.address} · RSSI ${device.rssi} dBm")
                        if (device.advertisementHex.isNotBlank()) {
                            Text(
                                device.advertisementHex.take(160),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { vm.ble.connect(device.address) }) { Text("Connetti") }
                    }
                }
            }
        }

        latestScan?.let { scan ->
            item {
                SectionTitle("Ultima scansione")
                ScanCard(scan)
            }
        }

        if (characteristics.isNotEmpty()) {
            item { SectionTitle("Caratteristiche GATT diagnostiche") }
            items(characteristics, key = { it.serviceUuid + it.characteristicUuid }) { info ->
                GattCard(
                    info = info,
                    onRead = { vm.ble.read(info) },
                    onSubscribe = { vm.ble.subscribe(info) }
                )
            }
        }

        item {
            Text(
                "Il punteggio successivo confronta dati grezzi o compressi. Non è uno spettro calibrato e non costituisce identificazione peritale.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MaterialsScreen(vm: ScioViewModel) {
    val materials by vm.materials.collectAsState()
    val selectedId by vm.selectedMaterialId.collectAsState()
    val latestScan by vm.ble.latestScan.collectAsState()

    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var scanNotes by remember { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionTitle("Nuovo materiale certo")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome materiale") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("Categoria") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Provenienza e condizioni") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.createMaterial(name, category, notes)
                    name = ""
                    category = ""
                    notes = ""
                },
                enabled = name.isNotBlank()
            ) { Text("Crea materiale") }
        }

        item {
            HorizontalDivider()
            SectionTitle("Libreria")
        }

        if (materials.isEmpty()) {
            item { Text("Nessun materiale archiviato.") }
        } else {
            items(materials, key = { it.id }) { material ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedId == material.id,
                            onClick = { vm.selectMaterial(material.id) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(material.name, fontWeight = FontWeight.Bold)
                            Text(material.category.ifBlank { "Senza categoria" })
                            Text("${material.scanCount} scansioni", style = MaterialTheme.typography.bodySmall)
                            if (material.notes.isNotBlank()) {
                                Text(material.notes, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { deleteCandidate = material.id }) { Text("Elimina") }
                    }
                }
            }
        }

        item {
            HorizontalDivider()
            SectionTitle("Salva l'ultima scansione")
            latestScan?.let { ScanCard(it) } ?: Text("Non è ancora stata acquisita una scansione.")
            OutlinedTextField(
                value = scanNotes,
                onValueChange = { scanNotes = it },
                label = { Text("Note sulla singola lettura") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.saveLatestScan(scanNotes)
                    scanNotes = ""
                },
                enabled = selectedId != null && latestScan != null
            ) { Text("Associa al materiale selezionato") }
            Text(
                "Per un primo profilo attendibile acquisire 20–40 scansioni, includendo punti diversi dello stesso materiale.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    deleteCandidate?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Eliminare il materiale?") },
            text = { Text("Verranno eliminate anche tutte le scansioni associate.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMaterial(id)
                    deleteCandidate = null
                }) { Text("Elimina") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Annulla") }
            }
        )
    }
}

@Composable
private fun MatchScreen(vm: ScioViewModel) {
    val latestScan by vm.ble.latestScan.collectAsState()
    val results by vm.matchResults.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionTitle("Campione da confrontare")
            latestScan?.let { ScanCard(it) } ?: Text("Eseguire una scansione SCiO.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = vm::compareLatestScan, enabled = latestScan != null) {
                Text("Confronta con la libreria")
            }
        }

        item {
            Text(
                "Il risultato è una somiglianza interna tra byte grezzi. Non leggerlo come percentuale di composizione o autenticità.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (results.isNotEmpty()) {
            item { SectionTitle("Risultati") }
            items(results, key = { it.materialId }) { result ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(result.materialName, fontWeight = FontWeight.Bold)
                        Text(result.category)
                        Text("Somiglianza sperimentale: ${"%.1f".format(result.scorePercent)}%")
                        Text("Ripetibilità profilo: ${"%.1f".format(result.repeatabilityPercent)}%")
                        Text("Evidenza numerica: ${"%.1f".format(result.evidencePercent)}% (${result.profileScans} scansioni)")
                        result.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataScreen(vm: ScioViewModel) {
    val packets by vm.ble.packetLog.collectAsState()
    val protocolMessages by vm.ble.protocolMessages.collectAsState()
    val storedScans by vm.storedScans.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = vm.exportJson()
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("scio-material-memory.json") }) {
                    Text("Esporta JSON")
                }
                OutlinedButton(onClick = vm.ble::clearLog) { Text("Pulisci log") }
            }
            Text("Scansioni archiviate: ${storedScans.size}")
        }

        if (protocolMessages.isNotEmpty()) {
            item { SectionTitle("Protocollo") }
            items(protocolMessages) { message ->
                Text(message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        item { SectionTitle("Pacchetti BLE recenti") }
        if (packets.isEmpty()) {
            item { Text("Nessun pacchetto ricevuto.") }
        } else {
            items(packets.take(100), key = { it.timestamp.toString() + it.hex }) { packet ->
                PacketCard(packet)
            }
        }
    }
}

@Composable
private fun ScanCard(scan: ScioScan) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(if (scan.complete) "Scansione completa" else "Scansione incompleta", fontWeight = FontWeight.Bold)
            Text(DateFormat.getDateTimeInstance().format(Date(scan.capturedAt)))
            Text("Sezioni: ${scan.sections.map { it.size }} byte")
            Text("Totale dati: ${scan.totalPayloadBytes} byte")
            Text("Pacchetti: ${scan.rawPackets.size}")
            scan.declaredLength?.let { Text("Lunghezza dichiarata: $it") }
            Text(scan.completionReason, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GattCard(
    info: GattCharacteristicInfo,
    onRead: () -> Unit,
    onSubscribe: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(info.serviceUuid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            Text(info.characteristicUuid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(info.propertyLabel(), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.canRead) TextButton(onClick = onRead) { Text("Leggi") }
                if (info.canNotify || info.canIndicate) TextButton(onClick = onSubscribe) { Text("Ascolta") }
            }
        }
    }
}

@Composable
private fun PacketCard(packet: PacketEvent?) {
    if (packet == null) {
        Text("Nessun pacchetto")
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("${packet.kind} · ${packet.payload.size} byte", fontWeight = FontWeight.Bold)
            Text(packet.characteristicUuid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            Text(packet.hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
