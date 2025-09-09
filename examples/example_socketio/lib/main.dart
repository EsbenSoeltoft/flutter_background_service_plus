import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_background_service/flutter_background_service.dart';

import 'background_service.dart'; // where onStart/onIosBackground + initializeService live

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeService(); // config & (auto) start the "socket" tagged service
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});
  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final services = FlutterBackgroundService();
  late StreamSubscription<Map<String, dynamic>?> _sub;

  bool _running = false;
  String _last = '(no messages yet)';

  @override
  void initState() {
    super.initState();
    // listen to messages from the "socket" service
    _sub = services.on('socket', 'socket_event').listen((data) {
      setState(() {
        _last = 'socket_event: ${data?['payload']}';
      });
    });
    _refreshRunning();
  }

  Future<void> _refreshRunning() async {
    final r = await services.isServiceRunning('socket');
    if (mounted) setState(() => _running = r);
  }

  @override
  void dispose() {
    _sub.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final btnLabel = _running ? 'Stop Socket Service' : 'Start Socket Service';

    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Socket.IO Background Service')),
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Service running: $_running'),
              const SizedBox(height: 8),
              Text('Last message: $_last'),
              const SizedBox(height: 16),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  ElevatedButton(
                    onPressed: () async {
                      if (_running) {
                        services.invoke('socket', 'stopService');
                      } else {
                        await services.start('socket');
                      }
                      await _refreshRunning();
                    },
                    child: Text(btnLabel),
                  ),
                  ElevatedButton(
                    onPressed: () =>
                        services.invoke('socket', 'setAsForeground'),
                    child: const Text('FG Mode'),
                  ),
                  ElevatedButton(
                    onPressed: () =>
                        services.invoke('socket', 'setAsBackground'),
                    child: const Text('BG Mode'),
                  ),
                  ElevatedButton(
                    onPressed: () => services.invoke('socket', 'emit', {
                      'message': 'Hello from UI!',
                    }),
                    child: const Text('Send UI → Socket'),
                  ),
                ],
              ),
              const SizedBox(height: 24),
              const Text(
                'Note: update the ws:// URL in background_service.dart '
                'to your Socket.IO server.',
              ),
            ],
          ),
        ),
      ),
    );
  }
}
