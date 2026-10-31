package it.bilar.sentinel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun TripBackupPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SentinelApplication
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<SyncRecord>?>(null) }
    fun failure(error: Exception) {
        message = if(error is IllegalStateException) appText(R.string.end_the_trip_before_restoring_a_backup)
            else appText(R.string.operation_failed_check_the_file_and_available_storage_existing_trips_a)
    }
    fun save(uri: android.net.Uri?, gpx: Boolean) {
        if(uri == null) return
        busy = true; message = ""
        scope.launch {
            try {
                withContext(app.tripDispatcher) {
                    val bytes = if(gpx) TripBackup.gpx(app.trips) else TripBackup.encode(app.trips)
                    try { requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { it.write(bytes) } }
                    finally { bytes.fill(0) }
                }
                message = if(gpx) appText(R.string.gpx_routes_exported) else appText(R.string.trip_backup_saved)
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) { failure(error) }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { save(it,false) }
    val gpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { save(it,true) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            busy = true; message = ""
            scope.launch {
                try {
                    pending = withContext(app.tripDispatcher) {
                        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { PortableArchive.readBounded(it,PortableArchive.MAX_FILE) }
                        try { TripBackup.decode(bytes).also(app.trips::validateSync) } finally { bytes.fill(0) }
                    }
                } catch(error: CancellationException) { throw error }
                catch(error: Exception) { failure(error) }
                finally { busy = false }
            }
        }
    }
    Dialog(onDismissRequest = { if(!busy) onClose() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(appText(R.string.trip_backup),style = MaterialTheme.typography.headlineSmall)
                Text(appText(R.string.save_and_restore_completed_trips_without_google_the_backup_contains_gp),style = MaterialTheme.typography.bodySmall)
                Button(onClick = { export.launch("sentinel-trips-${LocalDate.now()}.sentinel") },enabled = !busy,modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.save_backup)) }
                OutlinedButton(onClick = { restore.launch(arrayOf("application/octet-stream","*/*")) },enabled = !busy,modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.restore_backup)) }
                OutlinedButton(onClick = { gpx.launch("sentinel-routes-${LocalDate.now()}.gpx") },enabled = !busy,modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.export_gpx_routes)) }
                Text(appText(R.string.anyone_receiving_a_saved_file_can_read_it_use_gpx_to_share_routes_and),style = MaterialTheme.typography.bodySmall)
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(message.isNotEmpty()) Text(message,style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onClose,enabled = !busy) { Text(appText(R.string.close)) }
            }
        }
    }
    pending?.let { records ->
        AlertDialog(onDismissRequest = { if(!busy) pending = null },title = { Text(AppLanguage.quantity(R.plurals.restore_trip_count,records.size)) },
            text = { Text(appText(R.string.missing_trips_will_be_added_including_previously_deleted_trips_existin)) },
            confirmButton = { TextButton(enabled = !busy,onClick = {
                busy = true
                scope.launch {
                    try {
                        val count = withContext(app.tripDispatcher) { app.trips.restoreBackup(records) }
                        message = appText(R.string.message_56,count); pending = null
                    } catch(error: CancellationException) { throw error }
                    catch(error: Exception) { failure(error); pending = null }
                    finally { busy = false }
                }
            }) { Text(appText(R.string.restore)) } },dismissButton = { TextButton(enabled = !busy,onClick = { pending = null }) { Text(appText(R.string.cancel)) } })
    }
}
