A flutter plugin for execute dart code in background.

# Flutter Background Service Plus

An extended fork of [`flutter_background_service`](https://pub.dev/packages/flutter_background_service) with **multi-service and tagged isolate support**.

This plugin allows you to run **multiple background services** in Flutter, each identified by a unique `tag`, with bi-directional communication between:

- **UI ↔ Service**
- **Service ↔ Service**
- **Service ↔ UI (relay via Plugin pipes)**

---

## ✨ Features

- 🔹 Run multiple background isolates with unique `tag`s (e.g., `triggers`, `location`, `ui`).
- 🔹 Separate **default service** and **location service** classes on Android.
- 🔹 **Tag-specific pipes** for communication: isolate state is separated.
- 🔹 Bi-directional **UI ↔ Service communication** via `invoke`/`on`.
- 🔹 **Service ↔ Service relays** (`sendData(toTag)`).
- 🔹 **Foreground notifications** for Android services.
- 🔹 Watchdog restart mechanism for resilience after process death.
- 🔹 Configurable auto-start on boot and foreground/background mode.

---

## Android

- To change notification icon, just add drawable icon with name `ic_bg_service_small`.


### Configuration required for Foreground Services on Android 14+ (SDK 34)

Applications that target SDK 34 and use foreground services need to include some additional configuration to declare the type of foreground service they use:

* Determine the type or types of foreground service your app requires by consulting [the documentation](https://developer.android.com/about/versions/14/changes/fgs-types-required)

* Add the corresponding permission to your `android/app/src/main/AndroidManifest.xml` file:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools" package="com.example">
  ...
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <!--
    Permission to use here depends on the value you picked for foregroundServiceType - see the Android documentation.
    Eg, if you picked 'location', use 'android.permission.FOREGROUND_SERVICE_LOCATION'
  -->
  <!--
    If you need more than 1 type, you must separate each type by a pipe(|) symbol - see the Android documentation.
    Eg, android:foregroundServiceType="location|mediaPlayback"
  -->
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_..." />
  <application
        android:label="example"
        android:name="${applicationName}"
        android:icon="@mipmap/ic_launcher"
        ...>

        <activity
            android:name=".MainActivity"
            android:exported="true"
            ...>

        <!--Add this-->
        <service
            android:name="id.flutter.flutter_background_service.BackgroundService"
            android:foregroundServiceType="WhatForegroundServiceTypesDoYouWant"
        />
        <!--end-->

        ...
  ...
  </application>
</manifest>
```


## iOS

- Enable `background_fetch` capability in xcode (optional), if you wish ios to execute `IosConfiguration.onBackground` callback.

- For iOS 13 and Later (using `BGTaskScheduler`), insert lines below into your ios/Runner/Info.plist

```plist
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>dev.flutter.background.refresh</string>
</array>
```

- You can also using your own custom identifier
In `ios/Runner/AppDelegate.swift` add line below

```swift
import UIKit
import Flutter
import flutter_background_service_ios // add this

@UIApplicationMain
@objc class AppDelegate: FlutterAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    /// Add this line
    SwiftFlutterBackgroundServicePlugin.taskIdentifier = "your.custom.task.identifier"

    GeneratedPluginRegistrant.register(with: self)
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}
```


## 🚀 Usage

### 1. Configure a service

In your Flutter app, configure each background service before starting:

```dart
Future<void> configureBackgroundService() async {
  final service = FlutterBackgroundService();

  await service.configure(
    tag: "triggers", // 👈 unique tag
    serviceType: "default",
    iosConfiguration: IosConfiguration(
      autoStart: true,
      onForeground: onIosForeground,
      onBackground: onIosBackground,
    ),
    androidConfiguration: AndroidConfiguration(
      autoStart: false,
      onStart: onStart,
      isForegroundMode: true,
      autoStartOnBoot: false,
      initialNotificationContent: 'Incipier trigger background service',
      initialNotificationTitle: 'Incipier background service',
      foregroundServiceTypes: [AndroidForegroundType.specialUse],
    ),
  );
}
```

Similarly, configure a location service:

```dart
Future<void> configureBackgroundServiceLocation() async {
  final service = FlutterBackgroundService().forTag('location', serviceType: 'location');

  await service.configure(
    iosConfiguration: IosConfiguration(
      autoStart: false,
      onForeground: onIosLocationForeground,
      onBackground: onIosLocationBackground,
    ),
    androidConfiguration: AndroidConfiguration(
      autoStart: false,
      onStart: onLocationStart,
      isForegroundMode: true,
      autoStartOnBoot: true,
      initialNotificationContent: 'Location tracking background service',
      initialNotificationTitle: 'Incipier location service',
      foregroundServiceTypes: [
        AndroidForegroundType.location,
        AndroidForegroundType.specialUse,
      ],
    ),
  );
}
```

###2. Start a service

```dart
void startBackgroundService({
  required String uuid,
  Position? position,
}) {
  final service = FlutterBackgroundService().forTag('triggers', serviceType: 'default');
  service.start().then((_) {
    service.invoke("start", {
      "uuid": uuid,
      "pos": position,
    });
  });
}
```

For location:
```dart
void startBackgroundServiceLocation({
  required String uuid,
  geo.Position? position,
}) {
  final service = FlutterBackgroundService().forTag('location', serviceType: 'location');
  service.start().then((_) {
    service.invoke("start", {
      "uuid": uuid,
      "pos": {
        "latitude": position?.latitude,
        "longitude": position?.longitude,
      },
    });
  });
}
```

> **WARNING**:
> * YOU MUST MAKE SURE ANY REQUIRED PERMISSIONS TO BE GRANTED BEFORE YOU START THE SERVICE
> * THE TYPES YOU PUT IN foregroundServiceTypes, MUST BE DECLARED IN MANIFEST


###3. Stop a service
```dart
@pragma('vm:entry-point')
void stopBackgroundService() {
  final service = FlutterBackgroundService().forTag('triggers', serviceType: 'default');
  service.invoke('stop');
}
```

###4. Communicate with services

UI → Service
```dart
service.invoke("update", {"uuid": uuid});
```

Service → UI
```dart
_service.invoke("onNotificationReceived", {"title": "Task due!"});
```

Service → Service relay
```dart
_service.invoke('sendData', {
  'method': 'onLocationReceived',
  'args': {"latitude": 55.7, "longitude": 12.5},
  'toTag': 'triggers', // 👈 relay to another service
});
```



###5. Using custom notification for Foreground Service
You can make your own custom notification for foreground service. It can give you more power to make notifications more attractive to users, for example adding progressbars, buttons, actions, etc. The example below is using [flutter_local_notifications](https://pub.dev/packages/flutter_local_notifications) plugin, but you can use any other notification plugin. You can follow how to make it below:

- Notification Channel
```dart

Future<void> main() async {
    WidgetsFlutterBinding.ensureInitialized();
    await initializeService();

    runApp(MyApp());
}

// this will be used as notification channel id
const notificationChannelId = 'my_foreground';

// this will be used for notification id, So you can update your custom notification with this id.
const notificationId = 888;

Future<void> initializeService() async {
  final service = FlutterBackgroundService();

  const AndroidNotificationChannel channel = AndroidNotificationChannel(
    notificationChannelId, // id
    'MY FOREGROUND SERVICE', // title
    description:
        'This channel is used for important notifications.', // description
    importance: Importance.low, // importance must be at low or higher level
  );

  final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin =
      FlutterLocalNotificationsPlugin();

  await flutterLocalNotificationsPlugin
      .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>()
      ?.createNotificationChannel(channel);

  await service.configure(
    androidConfiguration: AndroidConfiguration(
      // this will be executed when app is in foreground or background in separated isolate
      onStart: onStart,

      // auto start service
      autoStart: true,
      isForegroundMode: true,

      notificationChannelId: notificationChannelId, // this must match with notification channel you created above.
      initialNotificationTitle: 'AWESOME SERVICE',
      initialNotificationContent: 'Initializing',
      foregroundServiceNotificationId: notificationId,
    ),
    ...
```

- Update notification info

```dart

Future<void> onStart(ServiceInstance service) async {
  // Only available for flutter 3.0.0 and later
  DartPluginRegistrant.ensureInitialized();

  final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin =
      FlutterLocalNotificationsPlugin();

  // bring to foreground
  Timer.periodic(const Duration(seconds: 1), (timer) async {
    if (service is AndroidServiceInstance) {
      if (await service.isForegroundService()) {
        flutterLocalNotificationsPlugin.show(
          notificationId,
          'COOL SERVICE',
          'Awesome ${DateTime.now()}',
          const NotificationDetails(
            android: AndroidNotificationDetails(
              notificationChannelId,
              'MY FOREGROUND SERVICE',
              icon: 'ic_bg_service_small',
              ongoing: true,
            ),
          ),
        );
      }
    }
  });
}
```



### Using Background Service Even when The Application Is Closed

You can use this feature in order to execute code in background.
Very useful to fetch realtime data from a server and push notifications.

> **Must Know**:
> *  ``` isForegroundMode: false ``` : The background mode requires running in release mode and requires disabling battery optimization so that the service stays up when the user closes the application.
> *  ``` isForegroundMode: true ``` : Displays a silent notification when used according to [Android's Policy](https://developer.android.com/develop/background-work/services)




##🔄 Flow Diagram




##⚠️ Limitations

Services are 1 per class (BackgroundService and BackgroundServiceLocation).
You cannot yet spin up 5 location services simultaneously — tags separate isolate logic, not service classes.

Tags must be unique per service type (triggers, location_default, etc.).

START_REDELIVER_INTENT means if Android restarts the service, the last Intent (with tag) is reused.
But if no intent was ever delivered, it falls back to the saved last_tag.


##🛠 Next Steps

Expand to N background services by allowing multiple service classes or dynamic service instantiation.

Improve tag persistence to avoid "default" fallback edge cases after cold boot.

Expose a Flutter-side API for enumerating all active tags at runtime.

Add test harnesses for multiple isolates running in parallel.


##📜 License

MIT




## FAQ

### Why the service not started automatically?

Some android device manufacturers have a custom android os for example MIUI from Xiaomi. You have to deal with that policy.

### Service killed by system and not respawn?

Try to disable battery optimization for your app.

### My notification icon not changed, how to solve it?

Make sure you had created notification icons named `ic_bg_service_small` and placed in res/drawable-mdpi, res/drawable-hdpi, res/drawable-xhdpi, res/drawable-xxhdpi for PNGs file, and res/drawable-anydpi-v24 for XML (Vector) file.

### Service not running in Release Mode

Add `@pragma('vm:entry-point')` to the `onStart()` method.
Example:
```dart
@pragma('vm:entry-point')
void onStart(ServiceInstance service){
}
```

### Service terminated when app is in background (minimized) on iOS

Keep in your mind, iOS doesn't have a long running service feature like Android. So, it's not possible to keep your application running when it's in background because the OS will suspend your application soon. Currently, this plugin provide onBackground method, that will be executed periodically by `Background Fetch` capability provided by iOS. It cannot be faster than 15 minutes and only alive about 15-30 seconds.


## Support the original creator of the plugin this was forked from.

[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://www.buymeacoffee.com/ekasetiawans)

