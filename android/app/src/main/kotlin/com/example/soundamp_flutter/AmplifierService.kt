package com.example.soundamp_flutter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Captures audio from the phone's microphone and plays it back live through
 * whatever audio output is currently active (Bluetooth earphones, if
 * connected). This is the same simple "sound amplifier" style
 * assistive-listening service as the native version of this app, just
 * driven by Intents from Flutter instead of an Activity binding.
 *
 * Background behaviour: this is a foreground service. Once started it
 * keeps running -- including while the app is minimized or the phone
 * screen is off -- until explicitly stopped or the system kills it under
 * extreme memory pressure. Android requires (and this shows) a persistent,
 * non-dismissible notification the entire time the microphone is active:
 * there is no silent or hidden background mode, by design.
 */
class AmplifierService : Service() {

    companion object {
        const val ACTION_START = "com.example.soundamp_flutter.action.START"
        const val ACTION_STOP = "com.example.soundamp_flutter.action.STOP"
        const val ACTION_SET_GAIN = "com.example.soundamp_flutter.action.SET_GAIN"
        const val EXTRA_GAIN = "gain"

        private const val CHANNEL_ID = "amplifier_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 44100
    }

    @Volatile private var isRunning = false
    @Volatile private var gain = 1.0f // 1.0 = no boost

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var workerThread: Thread? = null

    // Simple one-pole high-pass filter state, used to cut low-frequency
    // rumble/hum (wind, handling noise, AC hum) before boosting. Only ever
    // touched from the worker thread, so no synchronization needed.
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    // ~120Hz cutoff at 44.1kHz: alpha = RC / (RC + dt)
    private val hpAlpha = 0.983f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                gain = (intent.getFloatExtra(EXTRA_GAIN, 1.0f)).coerceIn(0.0f, 4.0f)
                startAmplifying()
            }
            ACTION_SET_GAIN -> {
                gain = (intent.getFloatExtra(EXTRA_GAIN, 1.0f)).coerceIn(0.0f, 4.0f)
            }
            ACTION_STOP -> stopAmplifying()
        }
        // Not sticky: if the OS kills this service it should stay dead
        // until the user explicitly starts it again, rather than silently
        // relaunching and re-opening the mic on its own.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAmplifying()
        super.onDestroy()
    }

    private fun startAmplifying() {
        if (isRunning) return

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val minBufIn = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val minBufOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBufIn, minBufOut) * 2

        // VOICE_COMMUNICATION (rather than plain MIC) routes capture through
        // the phone's voice-call audio pipeline, which most OEMs tune with
        // real noise suppression and echo cancellation -- MIC is usually
        // raw, untouched audio. The AudioTrack below stays on the MEDIA
        // path so playback still goes to Bluetooth normally; only the
        // input side changes.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.enabled = true
        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(record.audioSessionId)?.enabled = true
        }

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        audioRecord = record
        audioTrack = track
        isRunning = true
        hpPrevIn = 0f
        hpPrevOut = 0f

        record.startRecording()
        track.play()

        workerThread = thread(name = "amplifier-worker") {
            val buffer = ShortArray(bufSize / 2)
            while (isRunning) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        // High-pass filter first, to strip low-frequency
                        // rumble/hum before it gets amplified along with
                        // everything else.
                        val x = buffer[i].toFloat()
                        val y = hpAlpha * (hpPrevOut + x - hpPrevIn)
                        hpPrevIn = x
                        hpPrevOut = y

                        val boosted = (y * gain).toInt()
                        buffer[i] = boosted.coerceIn(
                            Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                        ).toShort()
                    }
                    track.write(buffer, 0, read)
                }
            }
        }
    }

    private fun stopAmplifying() {
        if (!isRunning) {
            stopForegroundCompat()
            return
        }
        isRunning = false
        workerThread?.join(500)
        workerThread = null

        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null

        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioTrack = null

        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sound Amplifier",
                NotificationManager.IMPORTANCE_LOW
            )
            manager?.createNotificationChannel(channel)
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sound Amplifier is active")
            .setContentText("Microphone audio is being streamed to your earphones. Tap to open.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }
}
