
package com.example.livelyrics

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.livelyrics.ui.theme.LiveLyricsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LiveLyricsTheme {
                LiveLyricsScreen()
            }
        }
    }
}

// Send text safely through the Bluetooth socket
private fun sendTextToLcd(
    bluetoothSocket: BluetoothSocket,
    text: String
) {
    synchronized(bluetoothSocket) {
        bluetoothSocket.outputStream.write(
            (text.trim() + "\n").toByteArray(Charsets.UTF_8)
        )

        bluetoothSocket.outputStream.flush()
    }
}

@Composable
fun LiveLyricsScreen() {

    val activity = LocalContext.current as ComponentActivity

    var connectionStatus by remember {
        mutableStateOf("Not Connected")
    }

    var connected by remember {
        mutableStateOf(false)
    }

    var message by remember {
        mutableStateOf("")
    }

    var socket by remember {
        mutableStateOf<BluetoothSocket?>(null)
    }

    var captureStatus by remember {
        mutableStateOf("Audio capture not started")
    }

    var recognizedLyrics by remember {
        mutableStateOf("")
    }

    var detectedLanguage by remember {
        mutableStateOf("")
    }

    val sppUUID = UUID.fromString(
        "00001101-0000-1000-8000-00805F9B34FB"
    )

    // Receive transcription from AudioCaptureService
    DisposableEffect(activity) {

        val transcriptionReceiver = object : BroadcastReceiver() {

            override fun onReceive(
                context: android.content.Context?,
                intent: Intent?
            ) {

                if (
                    intent?.action ==
                    AudioCaptureService.ACTION_TRANSCRIPTION
                ) {

                    val text = intent.getStringExtra(
                        AudioCaptureService.EXTRA_RECOGNIZED_TEXT
                    ) ?: ""

                    val language = intent.getStringExtra(
                        AudioCaptureService.EXTRA_LANGUAGE
                    ) ?: "unknown"

                    if (text.isNotBlank()) {
                        recognizedLyrics = text
                        detectedLanguage = language
                    }
                }
            }
        }

        val filter = IntentFilter(
            AudioCaptureService.ACTION_TRANSCRIPTION
        )

        ContextCompat.registerReceiver(
            activity,
            transcriptionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            activity.unregisterReceiver(transcriptionReceiver)
        }
    }

    // Automatically send every new transcription to Arduino
    LaunchedEffect(recognizedLyrics, connected, socket) {

        val currentSocket = socket
        val textToSend = recognizedLyrics

        if (
            textToSend.isNotBlank() &&
            connected &&
            currentSocket != null
        ) {

            try {

                withContext(Dispatchers.IO) {
                    sendTextToLcd(
                        currentSocket,
                        textToSend
                    )
                }

                connectionStatus = "Lyrics automatically sent to LCD"

            } catch (e: Exception) {

                connectionStatus =
                    "Automatic send failed: ${e.message}"

                connected = false
                socket = null
            }
        }
    }

    // Bluetooth connection
    val connectToHC05 = {

        connectionStatus = "Connecting..."

        Thread {

            try {

                val adapter = BluetoothAdapter.getDefaultAdapter()

                if (adapter == null) {

                    activity.runOnUiThread {
                        connectionStatus = "Bluetooth unavailable"
                    }

                } else {

                    val device = adapter.bondedDevices
                        .firstOrNull { it.name == "HC-05" }

                    if (device == null) {

                        activity.runOnUiThread {
                            connectionStatus = "HC-05 not paired"
                        }

                    } else {

                        val bluetoothSocket =
                            device.createRfcommSocketToServiceRecord(
                                sppUUID
                            )

                        bluetoothSocket.connect()

                        activity.runOnUiThread {
                            socket = bluetoothSocket
                            connected = true
                            connectionStatus = "Connected to HC-05"
                        }
                    }
                }

            } catch (e: Exception) {

                activity.runOnUiThread {
                    connected = false
                    connectionStatus =
                        "Connection failed: ${e.message}"
                }
            }

        }.start()
    }

    // Bluetooth permission
    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                connectToHC05()
            } else {
                connectionStatus = "Bluetooth permission denied"
            }
        }

    fun requestBluetoothConnection() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            bluetoothPermissionLauncher.launch(
                Manifest.permission.BLUETOOTH_CONNECT
            )

        } else {
            connectToHC05()
        }
    }

    // MediaProjection permission result
    val projectionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (
                result.resultCode == Activity.RESULT_OK &&
                result.data != null
            ) {

                val serviceIntent = Intent(
                    activity,
                    AudioCaptureService::class.java
                ).apply {

                    putExtra(
                        AudioCaptureService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )

                    putExtra(
                        AudioCaptureService.EXTRA_RESULT_DATA,
                        result.data
                    )
                }

                try {

                    ContextCompat.startForegroundService(
                        activity,
                        serviceIntent
                    )

                    captureStatus = "Capture service started"

                } catch (e: Exception) {

                    captureStatus =
                        "Could not start: ${e.message}"
                }

            } else {

                captureStatus = "Audio capture permission denied"
            }
        }

    // RECORD_AUDIO permission
    val audioPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                val manager = activity.getSystemService(
                    ComponentActivity.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                projectionLauncher.launch(
                    manager.createScreenCaptureIntent()
                )

            } else {

                captureStatus = "Audio permission denied"
            }
        }

    fun startAudioCapture() {

        if (
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            val manager = activity.getSystemService(
                ComponentActivity.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projectionLauncher.launch(
                manager.createScreenCaptureIntent()
            )

        } else {

            audioPermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    fun stopAudioCapture() {

        activity.stopService(
            Intent(activity, AudioCaptureService::class.java)
        )

        captureStatus = "Audio capture stopped"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {

        Text(
            text = "LiveLyrics",
            fontSize = 30.sp,
            color = MaterialTheme.colorScheme.primary
        )

        Text(text = "Your music, your language")

        Spacer(modifier = Modifier.height(20.dp))

        // Bluetooth section
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.padding(16.dp)
            ) {

                Text("BLUETOOTH CONNECTION")

                Spacer(modifier = Modifier.height(10.dp))

                Text(connectionStatus)

                Button(
                    onClick = {
                        requestBluetoothConnection()
                    },
                    enabled = !connected
                ) {
                    Text("Connect to HC-05")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Audio capture section
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.padding(16.dp)
            ) {

                Text(
                    text = "INTERNAL AUDIO CAPTURE",
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(captureStatus)

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        startAudioCapture()
                    }
                ) {
                    Text("Start Audio Capture")
                }

                OutlinedButton(
                    onClick = {
                        stopAudioCapture()
                    }
                ) {
                    Text("Stop Audio Capture")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "NOW PLAYING",
            fontSize = 16.sp
        )

        Text(
            text = "Music audio capture active",
            fontSize = 22.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Original lyrics
        Text(
            text = "Original Lyrics",
            fontSize = 20.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.padding(12.dp)
            ) {

                Text(
                    text = if (recognizedLyrics.isNotBlank()) {
                        recognizedLyrics
                    } else {
                        "Lyrics will appear here..."
                    },
                    fontSize = 17.sp
                )

                if (detectedLanguage.isNotBlank()) {

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Detected language: $detectedLanguage",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // English transliteration placeholder
        Text(
            text = "English Transliteration",
            fontSize = 20.sp
        )

        Text("English transliteration will appear here...")

        Spacer(modifier = Modifier.height(20.dp))

        // Manual Bluetooth message testing
        Text(
            text = "TEST BLUETOOTH MESSAGE",
            fontSize = 20.sp
        )

        OutlinedTextField(
            value = message,
            onValueChange = {
                message = it
            },
            label = {
                Text("Enter text for LCD")
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = {

                val currentSocket = socket

                if (
                    currentSocket != null &&
                    connected
                ) {

                    val textToSend = message

                    Thread {

                        try {

                            sendTextToLcd(
                                currentSocket,
                                textToSend
                            )

                        } catch (e: Exception) {

                            activity.runOnUiThread {

                                connectionStatus =
                                    "Send failed: ${e.message}"

                                connected = false
                                socket = null
                            }
                        }

                    }.start()
                }
            },
            enabled = connected && message.isNotBlank()
        ) {
            Text("Send to LCD")
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "16x2 LCD Display",
            fontSize = 20.sp
        )

        Text(
            text = "LCD Status: ${
                if (connected) "Bluetooth Connected"
                else "Not Connected"
            }"
        )
    }
}
