
package com.example.livelyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        // Communication with MainActivity
        const val ACTION_TRANSCRIPTION =
            "com.example.livelyrics.TRANSCRIPTION"

        const val EXTRA_RECOGNIZED_TEXT = "recognized_text"
        const val EXTRA_LANGUAGE = "language"

        private const val CHANNEL_ID = "livelyrics_audio"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "LiveLyricsAudio"

        private const val SAMPLE_RATE = 44100
        private const val CHUNK_SECONDS = 10

        private const val BACKEND_URL =
            "http://10.226.168.172:8000/transcribe"
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false

    private val uploadInProgress = AtomicBoolean(false)
    private val uploadExecutor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        try {
            createNotificationChannel()

            val notification = Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle("LiveLyrics")
                .setContentText("Capturing and processing music audio")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

            val resultCode = intent?.getIntExtra(
                EXTRA_RESULT_CODE,
                0
            ) ?: 0

            @Suppress("DEPRECATION")
            val resultData = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultData == null) {
                throw IllegalStateException(
                    "MediaProjection permission data is missing"
                )
            }

            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            mediaProjection = manager.getMediaProjection(
                resultCode,
                resultData
            )

            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "MediaProjection stopped")
                        stopSelf()
                    }
                },
                Handler(Looper.getMainLooper())
            )

            audioRecord =
                AudioCaptureManager.createAudioRecorder(
                    this,
                    mediaProjection!!
                )

            audioRecord?.startRecording()

            if (
                audioRecord?.recordingState !=
                AudioRecord.RECORDSTATE_RECORDING
            ) {
                throw IllegalStateException(
                    "Audio recorder did not start"
                )
            }

            startReadingAudio()

            Log.d(TAG, "Audio capture started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startReadingAudio() {

        isRecording = true

        recordingThread = Thread {

            val chunkSamples = ShortArray(
                SAMPLE_RATE * CHUNK_SECONDS
            )

            var samplesCollected = 0

            while (isRecording) {

                val remaining =
                    chunkSamples.size - samplesCollected

                val count = audioRecord?.read(
                    chunkSamples,
                    samplesCollected,
                    remaining
                ) ?: -1

                if (count > 0) {

                    samplesCollected += count

                    if (samplesCollected >= chunkSamples.size) {

                        Log.d(
                            TAG,
                            "Collected ${CHUNK_SECONDS} seconds of audio"
                        )

                        val wavData = createWavFile(chunkSamples)

                        if (uploadInProgress.compareAndSet(false, true)) {

                            uploadExecutor.execute {
                                try {
                                    uploadAudio(wavData)
                                } finally {
                                    uploadInProgress.set(false)
                                }
                            }

                        } else {
                            Log.w(
                                TAG,
                                "Previous upload still running. Skipping chunk."
                            )
                        }

                        samplesCollected = 0
                    }

                } else if (count < 0) {

                    if (isRecording) {
                        Log.e(
                            TAG,
                            "AudioRecord read error: $count"
                        )
                    }

                    break
                }
            }

        }.apply {
            name = "LiveLyricsAudioReader"
            start()
        }
    }

    private fun createWavFile(samples: ShortArray): ByteArray {

        val pcmData = ByteArrayOutputStream()

        for (sample in samples) {
            val value = sample.toInt()

            pcmData.write(value and 0xFF)
            pcmData.write((value shr 8) and 0xFF)
        }

        val audioBytes = pcmData.toByteArray()
        val wavData = ByteArrayOutputStream()

        val totalDataLength = audioBytes.size + 36

        fun writeInt(value: Int) {
            wavData.write(value and 0xFF)
            wavData.write((value shr 8) and 0xFF)
            wavData.write((value shr 16) and 0xFF)
            wavData.write((value shr 24) and 0xFF)
        }

        fun writeShort(value: Int) {
            wavData.write(value and 0xFF)
            wavData.write((value shr 8) and 0xFF)
        }

        wavData.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeInt(totalDataLength)
        wavData.write("WAVE".toByteArray(Charsets.US_ASCII))

        wavData.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeInt(16)
        writeShort(1)
        writeShort(1)
        writeInt(SAMPLE_RATE)
        writeInt(SAMPLE_RATE * 2)
        writeShort(2)
        writeShort(16)

        wavData.write("data".toByteArray(Charsets.US_ASCII))
        writeInt(audioBytes.size)
        wavData.write(audioBytes)

        return wavData.toByteArray()
    }

    private fun uploadAudio(wavData: ByteArray) {

        var connection: HttpURLConnection? = null

        try {

            val boundary = "LiveLyricsBoundary123"

            connection = URL(BACKEND_URL)
                .openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )

            connection.outputStream.use { output ->

                output.write(
                    "--$boundary\r\n".toByteArray()
                )

                output.write(
                    "Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n"
                        .toByteArray()
                )

                output.write(
                    "Content-Type: audio/wav\r\n\r\n"
                        .toByteArray()
                )

                output.write(wavData)

                output.write(
                    "\r\n--$boundary--\r\n".toByteArray()
                )

                output.flush()
            }

            val responseCode = connection.responseCode

            val responseStream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val response = responseStream?.use { stream ->
                BufferedReader(
                    InputStreamReader(stream)
                ).readText()
            } ?: ""

            if (responseCode in 200..299) {

                Log.d(TAG, "WHISPER TRANSCRIPTION: $response")

                // Read the JSON response from Python
                val json = JSONObject(response)

                val recognizedText =
                    json.optString("recognized_text", "").trim()

                val language =
                    json.optString("language", "unknown")

                if (recognizedText.isNotBlank()) {

                    // Send recognized lyrics to MainActivity
                    val transcriptionIntent =
                        Intent(ACTION_TRANSCRIPTION).apply {

                            setPackage(packageName)

                            putExtra(
                                EXTRA_RECOGNIZED_TEXT,
                                recognizedText
                            )

                            putExtra(
                                EXTRA_LANGUAGE,
                                language
                            )
                        }

                    sendBroadcast(transcriptionIntent)

                    Log.d(
                        TAG,
                        "Transcription sent to Android UI"
                    )
                }

            } else {

                Log.e(
                    TAG,
                    "Backend error $responseCode: $response"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Audio upload failed",
                e
            )

        } finally {
            connection?.disconnect()
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "LiveLyrics Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            val notificationManager =
                getSystemService(
                    NotificationManager::class.java
                )

            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {

        isRecording = false

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord stop: ${e.message}")
        }

        recordingThread?.join(1000)

        audioRecord?.release()
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null

        uploadExecutor.shutdown()

        stopForeground(STOP_FOREGROUND_REMOVE)

        Log.d(TAG, "Audio capture stopped")

        super.onDestroy()
    }
}
