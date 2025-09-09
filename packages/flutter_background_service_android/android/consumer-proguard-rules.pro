# --- Keep your plugin’s public API surface and reflection entry points ---
-keep class id.flutter.flutter_background_service.** { *; }
-keepclassmembers class id.flutter.flutter_background_service.** { *; }

# Keep annotations so @Keep (if you ever add it) and others are honored
-keepattributes *Annotation*

# Flutter engine & embedding (defensive; usually safe and small)
-keep class io.flutter.** { *; }
-dontwarn io.flutter.**

# If you expose Android Services/Receivers that must be called by the framework,
# make sure they aren’t stripped/renamed. Adjust the class names to match your package.
-keep class id.flutter.flutter_background_service.BackgroundService { *; }
-keep class id.flutter.flutter_background_service.BackgroundServiceLocation { *; }
-keep class id.flutter.flutter_background_service.BootReceiver { *; }
-keep class id.flutter.flutter_background_service.WatchdogReceiver { *; }

# If you route by string (MethodChannel names), keeping these classes intact prevents surprises
-keep class id.flutter.flutter_background_service.ForegroundTypeMapper { *; }
-keep class id.flutter.flutter_background_service.Config { *; }

# JSON codec is fine without special rules, but we silence any odd warnings
-dontwarn org.json.**
