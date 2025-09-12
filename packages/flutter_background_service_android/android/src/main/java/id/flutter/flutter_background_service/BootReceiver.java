package id.flutter.flutter_background_service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final String PREFS = "bgsvc";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onReceive action=" + action);
        if (action == null) return;

        if (!isBootAction(action)) {
            Log.i(TAG, "Ignoring non-boot action: " + action);
            return;
        }

        // Restart any tags that were configured to auto-start on boot.
        startRegisteredTagsForService(context, BackgroundService.class, /*serviceType*/"default", /*requireBootAutostart*/true);
        startRegisteredTagsForService(context, BackgroundServiceLocation.class, /*serviceType*/"location", /*requireBootAutostart*/true);
    }

    private boolean isBootAction(String action) {
        return "android.intent.action.BOOT_COMPLETED".equals(action)
            || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
            || "android.intent.action.QUICKBOOT_POWERON".equals(action)
            || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
    }

    private void startRegisteredTagsForService(Context context,
                                               Class<?> svcClass,
                                               String serviceType,
                                               boolean requireBootAutostart) {
        final SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String className = svcClass.getName();
        final String registryKey = className + ":registry";

        Set<String> reg = sp.getStringSet(registryKey, Collections.<String>emptySet());
        final Set<String> registry = reg == null ? Collections.<String>emptySet() : new HashSet<>(reg);

        if (registry.isEmpty()) {
            Log.i(TAG, "No tags registered for " + className + " (registry empty).");
            return;
        }

        for (String tag : registry) {
            if (tag == null || tag.isEmpty()) continue;

            final Config cfg = new Config(context, tag);

            if (requireBootAutostart && !cfg.isAutoStartOnBoot()) {
                Log.i(TAG, "Skip tag=" + tag + " for " + className + " (autoStartOnBoot=false).");
                continue;
            }

            if (cfg.isManuallyStopped()) {
                Log.i(TAG, "Skip tag=" + tag + " for " + className + " (manually stopped).");
                continue;
            }

            final Intent start = new Intent(context, svcClass);
            // Only ever pass **valid** tags (from registry); never "default".
            start.putExtra("tag", tag);
            start.putExtra("type", serviceType);

            try {
                if (cfg.isForeground()) {
                    Log.i(TAG, "Starting FGS " + className + " tag=" + tag + " type=" + serviceType);
                    ContextCompat.startForegroundService(context, start);
                } else {
                    Log.i(TAG, "Starting BG " + className + " tag=" + tag + " type=" + serviceType);
                    context.startService(start);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to start " + className + " tag=" + tag + " type=" + serviceType + " : " + t);
            }
        }
    }
}




// public class BootReceiver extends BroadcastReceiver {
//     @SuppressLint("WakelockTimeout")
//     @Override
//     public void onReceive(Context context, Intent intent) {
//         if (intent.getAction().equals(Intent.ACTION_MY_PACKAGE_REPLACED) || intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED) || intent.getAction().equals("android.intent.action.QUICKBOOT_POWERON")) {
//             final Config config = new Config(context);
//             boolean autoStart = config.isAutoStartOnBoot();
//             if (autoStart) {
//                 if (BackgroundService.lockStatic == null) {
//                     BackgroundService.getLock(context).acquire();
//                 }

//                 if (config.isForeground()) {
//                     ContextCompat.startForegroundService(context, new Intent(context, BackgroundService.class));
//                 } else {
//                     context.startService(new Intent(context, BackgroundService.class));
//                 }
//             }
//         }
//     }
// }
