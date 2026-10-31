package it.bilar.sentinel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalDebugVisible = staticCompositionLocalOf { false }

@Composable
fun AppSettingsPanel(state: ScooterUiState, onClose: () -> Unit, onSelect: (String) -> Unit, onAddJeep: () -> Unit,
    onAccount: () -> Unit, onRemove: () -> Unit, onDebug: (Boolean) -> Unit, onDemo: () -> Unit, onExitDemo: () -> Unit, onDiagnose: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit, onGoogle: () -> Unit, onBackup: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val account = remember { YouFsAccount(context.applicationContext) }
    var hasAccount by remember { mutableStateOf(account.remembered() != null) }
    var unlinking by remember { mutableStateOf(false) }
    var unlinkBusy by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 660.dp).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appText(R.string.settings), fontSize = 25.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(appText(R.string.close)) }
                }
                Text(appText(R.string.your_scooters), fontSize = 18.sp)
                state.scooters.forEach { scooter ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !state.demo) { onSelect(scooter.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.activeScooterId == scooter.id, onClick = { onSelect(scooter.id) }, enabled = !state.demo)
                        Column {
                            Text(scooter.name)
                            Text(if (state.activeScooterId == scooter.id && state.connected) appText(R.string.connected) else if (scooter.address != null) appText(R.string.remembered_device) else appText(R.string.not_connected),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedButton(onClick = { adding = true }, enabled = !state.busy && !state.demo, modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.add_a_scooter)) }
                TextButton(onClick = { removing = true }, enabled = !state.busy && !state.demo) { Text(appText(R.string.remove_selected_scooter)) }
                if(state.family == ScooterFamily.YOUFS_TUYA) OutlinedButton(onClick = { onClose(); onExport() },enabled = !state.demo && state.youfsSchema.isNotEmpty(),modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.share_export_youfs)) }
                OutlinedButton(onClick = { onClose(); onImport() },enabled = !state.demo,modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.import_youfs_scooter)) }
                HorizontalDivider()
                Text(appText(R.string.accounts_and_data),fontSize = 18.sp)
                Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                    Text("Language / Lingua",modifier = Modifier.weight(1f))
                    listOf("en" to "English","it" to "Italiano").forEach { (code,label) ->
                        TextButton(onClick = {
                            AppLanguage.set(context,code)
                            (context as? android.app.Activity)?.recreate()
                        },enabled = AppLanguage.code(context) != code && !unlinkBusy) { Text(label) }
                    }
                }
                OutlinedButton(onClick = { onClose(); onGoogle() },enabled = !state.demo,modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.google_sync)) }
                OutlinedButton(onClick = { onClose(); onBackup() },enabled = !state.demo,modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.trip_backup)) }
                if(hasAccount) TextButton(onClick = { unlinking = true },enabled = !unlinkBusy && !state.demo) { Text(appText(R.string.disconnect_youfs_account)) }
                if(accountMessage.isNotEmpty()) Text(accountMessage,fontSize = 12.sp)
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(appText(R.string.show_debug_and_diagnostics))
                        Text(appText(R.string.technical_details_logs_and_experimental_features), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.debugEnabled, onCheckedChange = onDebug)
                }
                if (state.debugEnabled) TextButton(onClick = { onClose(); onDiagnose() }) { Text(appText(R.string.open_diagnostics)) }
                HorizontalDivider()
                Text(appText(R.string.explore_the_app), fontSize = 18.sp)
                Text(appText(R.string.the_demo_uses_simulated_data_and_does_not_send_commands_to_your_scoote), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { onClose(); if (state.demo) onExitDemo() else onDemo() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.demo) appText(R.string.leave_demo) else appText(R.string.enter_demo))
                }
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text(appText(R.string.add_a_scooter)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(appText(R.string.choose_your_scooter_type))
            OutlinedButton(onClick = { adding = false; onClose(); onAddJeep() }, modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.jeep_sentinel_find_bluetooth_device)) }
            OutlinedButton(onClick = { adding = false; onClose(); onAccount() }, modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.youfs_a_sign_in)) }
            OutlinedButton(onClick = { adding = false; onClose(); onImport() },modifier = Modifier.fillMaxWidth()) { Text(appText(R.string.youfs_a_import_shared_file)) }
            Text(appText(R.string.sign_in_to_youfs_or_import_a_shared_file_credentials_stay_encrypted_in), fontSize = 12.sp)
        } }, confirmButton = { TextButton(onClick = { adding = false }) { Text(appText(R.string.cancel)) } })
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text(appText(R.string.message_0,state.activeScooter.name)) },
        text = { Text(appText(R.string.the_connection_and_this_scooter_s_local_credentials_will_be_removed_yo)) },
        confirmButton = { TextButton(onClick = { removing = false; onRemove() }) { Text(appText(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text(appText(R.string.cancel)) } })
    if(unlinking) AlertDialog(onDismissRequest = { if(!unlinkBusy) unlinking = false },title = { Text(appText(R.string.disconnect_your_youfs_account)) },
        text = { Text(appText(R.string.the_account_details_and_session_saved_on_this_phone_will_be_removed_co)) },
        confirmButton = { TextButton(enabled = !unlinkBusy,onClick = {
            unlinkBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { account.disconnect() }
                    hasAccount = false; unlinking = false; accountMessage = appText(R.string.youfs_account_disconnected_from_this_phone)
                } catch(error: CancellationException) { throw error }
                catch(_: Exception) { accountMessage = appText(R.string.unable_to_disconnect_the_account_please_try_again); unlinking = false }
                finally { unlinkBusy = false }
            }
        }) { Text(appText(R.string.disconnect)) } },dismissButton = { TextButton(enabled = !unlinkBusy,onClick = { unlinking = false }) { Text(appText(R.string.cancel)) } })
}
