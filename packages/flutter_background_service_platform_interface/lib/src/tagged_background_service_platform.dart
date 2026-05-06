import 'dart:async';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:flutter_background_service_platform_interface/flutter_background_service_platform_interface.dart';

/// Contract a platform must provide for a *tagged* service instance.
/// This mirrors the familiar API, but scoped to a specific [tag].
abstract class TaggedBackgroundServicePlatform extends PlatformInterface {
  TaggedBackgroundServicePlatform() : super(token: _token);
  static final Object _token = Object();

  // Optional: platforms may return a broadcast stream per tag
  Stream<Map<String, dynamic>?> on(String method);

  Future<bool> configure({
    required IosConfiguration iosConfiguration,
    required AndroidConfiguration androidConfiguration,
  });

  Future<bool> start();

  Future<bool> isServiceRunning();

  void invoke(String method, [Map<String, dynamic>? args]);

  Future<void> dispose() async {}
}
