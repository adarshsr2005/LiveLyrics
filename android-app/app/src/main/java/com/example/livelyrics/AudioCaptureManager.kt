
package com.example.livelyrics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import androidx.core.content.ContextCompat

object AudioCaptureManager {

    private const val SAMPLE_RATE = 44100

    fun createAudioRecorder(
        context: Context,
        mediaProjection: MediaProjection
    ): AudioRecord {

        // Check RECORD_AUDIO permission
        check(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            "RECORD_AUDIO permission not granted"
        }

        // Configure which app audio to capture
        val captureConfig =
            AudioPlaybackCaptureConfiguration.Builder(
                mediaProjection
            )
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

        // Configure audio format: 44.1 kHz, mono, PCM 16-bit
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        // Calculate buffer size
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        require(bufferSize > 0) {
            "Unable to determine audio buffer size"
        }

        // Create AudioRecord
        return AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }
}
