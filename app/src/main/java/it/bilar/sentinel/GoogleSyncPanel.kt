package it.bilar.sentinel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import android.accounts.Account
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun GoogleSyncPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val sync = (context.applicationContext as SentinelApplication).googleSync
    val state by sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    var authorizing by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }
    val client = remember { Identity.getAuthorizationClient(context) }
    var pendingAuthorization by remember { mutableStateOf<CompletableDeferred<AuthorizationResult>?>(null) }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingAuthorization?.let { pending ->
            try {
                if(result.resultCode != android.app.Activity.RESULT_OK || result.data == null) throw DriveSyncError(false,appText(R.string.google_sign_in_cancelled))
                pending.complete(client.getAuthorizationResultFromIntent(result.data))
            } catch(error: Exception) { pending.completeExceptionally(error) }
        }
    }
    val busy = authorizing || state.busy
    Dialog(onDismissRequest = { if(!busy) onClose() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 690.dp).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(appText(R.string.google_sync),style = MaterialTheme.typography.headlineSmall)
                Text(appText(R.string.trips_gps_routes_scooters_and_automatic_recording_sync_to_the_app_s_pr),style = MaterialTheme.typography.bodySmall)
                if(state.connected) {
                    Text(state.email,style = MaterialTheme.typography.titleMedium)
                    if(state.lastSync > 0) Text(appText(R.string.message_8,DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(state.lastSync))),style = MaterialTheme.typography.bodySmall)
                    Text(appText(R.string.data_syncs_when_online_after_each_trip_and_periodically_active_trips_a),style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(appText(R.string.choose_your_google_account_use_the_same_account_on_another_phone_to_re),style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = !busy,modifier = Modifier.fillMaxWidth(),onClick = {
                    authorizing = true
                    scope.launch {
                        try {
                            sync.authorizeAndSync { email ->
                                val result = client.authorize(GoogleSync.authorization(email)).awaitResult()
                                val resolved = if(result.hasResolution()) {
                                    val pending = result.pendingIntent ?: throw DriveSyncError(false,appText(R.string.google_access_is_unavailable))
                                    val waiting = CompletableDeferred<AuthorizationResult>()
                                    pendingAuthorization = waiting
                                    try {
                                        authorization.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                        waiting.await()
                                    } finally { pendingAuthorization = null; waiting.cancel() }
                                } else result
                                resolved.accessToken?.takeIf(String::isNotBlank)
                                    ?: throw DriveSyncError(false,appText(R.string.google_did_not_grant_drive_access))
                            }
                        } catch(error: CancellationException) { throw error }
                        catch(error: Exception) { sync.error(googleSyncError(error)) }
                        finally { authorizing = false }
                    }
                }) { Text(if(state.connected) appText(R.string.sync_now) else appText(R.string.connect_google_account)) }
                if(state.connected) TextButton(enabled = !busy,onClick = { disconnecting = true }) { Text(appText(R.string.disconnect_google_account)) }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(AppLanguage.translate(state.message),style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy,onClick = onClose) { Text(appText(R.string.close)) }
            }
        }
    }
    if(disconnecting) AlertDialog(onDismissRequest = { disconnecting = false },title = { Text(appText(R.string.disconnect_google)) },
        text = { Text(appText(R.string.sync_will_stop_local_data_and_drive_archives_will_be_kept_sign_in_with)) },
        confirmButton = { TextButton(onClick = {
            disconnecting = false; authorizing = true
            scope.launch {
                val email = state.email
                try {
                    withContext(Dispatchers.IO) { sync.disconnect() }
                    client.revokeAccess(RevokeAccessRequest.builder().setAccount(Account(email,"com.google")).setScopes(GoogleSync.scopes).build()).awaitResult()
                } catch(error: CancellationException) { throw error }
                catch(error: Exception) { sync.error(if(!sync.state.value.connected) appText(R.string.sync_stopped_you_can_revoke_any_remaining_access_from_your_google_acco) else googleSyncError(error)) }
                finally { authorizing = false }
            }
        }) { Text(appText(R.string.disconnect)) } },dismissButton = { TextButton(onClick = { disconnecting = false }) { Text(appText(R.string.cancel)) } })
}
