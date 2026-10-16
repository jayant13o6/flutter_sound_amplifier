package com.example.soundamp_flutter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.tanh

/**
 * Live audio pass-through service with two modes:
 *
 *  - MODE_PHONE_MIC:    phone's microphone  -> Bluetooth earphones
 *                       (assistive-listening / sound-amplifier use)
 *  - MODE_EARPHONE_MIC: Bluetooth earphone's microphone -> phone's speaker
 *
 * It is a foreground service: once started it keeps running while the app
 * is minimized or the screen is off. Android requires (and this shows) a
 * persistent notification the whole time the microphone is active; the
 * service only starts on an explicit user action and does not restart
 * itself (START_NOT_STICKY).
 *
 * Note on MODE_EARPHONE_MIC: Bluetooth earphones expose their microphone
 * only through the hands-free call profile (SCO), which is narrowband /
 * wideband voice quality (8 or 16 kHz), not music quality. Routing to the
 * phone speaker while SCO is active is also device dependent.
 */
class AmplifierService : Service() {

    companion object {
        const val ACTION_START = "com.example.soundamp_flutter.action.START"
        const val ACTION_STOP = "com.example.soundamp_flutter.action.STOP"
        const val ACTION_SET_GAIN = "com.example.soundamp_flutter.action.SET_GAIN"
        const val EXTRA_GAIN = "gain"
        const val EXTRA_MODE = "mode"

        const val MODE_PHONE_MIC = "phone_mic"
        const val MODE_EARPHONE_MIC = "earphone_mic"

        const val MAX_GAIN = 8.0f

        private const val CHANNEL_ID = "amplifier_channel"
        private const val NOTIFICATION_ID = 1001

        // Read by MainActivity so Flutter can tell if the service stopped
        // by itself (e.g. earphone mic couldn't connect).
        @Volatile var running = false
        @Volatile var lastError: String? = null

        fun findBluetoothMic(am: AudioManager): AudioDeviceInfo? =
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
    }

    @Volatile private var isRunning = false
    @Volatile private var gain = 1.0f // 1.0 = no boost, up to MAX_GAIN
    private var workerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                gain = (intent.getFloatExtra(EXTRA_GAIN, 1.0f)).coerceIn(0.0f, MAX_GAIN)
                startAmplifying(intent.getStringExtra(EXTRA_MODE) ?: MODE_PHONE_MIC)
            }
            ACTION_SET_GAIN -> {
                gain = (intent.getFloatExtra(EXTRA_GAIN, 1.0f)).coerceIn(0.0f, MAX_GAIN)
            }
            ACTION_STOP -> stopAmplifying()
        }
        // Not sticky: if the OS kills this service it stays dead until the
        // user explicitly starts it again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAmplifying()
        super.onDestroy()
    }

    private fun startAmplifying(mode: String) {
        if (isRunning) return

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "Microphone permission is missing"
            stopSelf()
            return
        }

        lastError = null
        startForeground(NOTIFICATION_ID, buildNotification(mode))
        isRunning = true
        running = true

        workerThread = thread(name = "amplifier-worker") {
            try {
                runPipeline(mode)
            } catch (e: Exception) {
                lastError = e.message ?: "Audio error"
            }
            // If we got here while still "running", the pipeline ended on
            // its own (an error). Shut the service down cleanly.
            if (isRunning) {
                isRunning = false
                running = false
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun runPipeline(mode: String) {
        val earphoneMode = mode == MODE_EARPHONE_MIC
        // Bluetooth call audio is 16 kHz (wideband) at best; the phone mic
        // can do CD-rate.
        val sampleRate = if (earphoneMode) 16000 else 44100
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        var scoReceiver: BroadcastReceiver? = null
        var record: AudioRecord? = null
        var track: AudioTrack? = null

        try {
            var btMic: AudioDeviceInfo? = null

            if (earphoneMode) {
                val connected = CountDownLatch(1)
                scoReceiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        val state = i?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.countDown()
                    }
                }
                ContextCompat.registerReceiver(
                    this, scoReceiver,
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                    ContextCompat.RECEIVER_EXPORTED
                )
                am.startBluetoothSco()
                am.isBluetoothScoOn = true

                if (!connected.await(6, TimeUnit.SECONDS)) {
                    throw IllegalStateException(
                        "Couldn't open the earphone microphone. Make sure the earphones " +
                            "are connected and support calls (hands-free profile)."
                    )
                }
                btMic = findBluetoothMic(am)
            }

            val minBufIn = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val minBufOut = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = max(minBufIn, minBufOut) * 2

            // VOICE_COMMUNICATION routes capture through the voice pipeline,
            // which most phones tune with real noise suppression.
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            btMic?.let { record.setPreferredDevice(it) }

            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(record.audioSessionId)?.enabled = true
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(record.audioSessionId)?.enabled = true
            }

            track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (earphoneMode) {
                // Play on the phone itself, not back into the earphones.
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { track.setPreferredDevice(it) }
            }

            record.startRecording()
            track.play()

            // One-pole high-pass (~120 Hz) to cut rumble/hum before boosting.
            val rc = 1f / (2f * Math.PI.toFloat() * 120f)
            val hpAlpha = rc / (rc + 1f / sampleRate)
            var hpPrevIn = 0f
            var hpPrevOut = 0f

            val buffer = ShortArray(bufSize / 2)
            while (isRunning) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        val x = buffer[i].toFloat()
                        val y = hpAlpha * (hpPrevOut + x - hpPrevIn)
                        hpPrevIn = x
                        hpPrevOut = y

                        // Soft-clip so high gain rolls off smoothly instead
                        // of crackling.
                        val boosted = y * gain
                        val softClipped = tanh(boosted / 32768f) * 32768f
                        buffer[i] = softClipped.toInt().coerceIn(
                            Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                        ).toShort()
                    }
                    track.write(buffer, 0, read)
                } else if (read < 0) {
                    throw IllegalStateException("Microphone stopped delivering audio")
                }
            }
        } finally {
            record?.let {
                try { it.stop() } catch (_: Exception) {}
                it.release()
            }
            track?.let {
                try { it.stop() } catch (_: Exception) {}
                it.release()
            }
            if (earphoneMode) {
                try { am.stopBluetoothSco() } catch (_: Exception) {}
                am.isBluetoothScoOn = false
                scoReceiver?.let {
                    try { unregisterReceiver(it) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun stopAmplifying() {
        val wasRunning = isRunning
        isRunning = false
        running = false
        if (wasRunning) {
            workerThread?.join(1500)
        }
        workerThread = null
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

    private fun buildNotification(mode: String): Notification {
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

        val text = if (mode == MODE_EARPHONE_MIC)
            "Your earphone microphone is live and playing on this phone. Tap to open."
        else
            "Phone microphone audio is being streamed to your earphones. Tap to open."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sound Amplifier is active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }
}
