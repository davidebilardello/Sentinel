package it.bilar.sentinel

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }
    private val model by viewModels<ScooterViewModel>()
    private val trips by viewModels<TripsViewModel>()
    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        trips.refreshPermission()
        trips.synchronize()
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (model.bluetoothEnabled()) model.connectSelected() else model.reportBluetoothOff()
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (model.hasPermissions()) findScooter() else model.reportPermissionDenied()
    }

    private fun findScooter() {
        if (!model.hasPermissions()) permissions.launch(model.requiredPermissions())
        else if (!model.bluetoothEnabled()) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        else model.connectSelected()
    }

    private fun requestTripPermissions() {
        trips.markPermissionAsked()
        locationPermissions.launch(trips.permissions())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val cloud by (application as SentinelApplication).googleSync.state.collectAsStateWithLifecycle()
            LaunchedEffect(cloud.revision) { model.refreshSavedScooters(); trips.synchronize() }
            LaunchedEffect(state.connectionRequest) { if (model.canAutoConnect()) findScooter() }
            LaunchedEffect(state.connected) {
                if (state.connected) {
                    trips.refreshPermission()
                    if (trips.shouldAskPermission()) requestTripPermissions() else trips.synchronize()
                }
            }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                model.reloadYouFsProfile()
                if (model.canAutoConnect() && model.bluetoothEnabled()) model.connectSelected()
                trips.synchronize()
                GoogleSync.schedule(application)
            }
            SentinelApp(state, onConnect = ::findScooter, onDisconnect = model::disconnect,
                onDemo = model::startDemo, onSend = model::send, onDevice = model::connect,
                onStopScan = model::closeDiscovery, onClearLogs = model::clearLogs,
                onScooter = model::selectScooter, onAddJeep = { model.prepareAddSentinel(); findScooter() },
                onRemoveScooter = model::removeSelectedScooter, onDebug = model::setDebug, onExitDemo = model::exitDemo,
                onAddYouFsProfile = model::importYouFsProfile,
                onYouFsQuery = model::queryYouFs, onYouFsSend = model::sendYouFs,
                tripsModel = trips, onTripPermission = ::requestTripPermissions,onErrorShown = model::clearUserError)
        }
    }
}
