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
import java.util.concurrent.atomic.AtomicInteger;
import android.content.SharedPreferences;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;


public class BackgroundService extends Service implements MethodChannel.MethodCallHandler {
    private static final String TAG = "BackgroundService";
    private static final String PREFS = "bgsvc";
    private static final String LOCK_NAME = BackgroundService.class.getName() + ".Lock";
    public static volatile WakeLock lockStatic = null; // static
    private static final long WAKELOCK_TIMEOUT_MS = 60_000L;
    /** Debug-only logical count of acquisitions we perform in this class. */
    private static final AtomicInteger WAKELOCK_HELD_COUNT = new AtomicInteger(0);
    /** Guard so we only release the bootstrap acquire once (ready or finally/destroy). */
    private final AtomicBoolean bootstrapLockReleased = new AtomicBoolean(false);

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
    private String currentTag = "default";

    public static final Set<String> ACTIVE_TAGS = Collections.synchronizedSet(new HashSet<>());


    /** Release the bootstrap wakelock exactly once, with a reason for logs. */
    private void releaseBootstrapWakeLock(String reason) {
        try {
            if (!bootstrapLockReleased.compareAndSet(false, true)) {
                return; // already released
            }
            if (lockStatic != null && lockStatic.isHeld()) {
                lockStatic.release();
                int c = WAKELOCK_HELD_COUNT.decrementAndGet();
                Log.i(TAG, "WakeLock released [" + reason + "], heldCount=" + c);
            } else {
                Log.d(TAG, "WakeLock not held at release [" + reason + "]");
            }
        } catch (Throwable t) {
            Log.w(TAG, "WakeLock release failed [" + reason + "]", t);
        }
    }

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

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        this.isRunning.set(false);

        if (this.backgroundEngine != null) {
            this.backgroundEngine.getServiceControlSurface().detachFromService();
            this.backgroundEngine.destroy();
            this.backgroundEngine = null;
        }

        // --- RELEASE WAKELOCK ---
        // try {
        //     if (lockStatic != null && lockStatic.isHeld()) {
        //         lockStatic.release();
        //         Log.i(TAG, "WakeLock released in onDestroy()");
        //     }
        // } catch (Exception e) {
        //     Log.w(TAG, "Failed to release WakeLock: " + e.getMessage(), e);
        // }
        // --- RELEASE WAKELOCK (guarded, once) ---
        releaseBootstrapWakeLock("onDestroy");
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
            CharSequence name = "Background Service";
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

        // Final guard + sanitize again (defensive)
        if (this.notificationChannelId == null || this.notificationChannelId.trim().isEmpty()
            || "null".equalsIgnoreCase(this.notificationChannelId.trim())) {
            this.notificationChannelId = "FOREGROUND_" + (this.currentTag != null ? this.currentTag : "default");
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
                    CharSequence name = "Background Service";
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
        if (SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        int requestCode = 11 + Math.abs(this.currentTag.hashCode() % 1000);
        PendingIntent pi = PendingIntent.getActivity(BackgroundService.this, requestCode, i, flags);
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

            ServiceCompat.startForeground(this, this.notificationId, mBuilder.build(), serviceType);
        } catch (SecurityException e) {
            Log.w(TAG, "Failed to start foreground service due to SecurityException - have you forgotten to request a permission? - " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Fast path: if engine is already running, just refresh notification.
        if (this.isRunning.get()) {
            if (this.config != null) updateNotificationInfo();
            return START_REDELIVER_INTENT;
        }

        // 1) Resolve tag (intent → prefs → fail)
        final String lastTagKey = getClass().getName() + ":last_tag";
        final String registryKey = getClass().getName() + ":registry";

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        String tagFromIntent = (intent != null && intent.hasExtra("tag"))
            ? intent.getStringExtra("tag") : null;

        String tagFromPrefs = sp.getString(lastTagKey, null);

        // Read registry set
        Set<String> registry = sp.getStringSet(registryKey, Collections.emptySet());

        // Accept Intent tag only if it’s non-empty AND registered.
        boolean validIntentTag = tagFromIntent != null
            && !tagFromIntent.isEmpty()
            && registry.contains(tagFromIntent);

        String resolvedTag = validIntentTag
            ? tagFromIntent
            : (tagFromPrefs != null && !tagFromPrefs.isEmpty() ? tagFromPrefs : null);

        if (resolvedTag == null) {
            // Nothing persisted yet → don’t spin up a “default” isolate; wait for a real start.
            return START_REDELIVER_INTENT;
        }

        this.currentTag = resolvedTag;

        // 2) Persist immediately (commit, not apply) so re-deliveries can rehydrate reliably
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putString(lastTagKey, this.currentTag).apply();

        // 3) Register ACTIVE_TAG + pipe listener before engine boot
        ACTIVE_TAGS.add(this.currentTag);
        Pipe myPipe = FlutterBackgroundServicePlugin.getOrCreatePipeForTag(this.currentTag);
        myPipe.addListener(listener);

        // 4) Tag-scoped config & notification/channel setup
        this.config = new Config(this, this.currentTag);
        this.config.setManuallyStopped(false);

        String storedChannelId = this.config.getNotificationChannelId();
        // Some prefs/JSON paths can store the literal "null" string — treat it as empty
        if (storedChannelId != null && "null".equalsIgnoreCase(storedChannelId.trim())) {
            storedChannelId = null;
        }

        this.notificationChannelId = (storedChannelId == null || storedChannelId.trim().isEmpty())
            ? ("FOREGROUND_" + this.currentTag)
            : storedChannelId;

        // Ensure channel exists on O+
        if (SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(this.notificationChannelId) == null) {
                createNotificationChannel(); // uses this.notificationChannelId
            }
        }

        this.notificationTitle = this.config.getInitialNotificationTitle();
        this.notificationContent = this.config.getInitialNotificationContent();
        this.notificationId = this.config.getForegroundNotificationId();
        if (this.notificationId <= 0) {
            this.notificationId = 1000 + Math.abs(this.currentTag.hashCode() % 800000);
        }
        this.configForegroundTypes = this.config.getForegroundServiceTypes();

        if (this.config.isForeground()) {
            updateNotificationInfo(); // promotes to foreground within the 5s window
        } else {
            Log.i(TAG, "Starting in background mode (no foreground notification).");
        }

        // 5) Watchdog + boot the engine with the **resolvedTag**
        WatchdogReceiver.enqueue(this);
        runService(this.currentTag);  // passes [handle, tag] to Dart (see file)

        return START_REDELIVER_INTENT;
    }


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
        final WakeLock wl = getLock(getApplicationContext());
        // Acquire with a timeout so we never risk a stuck wakelock on unexpected paths.
        wl.acquire(WAKELOCK_TIMEOUT_MS);
        // Logical count for debug; matches our single release later.
        if (BuildConfig.DEBUG) {
            int c = WAKELOCK_HELD_COUNT.incrementAndGet();
            Log.d(TAG, "WakeLock acquire (timeout=" + WAKELOCK_TIMEOUT_MS + "ms), heldCount=" + c);
        } else {
            WAKELOCK_HELD_COUNT.incrementAndGet();
        }
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
            this.backgroundEngine.getServiceControlSurface().attachToService(BackgroundService.this, null, this.config.isForeground());

            this.methodChannel = new MethodChannel(
                    this.backgroundEngine.getDartExecutor().getBinaryMessenger(),
                    "id.flutter/background_service_android_bg",
                    JSONMethodCodec.INSTANCE
            );
            this.methodChannel.setMethodCallHandler(this);

            this.dartEntrypoint = new DartExecutor.DartEntrypoint(
                    flutterLoader.findAppBundlePath(),
                    "package:flutter_background_service_android/flutter_background_service_android.dart",
                    "entrypoint"
            );

            final List<String> args = new ArrayList<>();
            args.add(String.valueOf(this.config.getBackgroundHandle())); // background handle
            args.add(tag); // pass tag to Dart

            this.backgroundEngine.getDartExecutor().executeDartEntrypoint(this.dartEntrypoint, args);

        } catch (UnsatisfiedLinkError e) {
            //After a reboot this may happen briefly and can be ignored

            // Optional: surface a friendly message via your foreground notification
            this.notificationContent = "Error " + e.getMessage();
            try { updateNotificationInfo(); } catch (Throwable ignore) {}
            // Rethrow so the failure isn’t hidden (lets watchdog/app handle restart)
            throw e;

        } catch (Throwable t) {
            throw t;
        } finally {
            // // Single point of release (avoids under/over-release).
            // try {
            //     if (wl.isHeld()) {
            //         wl.release();
            //         Log.i(TAG, "WakeLock released after bootstrap");
            //     }
            // } catch (Throwable relErr) {
            //     Log.w(TAG, "WakeLock release failed in finally", relErr);
            // }
            // Fallback release if 'ready' didn't arrive.
            releaseBootstrapWakeLock("bootstrap finally");
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
            // Early 'ready' handshake: release bootstrap wakelock ASAP.
            if (method.equalsIgnoreCase("ready")) {
                releaseBootstrapWakeLock("ready handshake");
                // Optionally update notification/title to "Running"
                result.success(true);
                return;
            }

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
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
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
