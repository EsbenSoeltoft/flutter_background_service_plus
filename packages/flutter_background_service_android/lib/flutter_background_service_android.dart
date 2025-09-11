library flutter_background_service_android;

import 'dart:async';
import 'dart:ui';

import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_background_service_platform_interface/flutter_background_service_platform_interface.dart';
import 'dart:developer';

bool _isMainIsolate = true;

@pragma('vm:entry-point')
Future<void> entrypoint(List<String> args) async {
  WidgetsFlutterBinding.ensureInitialized();
  _isMainIsolate = false;

  print(
      'BackgroundServiceManager: Starting background service with args=$args');
  log('BackgroundServiceManager: Starting background service with args=$args');

  // args[0] = background handle; args[1] = tag (from Java)
  final handle = int.parse(args[0]);
  final tag = args.length > 1 ? args[1] : 'default';

  print('BackgroundServiceManager: Starting background service with tag=$tag');
  log('BackgroundServiceManager: Starting background service with tag=$tag');

  final service = AndroidServiceInstance._(tag);

  /// Handshake: tell the plugin this tag is ready.
  try {
    service.invoke(
        'ready', {'tag': tag, 'ts': DateTime.now().millisecondsSinceEpoch});
  } catch (_) {}

  final callbackHandle = CallbackHandle.fromRawHandle(handle);
  final onStart = PluginUtilities.getCallbackFromHandle(callbackHandle);
  if (onStart != null) {
    onStart(service);
  }
}

class AndroidServiceInstance extends ServiceInstance {
  String runtimeTag = 'default'; // this will be set in the entrypoint

  static const MethodChannel _channel = const MethodChannel(
    'id.flutter/background_service_android_bg',
    JSONMethodCodec(),
  );

  AndroidServiceInstance._(this.runtimeTag) {
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  final _controller =
      StreamController<Map<String, dynamic>?>.broadcast(sync: true);

  Future<void> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case "onReceiveData":
        _controller.sink.add((call.arguments as Map?)?.cast<String, dynamic>());
        break;
      default:
    }
  }

  @override
  void invoke(String method, [Map<String, dynamic>? args]) {
    _channel.invokeMethod('sendData', {
      'method': method,
      'args': args,
      'tag': runtimeTag,
    });
  }

  @override
  Future<void> stopSelf() async {
    await _channel.invokeMethod("stopService");
  }

  @override
  Stream<Map<String, dynamic>?> on(String method) {
    return _controller.stream.transform(
      StreamTransformer.fromHandlers(
        handleData: (data, sink) {
          if (data?['method'] == method) {
            sink.add(data?['args']);
          }
        },
      ),
    );
  }

  @override
  Future<void> setForegroundNotificationInfo({
    required String title,
    required String content,
  }) async {
    await _channel.invokeMethod("setNotificationInfo", {
      "title": title,
      "content": content,
    });
  }

  @override
  Future<void> setAsForegroundService() async {
    await _channel.invokeMethod("setForegroundMode", {
      'value': true,
    });
  }

  @override
  Future<void> setAsBackgroundService() async {
    await _channel.invokeMethod("setForegroundMode", {
      'value': false,
    });
  }

  /// returns true when the current Service instance is in foreground mode.
  @override
  Future<bool> isForegroundService() async {
    final result = await _channel.invokeMethod<bool>('isForegroundMode');
    return result ?? false;
  }

  @override
  Future<void> setAutoStartOnBootMode(bool value) async {
    await _channel.invokeMethod("setAutoStartOnBootMode", {
      "value": value,
    });
  }

  Future<bool> openApp() async {
    final result = await _channel.invokeMethod('openApp');
    return result ?? false;
  }
}

/// Keeps the existing behavior where the default file provides the
/// platform instance, but now adds a multi-tag API.
class FlutterBackgroundServiceAndroid extends FlutterBackgroundServicePlatform {
  /// Registers this class as the default instance of [FlutterBackgroundServicePlatform].
  static void registerWith() {
    FlutterBackgroundServicePlatform.instance =
        FlutterBackgroundServiceAndroid();
  }

  FlutterBackgroundServiceAndroid._();
  static final FlutterBackgroundServiceAndroid _instance =
      FlutterBackgroundServiceAndroid._();

  factory FlutterBackgroundServiceAndroid() {
    if (!_isMainIsolate) {
      throw Exception(
        "This class should only be used in the main isolate (UI App)",
      );
    }

    return _instance;
  }

  /// NEW: return a tagged handle that always routes with [tag].
  /// Optionally provide a [serviceType] (e.g., "location") so the plugin
  /// starts the correct Android Service for this tag.
  @override
  TaggedBackgroundServicePlatform forTag(String tag, {String? serviceType}) =>
      _AndroidTaggedBackgroundService(tag: tag, serviceType: serviceType);

  // -------------------------
  // Back-compat passthroughs:
  // They operate on the "default" tag to preserve existing API.
  // -------------------------
  final _AndroidTaggedBackgroundService _default =
      _AndroidTaggedBackgroundService(tag: 'default');

  @override
  Future<bool> configure({
    required IosConfiguration iosConfiguration,
    required AndroidConfiguration androidConfiguration,
  }) =>
      _default.configure(
        iosConfiguration: iosConfiguration,
        androidConfiguration: androidConfiguration,
      );

  @override
  Future<bool> start() => _default.start();

  @override
  Future<bool> isServiceRunning() => _default.isServiceRunning();

  @override
  void invoke(String method, [Map<String, dynamic>? args]) =>
      _default.invoke(method, args);

  @override
  Stream<Map<String, dynamic>?> on(String method) => _default.on(method);

  @override
  Future<void> stopAll() async {
    _default.stopAll();
  }

  void disposeDefault() => _default.dispose();
}

/// A lightweight, tag-scoped client that talks to the same channels
/// but always includes "tag": <tag> so the Android plugin can route.
/// If [serviceType] is provided (e.g., "location"), the plugin can
/// choose a different Android Service class when starting.
class _AndroidTaggedBackgroundService extends TaggedBackgroundServicePlatform {
  _AndroidTaggedBackgroundService({required this.tag, this.serviceType});

  final String tag;
  final String? serviceType;

  static const MethodChannel _methodChannel = MethodChannel(
      'id.flutter/background_service/android/method', JSONMethodCodec());
  static const EventChannel _eventChannel = EventChannel(
      'id.flutter/background_service/android/event', JSONMethodCodec());

  StreamSubscription<dynamic>? _eventSub;
  final _controller =
      StreamController<Map<String, dynamic>?>.broadcast(sync: true);

  @override
  Future<bool> configure({
    required IosConfiguration iosConfiguration,
    required AndroidConfiguration androidConfiguration,
  }) async {
    _ensureEventSubscription();

    final handle =
        PluginUtilities.getCallbackHandle(androidConfiguration.onStart);
    if (handle == null) {
      throw 'onStart method must be a top-level or static function';
    }

    final cfgTypes = androidConfiguration.foregroundServiceTypes;
    final fgTypes = (cfgTypes == null || cfgTypes.isEmpty)
        ? null
        : cfgTypes.map((t) => t.name).toList();

    final ok = await _methodChannel.invokeMethod<bool>("configure", {
      "tag": tag,
      if (serviceType != null) "serviceType": serviceType,
      "background_handle": handle.toRawHandle(),
      "is_foreground_mode": androidConfiguration.isForegroundMode,
      "auto_start": androidConfiguration.autoStart,
      "auto_start_on_boot": androidConfiguration.autoStartOnBoot,
      "initial_notification_content":
          androidConfiguration.initialNotificationContent,
      "initial_notification_title":
          androidConfiguration.initialNotificationTitle,
      "notification_channel_id": androidConfiguration.notificationChannelId,
      "foreground_notification_id":
          androidConfiguration.foregroundServiceNotificationId,
      "foreground_service_types": fgTypes,
    });

    return ok ?? false;
  }

  @override
  Future<bool> start() async {
    final ok = await _methodChannel.invokeMethod<bool>("start", {
      "tag": tag,
      if (serviceType != null) "serviceType": serviceType,
    });
    return ok ?? false;
  }

  @override
  Future<bool> isServiceRunning() async {
    final ok = await _methodChannel
        .invokeMethod<bool>("isServiceRunning", {"tag": tag});
    return ok ?? false;
  }

  @override
  void invoke(String method, [Map<String, dynamic>? args]) {
    _methodChannel.invokeMethod("sendData", {
      "tag": tag,
      "method": method,
      "args": args,
    });
  }

  @override
  Stream<Map<String, dynamic>?> on(String method) {
    _ensureEventSubscription();
    return _controller.stream.transform(
      StreamTransformer.fromHandlers(
        handleData: (data, sink) {
          if (data?['method'] == method) sink.add(data?['args']);
        },
      ),
    );
  }

  void _ensureEventSubscription() {
    if (_eventSub != null) return;
    _eventSub =
        _eventChannel.receiveBroadcastStream({"tag": tag}).listen((event) {
      _controller.add((event as Map?)?.cast<String, dynamic>());
    });
  }

  Future<void> stopAll() async {
    await _methodChannel.invokeMethod('stopAll');
  }

  @override
  Future<void> dispose() async {
    await _eventSub?.cancel();
    await _controller.close();
  }
}
