import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:flutter/widgets.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:socket_io_client/socket_io_client.dart' as io;
import 'package:flutter_background_service_android/flutter_background_service_android.dart';

/// Configure the tagged "socket" service.
/// Call this once at app start.
Future<void> initializeService() async {
  // Android: set up a notification channel so we can run as a FGS.
  const AndroidNotificationChannel channel = AndroidNotificationChannel(
    'socket_foreground',
    'Socket Foreground Service',
    description: 'Socket.IO background connection',
    importance: Importance.low,
  );

  final local = FlutterLocalNotificationsPlugin();
  if (Platform.isAndroid || Platform.isIOS) {
    await local.initialize(
      const InitializationSettings(
        android: AndroidInitializationSettings('ic_bg_service_small'),
        iOS: DarwinInitializationSettings(),
      ),
    );
  }

  await local
      .resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin
      >()
      ?.createNotificationChannel(channel);

  final services = FlutterBackgroundService();

  // Configure the **socket** tagged service.
  await services.configure(
    tag: 'socket',
    // no special Android service class needed; use default type
    iosConfiguration: IosConfiguration(
      autoStart: true,
      onForeground: onStart,
      onBackground: onIosBackground,
    ),
    androidConfiguration: AndroidConfiguration(
      autoStart: true,
      onStart: onStart,
      isForegroundMode: true, // keep connection alive reliably
      autoStartOnBoot: true, // optional
      notificationChannelId: 'socket_foreground',
      initialNotificationTitle: 'Socket Service',
      initialNotificationContent: 'Connecting…',
      foregroundServiceNotificationId: 2001,
      // keep it generic; add AndroidForegroundType.location if you actually do location
      foregroundServiceTypes: [AndroidForegroundType.specialUse],
    ),
  );

  // If you want manual control instead of autoStart:
  // await services.start('socket');
}

// ---------------- iOS background hook ----------------

@pragma('vm:entry-point')
Future<bool> onIosBackground(ServiceInstance service) async {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();
  // If needed: background fetch / local persistence.
  return true;
}

// ---------------- Background entrypoint ----------------

@pragma('vm:entry-point')
void onStart(ServiceInstance service) async {
  DartPluginRegistrant.ensureInitialized();

  final local = FlutterLocalNotificationsPlugin();

  // socket client
  late io.Socket socket;
  bool connectedOnce = false;

  void connectSocket() {
    socket = io.io(
      // TODO: replace with your endpoint. Emulator-to-host example: ws://10.0.2.2:5000
      'ws://10.0.2.2:5000',
      io.OptionBuilder()
          .setTransports(['websocket'])
          .enableAutoConnect()
          .build(),
    );

    socket.onConnect((_) async {
      connectedOnce = true;

      // Update notification text when connected (Android)
      if (service is AndroidServiceInstance) {
        await service.setForegroundNotificationInfo(
          title: 'Socket Service',
          content: 'Connected (${DateTime.now().toIso8601String()})',
        );
      }

      // Let the UI know we’re connected
      service.invoke('socket_event', {'payload': 'connected: ${socket.id}'});
    });

    socket.onDisconnect((_) {
      service.invoke('socket_event', {'payload': 'disconnected'});
      // socket.io has built-in reconnect; you can also trigger manual retry here.
    });

    socket.on('serverEvent', (data) {
      // Relay server events to the UI
      service.invoke('socket_event', {'payload': 'serverEvent: $data'});
    });

    socket.onError((err) {
      service.invoke('socket_event', {'payload': 'socket_error: $err'});
    });
  }

  connectSocket();

  // Handle control messages from UI
  service.on('emit').listen((event) {
    final msg = event?['message'];
    socket.emit('clientData', msg ?? 'ping');
  });

  if (service is AndroidServiceInstance) {
    service
        .on('setAsForeground')
        .listen((_) => service.setAsForegroundService());
    service
        .on('setAsBackground')
        .listen((_) => service.setAsBackgroundService());
  }

  service.on('stopService').listen((_) async {
    try {
      socket.dispose();
    } catch (_) {}
    await Future<void>.delayed(const Duration(milliseconds: 100));
    service.stopSelf();
  });

  // Heartbeat every 5s
  Timer.periodic(const Duration(seconds: 5), (t) {
    if (socket.connected) {
      socket.emit('heartbeat', {'t': DateTime.now().toIso8601String()});
      service.invoke('socket_event', {'payload': 'heartbeat sent'});
    } else {
      if (!connectedOnce) {
        // still dialing; just inform UI once in a while
        service.invoke('socket_event', {'payload': 'connecting…'});
      }
    }
  });
}
