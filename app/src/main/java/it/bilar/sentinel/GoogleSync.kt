package it.bilar.sentinel

import android.accounts.Account
import android.content.Context
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class GoogleSyncStatus(val connected: Boolean = false, val email: String = "", val busy: Boolean = false,
    val lastSync: Long = 0, val message: String = appText(R.string.connect_google_to_sync_data_between_your_phones), val revision: Int = 0)

internal class GoogleSync(private val context: Context,
    private val trips: TripStore = (context.applicationContext as SentinelApplication).trips,
    private val driveFactory: (String) -> GoogleDriveStore = { GoogleDriveStore(it) },
    private val clearToken: suspend (String) -> Unit = { token ->
        Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).awaitResult()
    }) {
    companion object {
        val scopes = listOf(Scope("https://www.googleapis.com/auth/drive.appdata"),Scope("https://www.googleapis.com/auth/userinfo.email"),Scope("openid"))
        private val mutex = Mutex()
        fun schedule(context: Context, periodic: Boolean = false, immediate: Boolean = true) {
            if (!context.getSharedPreferences("google-sync",Context.MODE_PRIVATE).getBoolean("enabled",false)) return
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
            val manager = WorkManager.getInstance(context)
            if(immediate) manager.enqueueUniqueWork("google-sync-now",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<GoogleSyncWorker>().setConstraints(constraints).build())
            if(periodic) manager.enqueueUniquePeriodicWork("google-sync-periodic",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GoogleSyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).build())
        }
        fun authorization(account: String? = null) = AuthorizationRequest.builder().setRequestedScopes(scopes)
            .apply { account?.takeIf(String::isNotBlank)?.let { setAccount(Account(it,"com.google")) } }.build()
    }
    private val preferences = context.getSharedPreferences("google-sync",Context.MODE_PRIVATE)
    private val vault = EncryptedJsonFile(context,"google-sync.enc")
    private fun secret(): JSONObject? = vault.read()?.let { try { JSONObject(it.toString(Charsets.UTF_8)) } finally { it.fill(0) } }
    private val mutableState = MutableStateFlow(runCatching { secret()?.let { GoogleSyncStatus(true,it.getString("email"),lastSync = preferences.getLong("last",0)) } }.getOrNull() ?: GoogleSyncStatus())
    val state = mutableState.asStateFlow()
    fun error(message: String) { mutableState.value = mutableState.value.copy(busy = false,message = message) }
    suspend fun authorizeAndSync(authorize: suspend (String?) -> String) {
        repeat(2) { attempt ->
            val token = authorize(state.value.email.takeIf { state.value.connected })
            try {
                withContext(Dispatchers.IO) { if(state.value.connected) sync(token) else connect(token) }
                return
            } catch(error: DriveSyncError) {
                if(!error.authorizationRequired || attempt == 1) throw error
            }
        }
    }
    private suspend fun withToken(token: String, action: () -> Unit) {
        try { action() }
        catch(error: DriveSyncError) {
            // Revoked tokens remain cached by Play Services until explicitly cleared.
            if(error.authorizationRequired) clearToken(token)
            throw error
        }
    }
    suspend fun connect(token: String) = mutex.withLock {
        try {
            mutableState.value = mutableState.value.copy(busy = true,message = appText(R.string.retrieving_google_data))
            withToken(token) {
                val drive = driveFactory(token); val (owner,email) = drive.identity()
                val record = JSONObject().put("owner",owner).put("email",email)
                synchronize(drive,record)
                vault.write(record.toString().toByteArray())
                preferences.edit().putBoolean("enabled",true).apply()
                mutableState.value = mutableState.value.copy(connected = true,email = email)
                schedule(context,periodic = true,immediate = false)
            }
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }
    suspend fun sync(token: String? = null) = mutex.withLock {
        val saved = secret() ?: return@withLock
        mutableState.value = mutableState.value.copy(busy = true,message = appText(R.string.syncing))
        try {
            val accessToken = token ?: Identity.getAuthorizationClient(context).authorize(authorization(saved.getString("email"))).awaitResult().let { result ->
                if (result.hasResolution()) throw DriveSyncError(false,appText(R.string.open_settings_to_renew_google_access))
                result.accessToken ?: throw DriveSyncError(false,appText(R.string.google_access_is_unavailable))
            }
            withToken(accessToken) {
                val drive = driveFactory(accessToken)
                require(drive.identity().first == saved.getString("owner")) { appText(R.string.the_google_account_does_not_match_the_active_sync_account) }
                synchronize(drive,saved)
                vault.write(JSONObject().put("owner",saved.getString("owner")).put("email",saved.getString("email")).toString().toByteArray())
            }
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }
    private fun synchronize(drive: GoogleDriveStore, secret: JSONObject) {
        check(!trips.status.value.recording) { appText(R.string.sync_will_resume_when_the_trip_ends) }
        val registry = ScooterRegistry(context)
        var remote = emptyList<SyncRecord>()
        var downloaded = 0L
        val files = drive.files()
        for(file in files) {
            val bytes = drive.download(file)
            val clear = PortableArchive.unpack(bytes)
            try {
                downloaded += clear.size
                require(downloaded <= 128L * 1024 * 1024) { appText(R.string.sync_archives_exceed_this_version_s_limit) }
                val wrapper = JSONObject(clear.toString(Charsets.UTF_8))
                require(wrapper.getString("owner") == secret.getString("owner")) { appText(R.string.archive_belongs_to_a_different_account) }
                remote = SyncRecord.merge(remote+SyncRecord.decode(wrapper.getJSONObject("archive").toString().toByteArray()))
                require(remote.size <= 20_000) { appText(R.string.sync_archive_is_too_large) }
            } finally { clear.fill(0) }
        }
        val merged = SyncRecord.merge(remote)
        registry.validateSync(merged); trips.validateSync(merged); validatePreferences(merged)
        // Download and validate every file before changing local data or uploading anything.
        registry.mergeSync(merged); trips.mergeSync(merged); mergePreferences(merged)
        val all = SyncRecord.merge(registry.syncRecords()+trips.syncRecords()+preferenceRecord())
        val wrapper = JSONObject().put("owner",secret.getString("owner")).put("archive",JSONObject(SyncRecord.encode(all).toString(Charsets.UTF_8)))
        val clear = wrapper.toString().toByteArray()
        val compressed = try { PortableArchive.compress(clear) } finally { clear.fill(0) }
        val device = preferences.getString("device",null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("device",it).commit() }
        val name = "sentinel-sync-v2-$device.sentinel"
        drive.upload(name,files.firstOrNull { it.name == name },compressed)
        val now = System.currentTimeMillis(); preferences.edit().putLong("last",now).apply()
        mutableState.value = mutableState.value.copy(lastSync = now,message = appText(R.string.data_synced),revision = mutableState.value.revision+1)
    }
    private fun preferenceRecord(): List<SyncRecord> {
        val p = context.getSharedPreferences("trips",Context.MODE_PRIVATE)
        val modified = p.getLong("modified",0)
        return if(modified == 0L) emptyList() else listOf(SyncRecord("preferences","automatic-trips",modified,false,JSONObject().put("enabled",trips.enabled.value)))
    }
    private fun validatePreferences(records: List<SyncRecord>) {
        records.filter { it.kind == "preferences" }.forEach { require(it.id == "automatic-trips" && !it.deleted); it.data.getBoolean("enabled") }
    }
    private fun mergePreferences(records: List<SyncRecord>) {
        val p = context.getSharedPreferences("trips",Context.MODE_PRIVATE)
        records.find { it.kind == "preferences" }?.takeIf { it.modified > p.getLong("modified",0) }?.let {
            val enabled = it.data.getBoolean("enabled")
            p.edit().putBoolean("automatic",enabled).putLong("modified",it.modified).apply(); trips.enabled.value = enabled
        }
    }
    suspend fun disconnect() = mutex.withLock {
        preferences.edit().putBoolean("enabled",false).apply()
        WorkManager.getInstance(context).cancelUniqueWork("google-sync-now")
        WorkManager.getInstance(context).cancelUniqueWork("google-sync-periodic")
        vault.delete(); mutableState.value = GoogleSyncStatus(message = appText(R.string.account_disconnected_local_data_and_drive_archives_are_kept))
    }
}

internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if(continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if(continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

class GoogleSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        if (!applicationContext.getSharedPreferences("google-sync",Context.MODE_PRIVATE).getBoolean("enabled",false)) return Result.success()
        val sync = (applicationContext as SentinelApplication).googleSync
        return try { sync.sync(); Result.success() }
        catch(error: CancellationException) { throw error }
        catch(error: Exception) {
            sync.error(googleSyncError(error))
            if(runAttemptCount < 3 && (error !is DriveSyncError || error.retryable) && error !is IllegalArgumentException) Result.retry() else Result.failure()
        }
    }
}

internal fun googleSyncError(error: Exception): String = when(error) {
    is DriveSyncError -> error.message.orEmpty()
    is com.google.android.gms.common.api.ApiException -> if(error.statusCode == 10) appText(R.string.google_access_is_not_configured_for_this_app_complete_the_google_cloud) else appText(R.string.google_access_failed_check_google_play_services_and_try_again)
    is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { message -> listOf(appText(R.string.archive_too_large), appText(R.string.sync_archives), appText(R.string.too_many_archives), appText(R.string.archive_belongs), appText(R.string.the_google_account), appText(R.string.sync_will)).any(message::startsWith) }
        ?: appText(R.string.incompatible_archive_no_cloud_files_were_overwritten)
    else -> appText(R.string.sync_failed_your_local_data_remains_available)
}
