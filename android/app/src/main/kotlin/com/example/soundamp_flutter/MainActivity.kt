package com.example.soundamp_flutter

import android.content.Intent
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Bridges Flutter's UI to the native AmplifierService. All audio work
 * happens in the service; this only forwards start/stop/gain commands as
 * Intents and reports whether the service is still running.
 */
class MainActivity : FlutterActivity() {
    private val channelName = "soundamp/control"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "start" -> {
                        val gain = (call.argument<Double>("gain") ?: 1.0).toFloat()
                        val mode = call.argument<String>("mode") ?: AmplifierService.MODE_PHONE_MIC
                        val intent = Intent(this, AmplifierService::class.java).apply {
                            action = AmplifierService.ACTION_START
                            putExtra(AmplifierService.EXTRA_GAIN, gain)
                            putExtra(AmplifierService.EXTRA_MODE, mode)
                        }
                        startForegroundService(intent)
                        result.success(null)
                    }
                    "stop" -> {
                        val intent = Intent(this, AmplifierService::class.java).apply {
                            action = AmplifierService.ACTION_STOP
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "setGain" -> {
                        val gain = (call.argument<Double>("gain") ?: 1.0).toFloat()
                        val intent = Intent(this, AmplifierService::class.java).apply {
                            action = AmplifierService.ACTION_SET_GAIN
                            putExtra(AmplifierService.EXTRA_GAIN, gain)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "status" -> result.success(
                        mapOf(
                            "running" to AmplifierService.running,
                            "error" to AmplifierService.lastError
                        )
                    )
                    else -> result.notImplemented()
                }
            }
    }
}
