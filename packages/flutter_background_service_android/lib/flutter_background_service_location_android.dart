library flutter_background_service_location_android;

import 'dart:async';
import 'dart:ui';

import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_background_service_platform_interface/flutter_background_service_platform_interface.dart';

String _runtimeTag = 'location'; // this will be set in the entrypointLocation

String get currentServiceTag => _runtimeTag;

@pragma('vm:entry-point')
Future<void> entrypointLocation(List<String> args) async {
  WidgetsFlutterBinding.ensureInitialized();

  final service = AndroidServiceInstance._();

  final int handle = int.parse(args[0]);
  _runtimeTag = args.length > 1 ? args[1] : 'location';

  print('Starting background service location with tag=$_runtimeTag');

  // If you want the tag inside the Dart isolate, you can store it globally or pass to your onStart
  final callbackHandle = CallbackHandle.fromRawHandle(handle);
  final onStart = PluginUtilities.getCallbackFromHandle(callbackHandle);
  if (onStart != null) {
    onStart(service);
  }
}

/// The background-side service instance for the *default* service.
/// (Your existing file likely already has this; keeping here for completeness.)
class AndroidServiceInstance extends ServiceInstance {
  // IMPORTANT: use the LOCATION background channel to match your Java service
  static const MethodChannel _channel = MethodChannel(
    'id.flutter/background_service_location_android_bg',
    JSONMethodCodec(),
  );

  AndroidServiceInstance._() {
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
      'tag': currentServiceTag,
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

  Future<void> setForegroundNotificationInfo({
    required String title,
    required String content,
  }) async {
    await _channel.invokeMethod("setNotificationInfo", {
      "title": title,
      "content": content,
    });
  }

  Future<void> setAsForegroundService() async {
    await _channel.invokeMethod("setForegroundMode", {'value': true});
  }

  Future<void> setAsBackgroundService() async {
    await _channel.invokeMethod("setForegroundMode", {'value': false});
  }

  Future<bool> isForegroundService() async {
    final result = await _channel.invokeMethod<bool>('isForegroundMode');
    return result ?? false;
  }

  Future<void> setAutoStartOnBootMode(bool value) async {
    await _channel.invokeMethod("setAutoStartOnBootMode", {"value": value});
  }

  Future<bool> openApp() async {
    final result = await _channel.invokeMethod('openApp');
    return result ?? false;
  }
}
