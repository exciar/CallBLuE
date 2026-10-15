package dev.blue

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.blue.call.CallController
import dev.blue.call.CallSession

class MainActivity : ComponentActivity() {

    private val permissions = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION) // BLE scanning needs it up to Android 11
        }
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startIfAllowed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (permissions.all(::granted)) startIfAllowed() else request.launch(permissions)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startIfAllowed() {
        if (permissions.all(::granted)) BlueService.start(this)
    }

    @Composable
    private fun Screen() {
        val peers by BlueService.peers.collectAsState()
        val call by CallController.call.collectAsState()
        val bluetoothOn by BlueService.bluetoothOn.collectAsState()
        val prefs = remember { Prefs(this) }
        var name by remember { mutableStateOf(prefs.name) }
        val adapter = remember { getSystemService(BluetoothManager::class.java).adapter }

        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("BLuE", style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { prefs.name = name; BlueService.instance?.startDiscovery() }) { Text("Save") }
            }
            Text(
                when {
                    adapter == null -> "This phone has no Bluetooth."
                    bluetoothOn == null -> "Allow Bluetooth and microphone access to continue."
                    bluetoothOn == false -> "Bluetooth is off. Turn it on to call nearby phones."
                    else -> "Long range: ${yesNo(adapter.isLeCodedPhySupported)} · Extended advertising: ${yesNo(adapter.isLeExtendedAdvertisingSupported)}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            XiaomiSettings(prefs)
            call?.let { CallCard(it) }
            Text("Nearby", style = MaterialTheme.typography.titleMedium)
            if (peers.isEmpty()) Text("Looking for phones running BLuE…", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(peers, key = { it.key }) { peer ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(peer.name.ifBlank { "Unnamed phone" })
                            val tags = listOfNotNull("${peer.rssi} dBm", "long range".takeIf { peer.longRange }, "Xiaomi".takeIf { peer.xiaomi })
                            Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            if (BlueService.controller?.dial(peer) != true) {
                                Toast.makeText(this@MainActivity, "Can't call right now. Check Bluetooth or end the current call.", Toast.LENGTH_LONG).show()
                            }
                        }, enabled = call == null) { Text("Call") }
                    }
                }
            }
        }
    }

    /** Opt-in compatibility with stock Xiaomi Offline Communication, with its privacy cost spelled out. */
    @Composable
    private fun XiaomiSettings(prefs: Prefs) {
        val available = remember { BlueService.xiaomiModeAvailable(this) }
        val builtIn = remember { BlueService.hasBuiltInXiaomiFeature(this) }
        var on by remember { mutableStateOf(prefs.xiaomiMode && available) }
        var number by remember { mutableStateOf(prefs.xiaomiNumber) }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Talk to Xiaomi phones", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            builtIn -> "This phone already has Xiaomi Offline Communication built in."
                            !available -> "Needs long-range Bluetooth, which this phone doesn't have."
                            else -> "Long range, works with Xiaomi's built-in Offline Communication."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = on, enabled = available, onCheckedChange = {
                    on = it
                    prefs.xiaomiMode = it
                    BlueService.instance?.startDiscovery()
                })
            }
            if (on) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        number, { number = it.filter { c -> c.isDigit() || c == '+' }.take(24) },
                        label = { Text("Your phone number") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { prefs.xiaomiNumber = number; BlueService.instance?.startDiscovery() }) { Text("Save") }
                }
                Text(
                    "Xiaomi phones find each other by phone number, and their format broadcasts it openly: anyone nearby with a Bluetooth scanner can read it while this is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    @Composable
    private fun CallCard(c: CallController.CallUi) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.peer, style = MaterialTheme.typography.titleLarge)
                Text(when (c.state) {
                    CallSession.State.CONNECTED -> "Connecting…"
                    CallSession.State.SECURED -> if (c.incoming) "Connecting…" else "Ringing…"
                    CallSession.State.RINGING -> "Incoming call"
                    CallSession.State.ACTIVE -> "In call"
                    CallSession.State.ENDED -> "Call ended"
                })
                c.checkCode?.let { Text("Check code $it: it should match the other phone", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.padding(2.dp))
                val controller = BlueService.controller
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.state == CallSession.State.RINGING) {
                        Button(onClick = { controller?.answer() }) { Text("Answer") }
                        OutlinedButton(onClick = { controller?.reject() }) { Text("Decline") }
                    } else {
                        Button(onClick = { controller?.hangUp() }) { Text("Hang up") }
                        if (c.state == CallSession.State.ACTIVE) {
                            OutlinedButton(onClick = { controller?.setMuted(!c.muted) }) { Text(if (c.muted) "Unmute" else "Mute") }
                            val speakerLabel = if (c.speaker) "Speaker on" else "Speaker"
                            if (c.speaker) {
                                Button(onClick = { controller?.setSpeaker(false) }) { Text(speakerLabel) }
                            } else {
                                OutlinedButton(onClick = { controller?.setSpeaker(true) }) { Text(speakerLabel) }
                            }
                        }
                    }
                }
                if (c.muted) {
                    Text("Your microphone is muted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"
}
