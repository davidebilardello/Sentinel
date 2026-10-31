package it.bilar.sentinel

import android.app.Application
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File
import kotlinx.coroutines.*

class SentinelApplication : Application() {
    val tripDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val tripScope = CoroutineScope(SupervisorJob() + tripDispatcher)
    val trips by lazy { TripStore(this) }
    internal val googleSync by lazy { GoogleSync(this) }
    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "map-http"), 64L * 1024 * 1024))
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Sentinel (it.bilar.sentinel)").build())
            }.build())
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        tripScope.launch {
            try { trips.recoverInterrupted() }
            catch (e: Exception) { trips.status.value = RecordingStatus(message = appText(R.string.message_51,e.message .orEmpty())) }
        }
        GoogleSync.schedule(this,periodic = true)
    }
}
