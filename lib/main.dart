import 'dart:async';

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
  // Dart has no low-latency raw audio loopback API. The native foreground
  // service keeps running in the background once started.
  static const _channel = MethodChannel('soundamp/control');

  static const _modePhoneMic = 'phone_mic';
  static const _modeEarphoneMic = 'earphone_mic';

  bool _running = false;
  double _gain = 1.0;
  String _mode = _modePhoneMic;
  String _status = 'Idle';
  Timer? _poll;
  DateTime _startedAt = DateTime.now();

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

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
    if (_mode == _modeEarphoneMic) {
      // Android 12+ needs this to talk to Bluetooth earphones.
      final bt = await Permission.bluetoothConnect.request();
      if (!bt.isGranted) {
        setState(() => _status = 'Bluetooth permission is required');
        return;
      }
    }
    // Android 13+ requires a runtime prompt to show the ongoing notification.
    if (await Permission.notification.isDenied) {
      await Permission.notification.request();
    }

    try {
      await _channel.invokeMethod('start', {'gain': _gain, 'mode': _mode});
      _startedAt = DateTime.now();
      setState(() {
        _running = true;
        _status = _mode == _modeEarphoneMic
            ? 'Listening through your earphone mic… playing on this phone'
            : 'Listening… audio is being sent to your earphones';
      });
      _startPolling();
    } on PlatformException catch (e) {
      setState(() => _status = 'Could not start: ${e.message}');
    }
  }

  // The service can stop itself (e.g. the earphone mic couldn't connect),
  // so check on it periodically and show why.
  void _startPolling() {
    _poll?.cancel();
    _poll = Timer.periodic(const Duration(seconds: 2), (_) async {
      if (!_running) return;
      if (DateTime.now().difference(_startedAt).inSeconds < 8) return;
      try {
        final s = await _channel.invokeMapMethod<String, dynamic>('status');
        if (s != null && s['running'] != true) {
          _poll?.cancel();
          setState(() {
            _running = false;
            _status = (s['error'] as String?) ?? 'Stopped';
          });
        }
      } on PlatformException catch (_) {}
    });
  }

  Future<void> _stop() async {
    _poll?.cancel();
    try {
      await _channel.invokeMethod('stop');
    } on PlatformException catch (_) {
      // Treat as stopped either way.
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
    final earphoneMode = _mode == _modeEarphoneMic;
    return Scaffold(
      appBar: AppBar(title: const Text('Sound Amplifier')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          children: [
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(
                  value: _modePhoneMic,
                  icon: Icon(Icons.phone_android),
                  label: Text('Phone → Earphones'),
                ),
                ButtonSegment(
                  value: _modeEarphoneMic,
                  icon: Icon(Icons.headset_mic),
                  label: Text('Earphones → Phone'),
                ),
              ],
              selected: {_mode},
              // Can't switch modes while running.
              onSelectionChanged:
                  _running ? null : (v) => setState(() => _mode = v.first),
            ),
            const SizedBox(height: 32),
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
              max: 8.0,
              divisions: 80,
              label: '${_gain.toStringAsFixed(1)}x',
              onChanged: _updateGain,
            ),
            const SizedBox(height: 24),
            Text(
              earphoneMode
                  ? 'Uses your Bluetooth earphone\'s microphone and plays the '
                      'sound on this phone\'s speaker. Earphones only offer '
                      'their mic in call quality, so it will sound thinner '
                      'than music. Keep the phone away from the earphones to '
                      'avoid a squealing feedback loop.'
                  : 'Captures ambient sound from the phone mic and streams it '
                      'live to your connected Bluetooth earphones.',
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.grey),
            ),
            const SizedBox(height: 12),
            const Text(
              'Keeps running in the background once started. Android shows a '
              'persistent notification the entire time the mic is active. '
              'Only listen to conversations you\'re part of or have '
              'permission to hear.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }
}
