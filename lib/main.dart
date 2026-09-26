import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() => runApp(const SoundAmplifierApp());

class SoundAmplifierApp extends StatelessWidget {
  const SoundAmplifierApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Sound Amplifier',
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.teal),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  // All real audio work happens natively (see AmplifierService.kt), because
  // Dart has no low-latency raw audio loopback API. This channel just tells
  // the native foreground service to start/stop/adjust gain. The service
  // keeps running in the background on its own once started -- it does not
  // depend on the Flutter engine or this screen staying alive.
  static const _channel = MethodChannel('soundamp/control');

  bool _running = false;
  double _gain = 1.0;
  String _status = 'Idle';

  Future<void> _toggle() async {
    if (_running) {
      await _stop();
    } else {
      await _start();
    }
  }

  Future<void> _start() async {
    final micStatus = await Permission.microphone.request();
    if (!micStatus.isGranted) {
      setState(() => _status = 'Microphone permission is required');
      return;
    }
    // Android 13+ requires a runtime prompt to show the ongoing notification.
    if (await Permission.notification.isDenied) {
      await Permission.notification.request();
    }

    try {
      await _channel.invokeMethod('start', {'gain': _gain});
      setState(() {
        _running = true;
        _status = 'Listening… audio is being sent to your earphones';
      });
    } on PlatformException catch (e) {
      setState(() => _status = 'Could not start: ${e.message}');
    }
  }

  Future<void> _stop() async {
    try {
      await _channel.invokeMethod('stop');
    } on PlatformException catch (_) {
      // Nothing to do -- treat as stopped either way.
    }
    setState(() {
      _running = false;
      _status = 'Idle';
    });
  }

  Future<void> _updateGain(double value) async {
    setState(() => _gain = value);
    if (_running) {
      await _channel.invokeMethod('setGain', {'gain': _gain});
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Sound Amplifier')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              _status,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 32),
            FilledButton.icon(
              onPressed: _toggle,
              icon: Icon(_running ? Icons.stop : Icons.play_arrow),
              label: Text(_running ? 'Stop Listening' : 'Start Listening'),
            ),
            const SizedBox(height: 40),
            Text('Amplification: ${_gain.toStringAsFixed(1)}x'),
            Slider(
              value: _gain,
              min: 0.0,
              max: 4.0,
              divisions: 40,
              label: '${_gain.toStringAsFixed(1)}x',
              onChanged: _updateGain,
            ),
            const SizedBox(height: 24),
            const Text(
              'Captures ambient sound from the phone mic and streams it '
              'live to your connected Bluetooth earphones. Keeps running '
              'in the background once started -- Android shows a '
              'persistent notification the entire time the mic is active.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }
}
