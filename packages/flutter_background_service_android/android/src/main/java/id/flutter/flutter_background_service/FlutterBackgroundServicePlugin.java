package id.flutter.flutter_background_service;

//import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

//import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import java.util.concurrent.ConcurrentHashMap;
import android.content.SharedPreferences;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;


/**
 * FlutterBackgroundServicePlugin
 */
public class FlutterBackgroundServicePlugin implements FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
    private static final String TAG = "BackgroundServicePlugin";
    private Handler mainHandler;
    
    // Map of tag -> Intent
    private final Map<String, Intent> runningServices = new HashMap<>();
    // Map of tag -> Pipe
    private static final Map<String, Pipe> pipesByTag = new ConcurrentHashMap<>();
    // Map of tag -> List of sinks
    private static final Map<String, List<EventChannel.EventSink>> sinksByTag = new HashMap<>();

    public static final Set<String> readyTags = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private MethodChannel channel;
    private EventChannel eventChannel;
    // Used for tracking EventChannel connections from Dart
    private final Map<Object, EventChannel.EventSink> eventSinks = new HashMap<>();

    private Context context;

    public static final Pipe servicePipe = new Pipe();
    public static final Pipe mainPipe = new Pipe();

    private final Pipe.PipeListener listener = object -> receiveData(object);


    static Pipe getOrCreatePipeForTag(String tag) {
        Pipe p = pipesByTag.get(tag);
        if (p == null) {
            synchronized (pipesByTag) {
                p = pipesByTag.get(tag);
                if (p == null) {
                    p = new Pipe();
                    pipesByTag.put(tag, p);
                }
            }
        }
        return p;
    }

    static Pipe getPipeForTagIfExists(String tag) {
        return pipesByTag.get(tag);
    }

    public static void removeTagFromPluginState(String tag) {
        synchronized (pipesByTag) {
            Pipe p = pipesByTag.remove(tag);
            if (p != null) {
                try { p.dispose(); } catch (Throwable ignored) {}
            }
        }
        // Clear UI sinks for that tag
        synchronized (sinksByTag) {
            sinksByTag.remove(tag);
        }

        synchronized (readyTags) {
            readyTags.remove(tag);
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.context = flutterPluginBinding.getApplicationContext();
        this.mainHandler = new Handler(context.getMainLooper());

        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), 
            "id.flutter/background_service/android/method", JSONMethodCodec.INSTANCE);
        channel.setMethodCallHandler(this);

        eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), 
            "id.flutter/background_service/android/event", JSONMethodCodec.INSTANCE);
        eventChannel.setStreamHandler(this);

        mainPipe.addListener(listener);
    }

    private void start(String serviceType, String tag) {
        WatchdogReceiver.enqueue(context);

        Config tagConfig = new Config(context, tag);
        if (!tagConfig.isConfigured()) {
            Log.e(TAG, "No configuration found for tag: " + tag);
            return;
        }
        boolean isForeground = tagConfig.isForeground();

        Class<?> serviceClass = "location".equalsIgnoreCase(serviceType)
                ? BackgroundServiceLocation.class
                : BackgroundService.class;

        // Persist last_tag (fallback if Intent extras are lost)
        context.getSharedPreferences("bgsvc", Context.MODE_PRIVATE)
            .edit()
            .putString(serviceClass.getName() + ":last_tag", tag)
            .apply();

        Intent intent = new Intent(context, serviceClass);
        intent.putExtra("tag", tag);
        intent.putExtra("serviceType", serviceType);

        synchronized (runningServices) {
            runningServices.put(tag, new Intent(intent));
        }

        getOrCreatePipeForTag(tag); // ensure pipe exists

        try {
            if (isForeground) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "Started service for tag=" + tag + " type=" + serviceType);
            Log.i("BackgroundServicePlugin", "Starting service class="+ serviceClass.getSimpleName() + " tagExtra=" + tag);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start service for tag=" + tag + ": " + e.getMessage(), e);
            synchronized (runningServices) {
                runningServices.remove(tag);
            }
            context.getSharedPreferences("bgsvc", Context.MODE_PRIVATE)
                .edit()
                .remove(serviceClass.getName() + ":last_tag")
                .apply();
        }
    }

    private boolean stop(String tag) {
        if (tag == null || tag.isEmpty()) tag = "default";


        // peek the current intent first (don’t remove yet)
        Intent intent;
        synchronized (runningServices) {
            intent = runningServices.get(tag);
        }

        // best-effort: stop OS service
        boolean stopped = false;
        if (intent != null) {
            stopped = context.stopService(intent);
            Log.i(TAG, "Requested stop for tag=" + tag + " result=" + stopped);
        } else {
            Log.w(TAG, "Stop requested for tag=" + tag + " but no running service tracked.");
        }

        // clean local state for this tag
        synchronized (runningServices) { runningServices.remove(tag); }
        removeTagFromPluginState(tag);

        // clear both last_tag keys (harmless if absent)
        SharedPreferences sp = context.getSharedPreferences("bgsvc", Context.MODE_PRIVATE);
        sp.edit()
        .remove(BackgroundService.class.getName() + ":last_tag")
        .remove(BackgroundServiceLocation.class.getName() + ":last_tag")
        .apply();

        Log.i(TAG, "Requested stop for tag=" + tag + " result=" + stopped);
        return stopped;
    }

    private void stopAllServices() {
        Log.i(TAG, "Stopping ALL background services (default + location)");

        // 1) Best-effort graceful stop: broadcast "stopService" to any service isolates
        try {
            JSONObject msg = new JSONObject();
            msg.put("method", "stopService");
            msg.put("args", new JSONObject());
            // send to all tags
            synchronized (pipesByTag) {
                for (Pipe pipe : pipesByTag.values()) {
                    if (pipe.hasListener()) {
                        pipe.invoke(msg);
                    }
                }
            }
            // Also broadcast to the shared main pipe in case listeners live there
            if (mainPipe.hasListener()) {
                mainPipe.invoke(msg);
            }
            context.getSharedPreferences("bgsvc", Context.MODE_PRIVATE)
                .edit()
                .remove(BackgroundService.class.getName() + ":last_tag")
                .remove(BackgroundServiceLocation.class.getName() + ":last_tag")
                .apply();

        } catch (Exception ignore) {
            Log.w(TAG, "Failed to broadcast stopService message: " + ignore.getMessage(), ignore);
        }

        // 2) Cancel watchdog restarts
        try {
            WatchdogReceiver.remove(context);
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove watchdog: " + e.getMessage(), e);
        }

        // 3) Stop both Android Services explicitly (there is only one instance per class)
        try {
            context.stopService(new Intent(context, BackgroundService.class));
        } catch (Exception e) {
            Log.w(TAG, "stopService(BackgroundService) failed: " + e.getMessage(), e);
        }

        try {
            context.stopService(new Intent(context, BackgroundServiceLocation.class));
        } catch (Exception e) {
            Log.w(TAG, "stopService(BackgroundServiceLocation) failed: " + e.getMessage(), e);
        }

        // 4) Clear in-memory tracking
        synchronized (runningServices) {
            runningServices.clear();
        }
        synchronized (pipesByTag) {
            pipesByTag.clear();
        }
        synchronized (sinksByTag) {
            sinksByTag.clear();
        }

        // 5) Clear tag registries on each service
        try {
            BackgroundService.ACTIVE_TAGS.clear();
        } catch (Throwable ignore) {}
        try {
            BackgroundServiceLocation.ACTIVE_TAGS.clear();
        } catch (Throwable ignore) {}

        Log.i(TAG, "All background services stopped and plugin state cleared.");
    }


    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        String method = call.method;
        JSONObject arg = (JSONObject) call.arguments;

        try {
            String serviceType = "default";
            if (arg != null && arg.has("serviceType")) {
                serviceType = arg.getString("serviceType");
            }

            if ("configure".equals(method)) {
                if (arg == null || !arg.has("tag")) {
                    result.error("invalid-args", "configure requires 'tag'", null);
                    return;
                }

                // 1) Extract args
                final String tag = arg.getString("tag"); // <- get tag first
                long backgroundHandle = arg.getLong("background_handle");
                boolean isForeground = arg.getBoolean("is_foreground_mode");
                boolean autoStartOnBoot = arg.getBoolean("auto_start_on_boot");
                boolean autoStart = arg.getBoolean("auto_start");
                String initialNotificationTitle = arg.optString("initial_notification_title", null);
                String initialNotificationContent = arg.optString("initial_notification_content", null);
                String notificationChannelId = arg.optString("notification_channel_id", null);
                Integer foregroundNotificationId = arg.has("foreground_notification_id")
                        ? arg.getInt("foreground_notification_id") : null;
                JSONArray foregroundServiceTypes = arg.optJSONArray("foreground_service_types");

                String foregroundServiceTypesStr = null;
                if (foregroundServiceTypes != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < foregroundServiceTypes.length(); i++) {
                        sb.append(foregroundServiceTypes.getString(i));
                        if (i < foregroundServiceTypes.length() - 1) sb.append(",");
                    }
                    foregroundServiceTypesStr = sb.toString();
                }

                // 2) Resolve service class from serviceType
                Class<?> serviceClass = "location".equalsIgnoreCase(serviceType)
                        ? BackgroundServiceLocation.class
                        : BackgroundService.class;

                // 3) Persist registry + last_tag (per service class)
                android.content.SharedPreferences sp =
                        context.getSharedPreferences("bgsvc", Context.MODE_PRIVATE);

                final String registryKey = serviceClass.getName() + ":registry";
                java.util.Set<String> tags =
                        new java.util.HashSet<>(sp.getStringSet(registryKey, java.util.Collections.emptySet()));
                tags.add(tag);
                sp.edit()
                .putStringSet(registryKey, tags)
                .putString(serviceClass.getName() + ":last_tag", tag) // belt & suspenders fallback
                .apply();

                // 4) Store tag-scoped config in your existing Config store
                Config tagConfig = new Config(context, tag);
                tagConfig.setBackgroundHandle(backgroundHandle);
                tagConfig.setIsForeground(isForeground);
                tagConfig.setAutoStartOnBoot(autoStartOnBoot);
                tagConfig.setInitialNotificationTitle(initialNotificationTitle);
                tagConfig.setInitialNotificationContent(initialNotificationContent);
                tagConfig.setNotificationChannelId(notificationChannelId);
                tagConfig.setForegroundNotificationId(foregroundNotificationId);
                tagConfig.setForegroundServiceTypes(foregroundServiceTypesStr);

                Log.d(TAG, "Configuration set for tag: " + tag + " (type=" + serviceType + ")");

                // 5) Optionally start now
                if (autoStart) {
                    start(serviceType, tag);
                }

                result.success(true);
                return;
            }

            if ("start".equals(method)) {
                if (arg == null || !arg.has("tag")) {
                    result.error("invalid-args", "start requires 'tag'", null);
                    return;
                }
                String tag = arg.getString("tag");
                start(serviceType, tag);
                result.success(true);
                return;
            }

            if ("stop".equals(method)) {
                if (arg == null || !arg.has("tag")) {
                    result.error("invalid-args", "stop requires 'tag'", null);
                    return;
                }
                String tag = arg.getString("tag");
                boolean stopped = stop(tag);
                result.success(stopped);
                return;
            }

            if ("stopAll".equals(method)) {
                stopAllServices();
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("isServiceRunning")) {
                String tag = (arg != null && arg.has("tag")) ? arg.optString("tag", "default") : "default";
                boolean running = isServiceRunning(tag);
                result.success(running);
                return;
            }

            if (method.equalsIgnoreCase("sendData")) {
                // Prefer explicit relay key; fall back to tag for UI->service calls
                String targetTag = arg.optString("toTag", null);
                if (targetTag == null) targetTag = arg.optString("tag", null);

                if (targetTag == null) {
                    result.error("101", "Missing 'tag' in sendData", null);
                    return;
                }

                // Broadcast to all tags
                if ("all".equalsIgnoreCase(targetTag)) {
                    boolean sent = false;
                    synchronized (pipesByTag) {
                        for (Pipe pipe : pipesByTag.values()) {
                            if (pipe.hasListener()) {
                                pipe.invoke(arg);
                                sent = true;
                            }
                        }
                    }
                    result.success(sent);
                    return;
                }

                Pipe pipe = pipesByTag.get(targetTag);
                if (pipe != null && pipe.hasListener()) {
                    pipe.invoke(arg);
                    result.success(true);
                } else {
                    result.success(false);
                }
                return;
            }
            
            result.notImplemented();
        } catch (Exception e) {
            Log.e(TAG, "Error in onMethodCall: " + e.getMessage(), e);
            result.error("100", "Failed while reading arguments", e.getMessage());
        }
    }

    private boolean isServiceRunning(String tag) {
        if (tag == null || tag.isEmpty()) return false;

        // TRUE only when the Dart isolate for this tag has signaled "ready".
        return readyTags.contains(tag);

        // /// 1) Prefer explicit bookkeeping
        // synchronized (runningServices) {
        //     if (runningServices.containsKey(tag)) return true;
        // }

        // /// 2) Check services’ own active-tag sets
        // if (BackgroundService.ACTIVE_TAGS.contains(tag)) return true;
        // if (BackgroundServiceLocation.ACTIVE_TAGS.contains(tag)) return true;

        // /// 3) Optionally: if the tag was never configured, treat as not running
        // Config cfg = new Config(context, tag);
        // if (!cfg.isConfigured()) return false;

        // /// 4) Don’t fall back to broad ActivityManager scan (it’s not tag-aware)
        // return false;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        mainPipe.removeListener(listener);
        channel.setMethodCallHandler(null);
        channel = null;

        synchronized (eventSinks) {
            eventSinks.clear();
        }
        eventChannel.setStreamHandler(null);
        eventChannel = null;
    }

    private void receiveData(JSONObject data) {
        final String method = data.optString("method", "");
        String dataTag = data.optString("tag", null);

        if ("ready".equalsIgnoreCase(method) && dataTag != null) {
            readyTags.add(dataTag);
        } else if (("notReady".equalsIgnoreCase(method) || "stopService".equalsIgnoreCase(method))
                    && dataTag != null) {
            readyTags.remove(dataTag);
        }

        if ("all".equalsIgnoreCase(dataTag) || dataTag == null) {
            synchronized (sinksByTag) {
                for (List<EventChannel.EventSink> sinks : sinksByTag.values()) {
                    for (EventChannel.EventSink sink : sinks) {
                        mainHandler.post(() -> sink.success(data));
                    }
                }
            }
        } else {
            List<EventChannel.EventSink> sinks;
            synchronized (sinksByTag) {
                sinks = sinksByTag.get(dataTag);
            }
            if (sinks != null) {
                for (EventChannel.EventSink sink : sinks) {
                    mainHandler.post(() -> sink.success(data));
                }
            }
        }
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        String tag = arguments instanceof JSONObject ? ((JSONObject) arguments).optString("tag", "default") : "default";
        synchronized (sinksByTag) {
            sinksByTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(events);
        }
        synchronized (eventSinks) {
            eventSinks.put(arguments, events);
        }
    }

    @Override
    public void onCancel(Object arguments) {
        String tag = arguments instanceof JSONObject ? ((JSONObject) arguments).optString("tag", "default") : "default";
        synchronized (sinksByTag) {
            List<EventChannel.EventSink> sinks = sinksByTag.get(tag);
            if (sinks != null) sinks.remove(eventSinks.get(arguments));
        }
        synchronized (eventSinks) {
            eventSinks.remove(arguments);
        }
    }
}

