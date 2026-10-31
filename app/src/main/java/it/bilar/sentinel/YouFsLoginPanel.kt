package it.bilar.sentinel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun YouFsLoginPanel(onClose: () -> Unit, onAdd: (ByteArray) -> Boolean) {
    val context = LocalContext.current.applicationContext
    val manager = remember { YouFsAccount(context) }
    val saved = remember { manager.remembered() }
    var account by remember { mutableStateOf(saved?.optString("account").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var country by remember { mutableStateOf(saved?.optString("country", "39") ?: "39") }
    var region by remember { mutableStateOf(saved?.optString("region", "EU") ?: "EU") }
    var code by remember { mutableStateOf("") }
    var showCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf<List<YouFsAccountDevice>?>(null) }
    val scope = rememberCoroutineScope()
    val debug = LocalDebugVisible.current
    fun errorText(error: Exception) = when (error) {
        is YouFsCloudError -> error.message.orEmpty() + if (debug) " (${error.code})" else ""
        is IllegalArgumentException -> appText(R.string.check_your_email_or_phone_number_and_country_code)
        else -> appText(R.string.the_server_did_not_return_compatible_data_try_again_later)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().imePadding().padding(16.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 690.dp).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appText(R.string.youfs_a_account), fontSize = 24.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { password = ""; code = ""; onClose() }) { Text(appText(R.string.close)) }
                }
                Text(appText(R.string.sign_in_with_your_original_youfs_a_app_credentials_to_retrieve_your_sc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (devices == null) {
                    OutlinedTextField(account, { account = it }, label = { Text(appText(R.string.email_or_phone)) }, enabled = !busy, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, enabled = !busy, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(country, { country = it }, label = { Text(appText(R.string.country_code)) }, enabled = !busy, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.weight(1f))
                        var regionOpen by remember { mutableStateOf(false) }
                        val regions = mapOf("EU" to appText(R.string.europe), "AZ" to appText(R.string.united_states), "IN" to "India", "CN" to appText(R.string.china))
                        Box(Modifier.weight(1f).padding(top = 8.dp)) {
                            OutlinedButton(onClick = { regionOpen = true }, enabled = !busy) { Text((regions[region] ?: appText(R.string.europe)) + " ▾") }
                            DropdownMenu(regionOpen, onDismissRequest = { regionOpen = false }) {
                                regions.forEach { (id,name) -> DropdownMenuItem(text = { Text(name) }, onClick = { region = id; regionOpen = false }) }
                            }
                        }
                    }
                    Text(appText(R.string.use_the_country_and_region_you_registered_your_account_with), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showCode) {
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text(appText(R.string.email_code)) }, enabled = !busy, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true; message = ""
                            scope.launch {
                                try { withContext(Dispatchers.IO) { manager.requestCode(account, password, country.trim().removePrefix("+"), region) }; message = appText(R.string.code_requested_check_your_email) }
                                catch (error: Exception) { message = errorText(error) }
                                finally { busy = false }
                            }
                        }) { Text(appText(R.string.request_email_code)) }
                    }
                    Button(enabled = !busy && account.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                        busy = true; message = ""
                        scope.launch {
                            try {
                                devices = withContext(Dispatchers.IO) { manager.login(account, password, country.trim().removePrefix("+"), region, code) }
                                password = ""; code = ""; showCode = false
                                if (devices!!.isEmpty()) message = appText(R.string.no_devices_found_check_your_account_and_region)
                            } catch (error: Exception) {
                                if (error is YouFsCloudError && error.requiresCode) showCode = true
                                message = errorText(error)
                            } finally { busy = false }
                        }
                    }) { Text(if (busy) appText(R.string.signing_in) else appText(R.string.sign_in_and_find_scooters)) }
                } else {
                    Text(appText(R.string.choose_a_scooter_to_add), fontSize = 17.sp)
                    devices!!.forEach { device -> OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        busy = true; message = ""
                        scope.launch {
                            try {
                                val profile = withContext(Dispatchers.IO) { manager.fetch(device.id) }
                                if (onAdd(profile)) onClose() else message = appText(R.string.unable_to_save_this_device_on_your_phone)
                            } catch (error: Exception) { message = errorText(error) }
                            finally { busy = false }
                        }
                    }) { Text(device.name) } }
                    TextButton(onClick = { devices = null; message = "" }, enabled = !busy) { Text(appText(R.string.change_account)) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotEmpty()) Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
