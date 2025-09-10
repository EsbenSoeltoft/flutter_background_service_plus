package id.flutter.flutter_background_service;

import id.flutter.flutter_background_service.BuildConfig;
import static android.os.Build.VERSION.SDK_INT;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class BackgroundServiceLocation extends Service implements MethodChannel.MethodCallHandler {
    private static final String TAG = "BackgroundServiceLocation";
    private static final String LOCK_NAME = BackgroundServiceLocation.class.getName() + ".Lock";

    public static volatile WakeLock lockStatic = null; // static

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private FlutterEngine backgroundEngine;
    private MethodChannel methodChannel;
    private Config config;
    private DartExecutor.DartEntrypoint dartEntrypoint;

    private boolean isManuallyStopped = false;

    private String notificationTitle;
    private String notificationContent;
    private String notificationChannelId;
    private int notificationId;
    private String configForegroundTypes;
    private String[] foregroundTypes;

    private Handler mainHandler;
    private String currentTag = "location_default";

    public static final Set<String> ACTIVE_TAGS = Collections.synchronizedSet(new HashSet<>());

    synchronized public static PowerManager.WakeLock getLock(Context context) {
        if (lockStatic == null) {
            PowerManager mgr = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            lockStatic = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_NAME);
            lockStatic.setReferenceCounted(true);
        }
        return lockStatic;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.mainHandler = new Handler(Looper.getMainLooper());
        // Config is initialized in onStartCommand where tag is known
    }

    @Override
    public void onDestroy() {
        if (!this.isManuallyStopped) {
            WatchdogReceiver.enqueue(this);
        } else if (this.config != null) {
            this.config.setManuallyStopped(true);
        }

        stopForeground(true);
        this.isRunning.set(false);

        if (this.backgroundEngine != null) {
            this.backgroundEngine.getServiceControlSurface().detachFromService();
            this.backgroundEngine.destroy();
            this.backgroundEngine = null;
        }

        // --- RELEASE WAKELOCK ---
        try {
            if (lockStatic != null && lockStatic.isHeld()) {
                lockStatic.release();
                Log.i(TAG, "WakeLock released in onDestroy()");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to release WakeLock: " + e.getMessage(), e);
        }
        // --- END RELEASE WAKELOCK ---
        
        try {
            Pipe myPipe = FlutterBackgroundServicePlugin.getPipeForTagIfExists(currentTag);
            if (myPipe != null) {
                myPipe.removeListener(listener);
            }
        } catch (Throwable ignored) {}

        FlutterBackgroundServicePlugin.removeTagFromPluginState(currentTag);

        this.methodChannel = null;
        this.dartEntrypoint = null;

        // Unregister tag
        ACTIVE_TAGS.remove(currentTag);  
         
        super.onDestroy();
    }

    private final Pipe.PipeListener listener = new Pipe.PipeListener() {
        @Override
        public void onReceived(JSONObject object) {
            receiveData(object);
        }
    };

    private void createNotificationChannel() {
        if (SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Location Background Service";
            String description = "Executing process in background";

            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(this.notificationChannelId, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected void updateNotificationInfo() {
        if (this.config == null || !this.config.isForeground()) {
            // Defensive only; we should not be here in normal flow
            // do not update notification if not in foreground mode
            return; 
        }

        // Ensure channel id exists and the channel is created (hardening)
        if (this.notificationChannelId == null || this.notificationChannelId.trim().isEmpty()) {
            this.notificationChannelId = "FOREGROUND_" + this.currentTag;
        }

        if (SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) {
                    Log.e(TAG, "NotificationManager is null, cannot ensure notification channel. Stopping service.");
                    // Avoid violating the 5s foreground rule; stop and let your watchdog retry later.
                    stopSelf();
                    return;
                }

                NotificationChannel existing = nm.getNotificationChannel(this.notificationChannelId);
                if (existing == null) {
                    CharSequence name = "Background Service Location";
                    String description = "Executing process in background";
                    NotificationChannel ch = new NotificationChannel(
                            this.notificationChannelId,
                            name,
                            NotificationManager.IMPORTANCE_LOW
                    );
                    ch.setDescription(description);
                    nm.createNotificationChannel(ch);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to ensure notification channel. Stopping service. " + e.getMessage(), e);
                stopSelf();
                return;
            }
        }

        // --- ICON CHECK ---
        int iconId = getResources().getIdentifier("ic_bg_service_small", "drawable", getPackageName());
        if (iconId == 0) {
            iconId = android.R.drawable.ic_dialog_alert; // safe fallback
            Log.w(TAG, "Fallback to system icon because ic_bg_service_small was not found.");
        } else if( BuildConfig.DEBUG) {
            Log.i(TAG, "Using notification icon 'ic_bg_service_small' with id=" + iconId);
        }
        // --- END ICON CHECK ---

        String packageName = getApplicationContext().getPackageName();
        Intent i = getPackageManager().getLaunchIntentForPackage(packageName);
        if (i == null) {
            // Fallback to app details settings if launcher intent is unavailable
            i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + packageName));
        }   

        int flags = PendingIntent.FLAG_CANCEL_CURRENT;
        if (SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        int requestCode = 11 + Math.abs(this.currentTag.hashCode() % 1000);
        PendingIntent pi = PendingIntent.getActivity(BackgroundServiceLocation.this, requestCode, i, flags);
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, this.notificationChannelId)
                .setSmallIcon(iconId)
                .setAutoCancel(true)
                .setOngoing(true)
                .setContentTitle(this.notificationTitle)
                .setContentText(this.notificationContent)
                .setContentIntent(pi);

        try {
            this.foregroundTypes = null;
            if (this.configForegroundTypes != null && !this.configForegroundTypes.isEmpty()) {
                this.foregroundTypes = this.configForegroundTypes.split(",");
            }
            Integer serviceType = ForegroundTypeMapper.getForegroundServiceType(this.foregroundTypes);

            Log.i(TAG, "tag=" + this.currentTag
                    + " channelId=" + this.notificationChannelId
                    + " serviceTypesCsv=" + this.configForegroundTypes
                    + " computedMask=" + serviceType);

            ServiceCompat.startForeground(this, this.notificationId, mBuilder.build(), serviceType);
        } catch (SecurityException e) {
            Log.w(TAG, "Failed to start foreground service due to SecurityException - have you forgotten to request a permission? - " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (this.isRunning.get()) {
            if (this.config != null) updateNotificationInfo();
            return START_REDELIVER_INTENT;
        }

        // Tag from intent or shared prefs  
        String tagFromIntent = (intent != null && intent.hasExtra("tag"))
                    ? intent.getStringExtra("tag")
                    : null;

        if (tagFromIntent == null || tagFromIntent.isEmpty()) {
            tagFromIntent = getSharedPreferences("bgsvc", MODE_PRIVATE)
                    .getString(getClass().getName() + ":last_tag", "location_default");
        }
        this.currentTag = (tagFromIntent == null || tagFromIntent.isEmpty()) ? "location_default" : tagFromIntent;

        // Register this tag as active
        ACTIVE_TAGS.add(currentTag); 

        // 2) Register listener on the tag-specific pipe
        Pipe myPipe = FlutterBackgroundServicePlugin.getOrCreatePipeForTag(currentTag);
        myPipe.addListener(listener);  

        // Tag-scoped config
        this.config = new Config(this, this.currentTag);
        this.config.setManuallyStopped(false);

        // Load notification settings from this tag’s config
        String storedChannelId = this.config.getNotificationChannelId();
        if (storedChannelId == null || storedChannelId.trim().isEmpty()) {
            this.notificationChannelId = "FOREGROUND_" + this.currentTag;
            createNotificationChannel();
        } else {
            this.notificationChannelId = storedChannelId;
        }

        this.notificationTitle = this.config.getInitialNotificationTitle();
        this.notificationContent = this.config.getInitialNotificationContent();
        this.notificationId = this.config.getForegroundNotificationId();
        if (this.notificationId <= 0) {
            this.notificationId = 1000 + Math.abs(this.currentTag.hashCode() % 800000); // sane fallback
        }
        this.configForegroundTypes = this.config.getForegroundServiceTypes();
        
        if (this.config.isForeground()) {
            updateNotificationInfo();    // will call startForeground(...)
        } else if (BuildConfig.DEBUG) {
            // NOT foreground mode – don’t try to show a notification or call startForeground
            Log.i(TAG, "Starting in background mode (no foreground notification).");
        }

        WatchdogReceiver.enqueue(this);
        runService(this.currentTag); // pass tag down so Dart entrypoint also knows it

        return START_REDELIVER_INTENT;
    }

    @SuppressLint("WakelockTimeout")
    private void runService(String tag) {
        // If we *know* we’re running, bail.
        if (this.isRunning.get()) {
            Log.v(TAG, "Service already running (flag) for tag= " + tag);
            return;
        }

        // If we already have an engine *and it’s executing Dart*, bail.
        if (this.backgroundEngine != null
                && this.backgroundEngine.getDartExecutor().isExecutingDart()) {
            Log.v(TAG, "Dart already executing for tag=" + tag);
            this.isRunning.set(true); // keep flag consistent
            return;
        }

        Log.v(TAG, "Starting flutter engine for background service tag=" + tag);
        getLock(getApplicationContext()).acquire();

        try {
            //updateNotificationInfo();

            FlutterLoader flutterLoader = FlutterInjector.instance().flutterLoader();
            if (!flutterLoader.initialized()) {
                flutterLoader.startInitialization(getApplicationContext());
            }
            flutterLoader.ensureInitializationComplete(getApplicationContext(), null);

            this.isRunning.set(true);
            this.backgroundEngine = new FlutterEngine(this);

            // Remove UI plugin from background engine
            this.backgroundEngine.getPlugins().remove(FlutterBackgroundServicePlugin.class);
            this.backgroundEngine.getServiceControlSurface().attachToService(BackgroundServiceLocation.this, null, this.config.isForeground());

            this.methodChannel = new MethodChannel(
                    this.backgroundEngine.getDartExecutor().getBinaryMessenger(),
                    "id.flutter/background_service_location_android_bg",
                    JSONMethodCodec.INSTANCE
            );
            this.methodChannel.setMethodCallHandler(this);

            this.dartEntrypoint = new DartExecutor.DartEntrypoint(
                    flutterLoader.findAppBundlePath(),
                    "package:flutter_background_service_android/flutter_background_service_location_android.dart",
                    "entrypointLocation"
            );

            final List<String> args = new ArrayList<>();
            args.add(String.valueOf(this.config.getBackgroundHandle())); // background handle
            args.add(tag); // pass tag as second argument to Dart

            this.backgroundEngine.getDartExecutor().executeDartEntrypoint(this.dartEntrypoint, args);

        } catch (UnsatisfiedLinkError e) {
            //After a reboot this may happen briefly and can be ignored

            // Optional: surface a friendly message via your foreground notification
            this.notificationContent = "Error " + e.getMessage();
            try { updateNotificationInfo(); } catch (Throwable ignore) {}

            // Always release the wakelock on failure
            try {
                if (lockStatic != null && lockStatic.isHeld()) {
                    lockStatic.release();
                    Log.w(TAG, "Wakelock released due to init failure (UnsatisfiedLinkError)", e);
                }
            } catch (Exception ignored) {}

            // Rethrow so the failure isn’t hidden (lets watchdog/app handle restart)
            throw e;

        } catch (Throwable t) {
            // Any other failure: release and rethrow
            try {
                if (lockStatic != null && lockStatic.isHeld()) {
                    lockStatic.release();
                    Log.w(TAG, "Wakelock released due to init failure", t);
                }
            } catch (Exception ignored) {}

            throw t;
        } 
    }

    public void receiveData(JSONObject data) {
        if (this.methodChannel == null) return;
        try {
            final JSONObject arg = data;
            this.mainHandler.post(() -> {
                if (this.methodChannel == null) return;
                this.methodChannel.invokeMethod("onReceiveData", arg);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (this.isRunning.get()) {
            WatchdogReceiver.enqueue(getApplicationContext(), 1000);
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
       if (this.config == null) {
            result.error("not-ready", "Service config not initialized yet", null);
            return;
        }

        String method = call.method;

        try {
            if (method.equalsIgnoreCase("setNotificationInfo")) {
                JSONObject arg = (JSONObject) call.arguments;
                if (arg.has("title")) {
                    this.notificationTitle = arg.getString("title");
                    this.notificationContent = arg.getString("content");
                    updateNotificationInfo();
                    result.success(true);
                }
                return;
            }

            if (method.equalsIgnoreCase("setAutoStartOnBootMode")) {
                JSONObject arg = (JSONObject) call.arguments;
                boolean value = arg.getBoolean("value");
                this.config.setAutoStartOnBoot(value);
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("setForegroundMode")) {
                JSONObject arg = (JSONObject) call.arguments;
                boolean value = arg.getBoolean("value");
                this.config.setIsForeground(value);
                if (value) {
                    updateNotificationInfo();
                    this.backgroundEngine.getServiceControlSurface().onMoveToForeground();
                } else {
                    stopForeground(true);
                    this.backgroundEngine.getServiceControlSurface().onMoveToBackground();
                }

                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("isForegroundMode")) {
                boolean value = this.config.isForeground();
                result.success(value);
                return;
            }

            if (method.equalsIgnoreCase("stopService")) {
                this.isManuallyStopped = true;
                WatchdogReceiver.remove(this);
                stopSelf();
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("sendData")) {
                try {
                    JSONObject arg = (JSONObject) call.arguments;

                    // Always annotate the origin of this message
                    arg.put("fromTag", currentTag);

                    // 1) Fan-out to UI listeners on the main pipe
                    if (FlutterBackgroundServicePlugin.mainPipe.hasListener()) {
                        // If no tag is present, annotate with the origin so UI can filter by tag.
                        if (!arg.has("tag")) arg.put("tag", currentTag);
                        FlutterBackgroundServicePlugin.mainPipe.invoke(arg);
                    }

                    // 2) Optional relay Service -> Service
                    String toTag = arg.optString("toTag", null);
                    if (toTag != null && !toTag.isEmpty()) {
                        Pipe target = FlutterBackgroundServicePlugin.getOrCreatePipeForTag(toTag);
                        // For service sinks, set tag to the destination tag to hit the right EventChannel subscribers
                        arg.put("tag", toTag);
                        target.invoke(arg);
                    }

                    result.success(true);
                } catch (Exception e) {
                    result.error("send-data-failure", e.getMessage(), e);
                }
                return;
            }

            if (method.equalsIgnoreCase("openApp")) {
                try {
                    String packageName = getPackageName();
                    Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(launchIntent);
                        result.success(true);
                        return;
                    }
                } catch (Exception e) {
                    result.error("open app failure", e.getMessage(), e);
                }
                return;
            }
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }

        result.notImplemented();
    }
}
