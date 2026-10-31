package it.bilar.sentinel

import android.content.ClipData
import android.content.Intent
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ScooterTransferPanel(exporting: Boolean, scooter: SavedScooter, onClose: () -> Unit, onImport: (ByteArray) -> Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var incoming by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun failure(error: Exception) {
        message = if(error is IllegalArgumentException && error.message?.length in 1..150) error.message.orEmpty()
            else appText(R.string.operation_failed_choose_a_scooter_file_exported_by_sentinel)
    }
    suspend fun prepare(): ByteArray = withContext(Dispatchers.IO) {
        val profile = YouFsProfileStore(context,scooter.profileSlot).load()
            ?: throw IllegalArgumentException(appText(R.string.connect_your_youfs_account_first_to_retrieve_the_scooter))
        ScooterShareFile.encode(profile,scooter.name,scooter.address)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null) scope.launch {
            busy = true
            var bytes: ByteArray? = null
            try {
                val prepared = prepare(); bytes = prepared
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { it.write(prepared) } }
                message = appText(R.string.scooter_exported_import_the_file_on_another_phone)
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) { failure(error) }
            finally { bytes?.fill(0); busy = false }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            busy = true; message = ""; incoming?.fill(0); incoming = null
            try { incoming = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { PortableArchive.readBounded(it,YouFsProfile.MAX_BYTES) } } }
            catch(error: CancellationException) { throw error }
            catch(error: Exception) { failure(error) }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if(!busy) { incoming?.fill(0); onClose() } }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if(exporting) appText(R.string.message_22,scooter.name) else appText(R.string.import_youfs_scooter),style = MaterialTheme.typography.headlineSmall)
                Text(if(exporting) appText(R.string.anyone_receiving_this_file_can_connect_to_and_control_this_scooter_sha)
                    else appText(R.string.choose_a_scooter_file_shared_by_sentinel_it_will_be_added_to_your_gara),style = MaterialTheme.typography.bodySmall)
                if(exporting) {
                    Button(enabled = !busy,modifier = Modifier.fillMaxWidth(),onClick = {
                        busy = true; message = ""
                        scope.launch {
                            var bytes: ByteArray? = null
                            try {
                                val prepared = prepare(); bytes = prepared
                                val uri = withContext(Dispatchers.IO) {
                                    val folder = File(context.cacheDir,"shared-scooters").apply { mkdirs() }
                                    folder.listFiles()?.filter { it.lastModified() < System.currentTimeMillis()-86_400_000 }?.forEach { it.delete() }
                                    val file = File(folder,"youfs-${java.util.UUID.randomUUID()}.json").apply { writeBytes(prepared) }
                                    FileProvider.getUriForFile(context,"${context.packageName}.shared",file)
                                }
                                val intent = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(appText(R.string.youfs_scooter),uri) }
                                context.startActivity(Intent.createChooser(intent,appText(R.string.share_youfs_scooter)))
                                message = appText(R.string.file_ready_to_share)
                            } catch(error: CancellationException) { throw error }
                            catch(error: Exception) { failure(error) }
                            finally { bytes?.fill(0); busy = false }
                        }
                    }) { Text(appText(R.string.share_file)) }
                    OutlinedButton(enabled = !busy,modifier = Modifier.fillMaxWidth(),onClick = { save.launch("monopattino-youfs.json") }) { Text(appText(R.string.save_file)) }
                } else {
                    OutlinedButton(enabled = !busy,onClick = { pick.launch(arrayOf("application/json","application/octet-stream","*/*")) }) { Text(if(incoming == null) appText(R.string.choose_file) else appText(R.string.file_selected_change)) }
                    Button(enabled = !busy && incoming != null,modifier = Modifier.fillMaxWidth(),onClick = {
                        busy = true; message = ""
                        scope.launch {
                            var clear: ByteArray? = null
                            try {
                                clear = withContext(Dispatchers.IO) { ScooterShareFile.decode(incoming!!) }
                                if(onImport(clear)) { incoming?.fill(0); incoming = null; onClose() }
                                else message = appText(R.string.unable_to_add_the_scooter)
                            } catch(error: CancellationException) { throw error }
                            catch(error: Exception) { failure(error) }
                            finally { clear?.fill(0); busy = false }
                        }
                    }) { Text(appText(R.string.import_into_garage)) }
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(message.isNotBlank()) Text(message,style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy,onClick = { incoming?.fill(0); onClose() }) { Text(appText(R.string.close)) }
            }
        }
    }
}
