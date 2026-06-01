import 'dart:async';

import 'package:flutter_background_service_platform_interface/src/configs.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'src/tagged_background_service_platform.dart';

export 'src/configs.dart';
export 'src/tagged_background_service_platform.dart';

abstract class Observable {
  void invoke(String method, [Map<String, dynamic>? args]);
  Stream<Map<String, dynamic>?> on(String method);
}

abstract class FlutterBackgroundServicePlatform extends PlatformInterface
    implements Observable {
  FlutterBackgroundServicePlatform() : super(token: _token);
  static final Object _token = Object();

  static FlutterBackgroundServicePlatform? _instance;

  static FlutterBackgroundServicePlatform get instance {
    if (_instance == null) {
      throw StateError(
        'FlutterBackgroundServicePlatform.instance has not been set. '
        'Ensure a platform implementation registers itself (e.g., Android registerWith).',
      );
    }
    return _instance!;
  }

  /// Platform-specific plugins should set this with their own platform-specific
  /// class that extends [FlutterBackgroundServicePlatform] when they register themselves.
  static set instance(FlutterBackgroundServicePlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<bool> configure({
    required IosConfiguration iosConfiguration,
    required AndroidConfiguration androidConfiguration,
  });

  Future<bool> start();

  Future<bool> isServiceRunning();

  /// NEW: Stop all running services for this plugin on the current platform.
  Future<void> stopAll() {
    throw UnimplementedError('stopAll() has not been implemented.');
  }

  /// NEW: Return a tag-scoped handle.
  /// Default implementation throws; each platform should override.
  TaggedBackgroundServicePlatform forTag(String tag, {String? serviceType}) {
    throw UnimplementedError('forTag() has not been implemented.');
  }
}

abstract class ServiceInstance implements Observable {
  /// Stop the service
  Future<void> stopSelf();

  // -------- Optional cross-platform APIs (no-ops by default) --------

  /// Android: updates the ongoing foreground notification.
  /// Other platforms: no-op.
  Future<void> setForegroundNotificationInfo({
    required String title,
    required String content,
    String? channelId,
    int? notificationId,
  }) async {}

  /// Android: move to Foreground Service.
  /// Other platforms: no-op.
  Future<void> setAsForegroundService() async {}

  /// Android: move to Background (no ongoing notification).
  /// Other platforms: no-op.
  Future<void> setAsBackgroundService() async {}

  /// Android: true when running as a Foreground Service.
  /// Other platforms: always false.
  Future<bool> isForegroundService() async => false;

  /// Android: persist "auto start on boot".
  /// Other platforms: no-op.
  Future<void> setAutoStartOnBootMode(bool value) async {}
}
