import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_background_service_android/flutter_background_service_android.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await _initializeServices();
  runApp(const MyApp());
}

Future<void> _initializeServices() async {
  // Shared notification setup (Android)
  const AndroidNotificationChannel channel = AndroidNotificationChannel(
    'my_foreground',
    'MY FOREGROUND SERVICE',
    description: 'This channel is used for important notifications.',
    importance: Importance.low,
  );

  final notifications = FlutterLocalNotificationsPlugin();
  if (Platform.isIOS || Platform.isAndroid) {
    await notifications.initialize(
      const InitializationSettings(
        iOS: DarwinInitializationSettings(),
        android: AndroidInitializationSettings('ic_bg_service_small'),
      ),
    );
  }

  await notifications
      .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>()
      ?.createNotificationChannel(channel);

  final services = FlutterBackgroundService();

  // ---- Configure and (auto) start DEFAULT service ----
  await services.configure(
    tag: 'default',
    iosConfiguration: IosConfiguration(
      autoStart: true,
      onForeground: onStart,
      onBackground: onIosBackground,
    ),
    androidConfiguration: AndroidConfiguration(
      onStart: onStart,
      autoStart: true,
      isForegroundMode: true,
      autoStartOnBoot: true,
      notificationChannelId: 'my_foreground',
      initialNotificationTitle: 'Default Service',
      initialNotificationContent: 'Initializing…',
      foregroundServiceNotificationId: 1001,
      // e.g. specialUse only (no location)
      foregroundServiceTypes: [AndroidForegroundType.specialUse],
    ),
  );

  // ---- Configure and (auto) start LOCATION service ----
  await services.configure(
    tag: 'location',
    serviceType:
        'location', // tells the plugin to start the Location service class
    iosConfiguration: IosConfiguration(
      autoStart: true,
      onForeground: onStart,
      onBackground: onIosBackground,
    ),
    androidConfiguration: AndroidConfiguration(
      onStart: onStart,
      autoStart: true,
      isForegroundMode: true,
      autoStartOnBoot: true,
      notificationChannelId: 'my_foreground',
      initialNotificationTitle: 'Location Service',
      initialNotificationContent: 'Initializing…',
      foregroundServiceNotificationId: 1002,
      // includes location + (optionally) specialUse
      foregroundServiceTypes: [
        AndroidForegroundType.location,
        AndroidForegroundType.specialUse,
      ],
    ),
  );

  // If you prefer manual start:
  // await services.start('default');
  // await services.start('location');
}

// ---------------- Background entrypoints ----------------

@pragma('vm:entry-point')
Future<bool> onIosBackground(ServiceInstance service) async {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();

  final sp = await SharedPreferences.getInstance();
  await sp.reload();
  final log = sp.getStringList('log') ?? <String>[];
  log.add('iOS BG @ ${DateTime.now().toIso8601String()}');
  await sp.setStringList('log', log);

  return true;
}

@pragma('vm:entry-point')
void onStart(ServiceInstance service) async {
  // Make sure plugins are registered
  DartPluginRegistrant.ensureInitialized();

  // Example shared state
  final prefs = await SharedPreferences.getInstance();
  await prefs.setString("hello", "world");

  final notifications = FlutterLocalNotificationsPlugin();

  if (service is AndroidServiceInstance) {
    service
        .on('setAsForeground')
        .listen((_) => service.setAsForegroundService());
    service
        .on('setAsBackground')
        .listen((_) => service.setAsBackgroundService());
  }

  service.on('stopService').listen((_) => service.stopSelf());

  // Emit updates every second
  Timer.periodic(const Duration(seconds: 1), (timer) async {
    if (service is AndroidServiceInstance) {
      if (await service.isForegroundService()) {
        // Option A: custom notification (id must match your config ID)
        // NOTE: If you don't want a custom notification, you can remove this and
        // keep only setForegroundNotificationInfo below.
        // Choose one approach to avoid double notifications.
        // notifications.show(
        //   1001,
        //   'Service running',
        //   'Tick ${DateTime.now()}',
        //   const NotificationDetails(
        //     android: AndroidNotificationDetails(
        //       'my_foreground',
        //       'MY FOREGROUND SERVICE',
        //       icon: 'ic_bg_service_small',
        //       ongoing: true,
        //     ),
        //   ),
        // );

        // Option B: built-in FGS notification text
        await service.setForegroundNotificationInfo(
          title: "Background Service",
          content: "Updated at ${DateTime.now()}",
        );
      }
    }

    // Log (visible in logcat)
    // You can include tag-aware behavior if you pass the tag to Dart; for simplicity
    // this example just emits a generic payload.
    final deviceInfo = DeviceInfoPlugin();
    String? device;
    if (Platform.isAndroid) {
      device = (await deviceInfo.androidInfo).model;
    } else if (Platform.isIOS) {
      device = (await deviceInfo.iosInfo).model;
    }

    service.invoke('update', {
      "current_date": DateTime.now().toIso8601String(),
      "device": device,
    });
  });
}

// ---------------- UI / Demo app ----------------

class MyApp extends StatefulWidget {
  const MyApp({Key? key}) : super(key: key);
  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final services = FlutterBackgroundService();

  String textDefault = "Stop Default Service";
  String textLocation = "Stop Location Service";

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Tagged Services Demo')),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const Text('Default service (tag: "default")',
                style: TextStyle(fontWeight: FontWeight.bold)),
            _ServiceControls(
              tag: 'default',
              startStopLabelBuilder: (running) =>
                  running ? 'Stop Default Service' : 'Start Default Service',
            ),
            const SizedBox(height: 16),
            StreamBuilder<Map<String, dynamic>?>(
              stream: services.on('default', 'update'),
              builder: (context, snapshot) {
                if (!snapshot.hasData)
                  return const Text('Waiting for default updates…');
                final data = snapshot.data!;
                final device = data['device'] as String?;
                final date =
                    DateTime.tryParse(data['current_date'] as String? ?? '');
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Device: ${device ?? 'Unknown'}'),
                    Text('Time:   ${date ?? 'n/a'}'),
                  ],
                );
              },
            ),
            const Divider(height: 32),
            const Text('Location service (tag: "location")',
                style: TextStyle(fontWeight: FontWeight.bold)),
            _ServiceControls(
              tag: 'location',
              startStopLabelBuilder: (running) =>
                  running ? 'Stop Location Service' : 'Start Location Service',
            ),
            const SizedBox(height: 16),
            StreamBuilder<Map<String, dynamic>?>(
              stream: services.on('location', 'update'),
              builder: (context, snapshot) {
                if (!snapshot.hasData)
                  return const Text('Waiting for location updates…');
                final data = snapshot.data!;
                final device = data['device'] as String?;
                final date =
                    DateTime.tryParse(data['current_date'] as String? ?? '');
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Device: ${device ?? 'Unknown'}'),
                    Text('Time:   ${date ?? 'n/a'}'),
                  ],
                );
              },
            ),
            const SizedBox(height: 24),
            const LogView(),
          ],
        ),
      ),
    );
  }
}

class _ServiceControls extends StatefulWidget {
  const _ServiceControls({
    required this.tag,
    required this.startStopLabelBuilder,
    Key? key,
  }) : super(key: key);

  final String tag;
  final String Function(bool isRunning) startStopLabelBuilder;

  @override
  State<_ServiceControls> createState() => _ServiceControlsState();
}

class _ServiceControlsState extends State<_ServiceControls> {
  final services = FlutterBackgroundService();
  bool _running = true;

  Future<void> _refresh() async {
    final r = await services.isServiceRunning(widget.tag);
    if (mounted) setState(() => _running = r);
  }

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 12,
      runSpacing: 8,
      children: [
        ElevatedButton(
          onPressed: () => services.invoke(widget.tag, "setAsForeground"),
          child: const Text("Foreground Mode"),
        ),
        ElevatedButton(
          onPressed: () => services.invoke(widget.tag, "setAsBackground"),
          child: const Text("Background Mode"),
        ),
        ElevatedButton(
          onPressed: () async {
            final running = await services.isServiceRunning(widget.tag);
            if (running) {
              services.invoke(widget.tag, "stopService");
            } else {
              await services.start(widget.tag);
            }
            await _refresh();
          },
          child: Text(widget.startStopLabelBuilder(_running)),
        ),
      ],
    );
  }
}

class LogView extends StatefulWidget {
  const LogView({Key? key}) : super(key: key);
  @override
  State<LogView> createState() => _LogViewState();
}

class _LogViewState extends State<LogView> {
  late final Timer _timer;
  List<String> logs = [];

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) async {
      final sp = await SharedPreferences.getInstance();
      await sp.reload();
      logs = sp.getStringList('log') ?? [];
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _timer.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Divider(height: 32),
        const Text('iOS background logs:'),
        const SizedBox(height: 8),
        for (final log in logs) Text(log),
      ],
    );
  }
}
