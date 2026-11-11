package com.webrtc.scrcpy.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Internal system audio capturer (Android 10+ AudioPlaybackCapture API)
 */
class AudioCaptureManager(private val mediaProjection: MediaProjection?) {
    private val TAG = "AudioCaptureManager"

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false
    private var recordThread: Thread? = null

    val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @RequiresApi(Build.VERSION_CODES.Q)
    fun startCapture(onAudioData: (ByteArray, Int) -> Unit) {
        val proj = mediaProjection ?: run {
            Log.w(TAG, "MediaProjection is null; cannot capture internal system audio")
            return
        }

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord?.startRecording()
            isRecording = true

            recordThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        onAudioData(buffer, read)
                    }
                }
            }.apply {
                name = "AudioRecordThread"
                start()
            }
            Log.i(TAG, "AudioPlaybackCapture internal audio capture ready")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start internal audio capture: ${e.message}")
        }
    }

    fun stopCapture() {
        isRecording = false
        try {
            recordThread?.join(500)
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping internal audio capture: ${e.message}")
        } finally {
            audioRecord = null
            recordThread = null
        }
    }
}

