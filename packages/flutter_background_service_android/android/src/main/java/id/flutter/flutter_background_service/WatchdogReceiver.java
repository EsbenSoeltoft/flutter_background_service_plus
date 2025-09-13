package id.flutter.flutter_background_service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.AlarmManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static android.content.Context.ALARM_SERVICE;

public class WatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "WatchdogReceiver";
    private static final String PREFS = "bgsvc";
    private static final int QUEUE_REQUEST_ID = 111;
    private static final String ACTION_RESPAWN = "id.flutter.background_service.RESPAWN";

    /** Default to 5s, adjust if you expose a knob. */
    private static final long DEFAULT_INTERVAL_MS = 5000L;

    public static void enqueue(Context context) {
        enqueue(context, DEFAULT_INTERVAL_MS);
    }

    public static void enqueue(Context context, long millis) {
        final AlarmManager manager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (manager == null) {
            Log.w(TAG, "AlarmManager is null; cannot schedule watchdog.");
            return;
        }

        final Intent intent = new Intent(context, WatchdogReceiver.class).setAction(ACTION_RESPAWN);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // We never mutate the intent after creation → immutable is safer.
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        final PendingIntent pIntent =
                PendingIntent.getBroadcast(context, QUEUE_REQUEST_ID, intent, flags);

        final long triggerAtMillis = System.currentTimeMillis() + Math.max(0, millis);

        boolean canUseExact = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            canUseExact = manager.canScheduleExactAlarms();
        }

        try {
            if (canUseExact) {
                // Prefer exact + allow while idle when possible (API 23+).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pIntent);
                } else {
                    AlarmManagerCompat.setExact(manager, AlarmManager.RTC_WAKEUP, triggerAtMillis, pIntent);
                }
            } else {
                // Fall back to inexact but allowed while idle.
                AlarmManagerCompat.setAndAllowWhileIdle(manager, AlarmManager.RTC_WAKEUP, triggerAtMillis, pIntent);
            }
        } catch (SecurityException se) {
            // Happens on S+ if app lacks SCHEDULE_EXACT_ALARM and we attempted exact;
            // retry with allow-while-idle inexact.
            Log.w(TAG, "Exact alarm denied; retrying with allowWhileIdle", se);
            AlarmManagerCompat.setAndAllowWhileIdle(manager, AlarmManager.RTC_WAKEUP, triggerAtMillis, pIntent);
        }
    }

    public static void remove(Context context) {
        final AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (alarmManager == null) return;

        final Intent intent = new Intent(context, WatchdogReceiver.class).setAction(ACTION_RESPAWN);

        int flags = PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        final PendingIntent pi =
                PendingIntent.getBroadcast(context, QUEUE_REQUEST_ID, intent, flags);
        if (pi != null) {
            alarmManager.cancel(pi);
            pi.cancel();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        startRegisteredTagsForService(context, BackgroundService.class, "default");
        startRegisteredTagsForService(context, BackgroundServiceLocation.class, "location");

        // Keep ticking even if the Service forgets to enqueue again.
        enqueue(context, DEFAULT_INTERVAL_MS);
    }

    private void startRegisteredTagsForService(Context context, Class<?> svcClass, String serviceType) {
        final SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String className = svcClass.getName();
        final String registryKey = className + ":registry";

        Set<String> reg = sp.getStringSet(registryKey, Collections.<String>emptySet());
        final Set<String> registry = (reg == null) ? Collections.<String>emptySet() : new HashSet<>(reg);

        if (registry.isEmpty()) {
            Log.i(TAG, "No tags registered for " + className + " (registry empty).");
            return;
        }

        for (String tag : registry) {
            if (tag == null || tag.isEmpty()) continue;

            final Config cfg = new Config(context, tag);

            // Respect manual stop: do not resurrect this tag.
            if (cfg.isManuallyStopped()) {
                //Log.i(TAG, "Watchdog skip " + className + " tag=" + tag + " (manually stopped).");
                continue;
            }

            final Intent start = new Intent(context, svcClass)
                    .putExtra("tag", tag)     // valid tag from registry only; never "default"
                    .putExtra("type", serviceType);

            try {
                if (cfg.isForeground()) {
                    //Log.i(TAG, "Watchdog starting FGS " + className + " tag=" + tag + " type=" + serviceType);
                    ContextCompat.startForegroundService(context, start);
                } else {
                    //Log.i(TAG, "Watchdog starting BG " + className + " tag=" + tag + " type=" + serviceType);
                    context.startService(start);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Watchdog failed to start " + className + " tag=" + tag + " type=" + serviceType + " : " + t);
            }
        }
    }
}
