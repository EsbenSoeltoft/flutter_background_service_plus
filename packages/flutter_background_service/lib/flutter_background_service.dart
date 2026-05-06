library flutter_background_service;

import 'dart:async';
import 'package:flutter_background_service_platform_interface/flutter_background_service_platform_interface.dart';
import 'package:flutter_background_service_android/flutter_background_service_android.dart';
import 'package:flutter_background_service_android/flutter_background_service_location_android.dart'
    as _bg_location show entrypointLocation;

export 'package:flutter_background_service_platform_interface/flutter_background_service_platform_interface.dart'
    show
        IosConfiguration,
        AndroidConfiguration,
        ServiceInstance,
        AndroidForegroundType,
        TaggedBackgroundServicePlatform;

// Make the symbol "reachable" so the tree-shaker keeps it.
@pragma('vm:entry-point')
void _preserveLocationEntrypointForAOT() {
  // This code never runs; it only creates a hard reference to the entrypoint.
  // ignore: dead_code
  if (false) {
    entrypoint(const <String>[]);
    _bg_location.entrypointLocation(const <String>[]);
  }
}

/// Global manager for tagged background services.
/// - Always work with tags (e.g. 'default', 'location', 'sync', ...).
/// - Internally caches tag-scoped clients, so calls from different parts of the
///   app reuse the same underlying channel/subscription for a given tag.
class FlutterBackgroundService {
  FlutterBackgroundService._();
  static final FlutterBackgroundService _instance =
      FlutterBackgroundService._();

  /// Get the singleton manager.
  factory FlutterBackgroundService() => _instance;

  final Map<String, TaggedBackgroundService> _cache = {};

  /// Return a tag-scoped client. The same instance is returned for the same tag.
  /// If you pass [serviceType] the first time, it is used to construct the
  /// platform client; subsequent calls with the same tag ignore [serviceType].
  TaggedBackgroundService forTag(String tag, {String? serviceType}) {
    return _cache.putIfAbsent(tag, () {
      final platformTagged = FlutterBackgroundServicePlatform.instance
          .forTag(tag, serviceType: serviceType);
      return TaggedBackgroundService._(
        tag: tag,
        platformTagged: platformTagged,
        onDispose: () => _cache.remove(tag),
      );
    });
  }

  // NEW: Stop all background services (default + location, etc.)
  Future<void> stopAll() => FlutterBackgroundServicePlatform.instance.stopAll();

  // -----------------------------
  // Convenience "static-style" API
  // These let you call the manager directly without holding a handle.
  // -----------------------------

  Future<bool> configure({
    required String tag,
    String? serviceType,
    required IosConfiguration iosConfiguration,
    required AndroidConfiguration androidConfiguration,
  }) {
    return forTag(tag, serviceType: serviceType).configure(
      iosConfiguration: iosConfiguration,
      androidConfiguration: androidConfiguration,
    );
  }

  Future<bool> start(String tag, {String? serviceType}) {
    return forTag(tag, serviceType: serviceType).start();
  }

  Future<bool> isServiceRunning(String tag) {
    return forTag(tag).isServiceRunning();
  }

  void invoke(String tag, String method, [Map<String, dynamic>? args]) {
    forTag(tag).invoke(method, args);
  }

  Stream<Map<String, dynamic>?> on(String tag, String method) {
    return forTag(tag).on(method);
  }

  /// Dispose a single tag client (closes streams). Does not stop the OS service.
  Future<void> disposeTag(String tag) async {
    final client = _cache.remove(tag);
    if (client != null) {
      await client.dispose();
    }
  }

  /// Dispose all cached clients (closes streams). Does not stop OS services.
  Future<void> disposeAll() async {
    final toDispose = List.of(_cache.values);
    _cache.clear();
    for (final c in toDispose) {
      await c.dispose();
    }
  }
}

/// A lightweight tag-scoped client that talks to the platform for one tag.
///
/// You typically won't construct this directly; use:
///   `FlutterBackgroundService().forTag('location', serviceType: 'location')`
class TaggedBackgroundService implements Observable {
  TaggedBackgroundService._({
    required this.tag,
    required TaggedBackgroundServicePlatform platformTagged,
    void Function()? onDispose,
  })  : _platformTagged = platformTagged,
        _onDispose = onDispose;

  final String tag;
  final TaggedBackgroundServicePlatform _platformTagged;
  final void Function()? _onDispose;

  Future<bool> configure({
    required IosConfiguration iosConfiguration,
    required AndroidConfiguration androidConfiguration,
  }) {
    return _platformTagged.configure(
      iosConfiguration: iosConfiguration,
      androidConfiguration: androidConfiguration,
    );
  }

  Future<bool> start() => _platformTagged.start();

  Future<bool> isServiceRunning() => _platformTagged.isServiceRunning();

  @override
  void invoke(String method, [Map<String, dynamic>? args]) {
    _platformTagged.invoke(method, args);
  }

  @override
  Stream<Map<String, dynamic>?> on(String method) {
    return _platformTagged.on(method);
  }

  /// Close any Dart-side subscriptions/resources for this tag client.
  /// (Does not stop the Android/iOS service; call a stop API if/when you add it.)
  Future<void> dispose() async {
    await _platformTagged.dispose();
    _onDispose?.call();
  }
}
