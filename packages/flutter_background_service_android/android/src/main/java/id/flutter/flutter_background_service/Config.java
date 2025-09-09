package id.flutter.flutter_background_service;

import android.content.Context;
import android.content.SharedPreferences;


public class Config {

    private static final String PREF = "flutter_background_service";
    private final SharedPreferences prefs;
    private final String prefix; // e.g., "default:" or "location:"

    // Constructor for global config (fallback to "default" if no tag is passed)
    public Config(Context ctx) {
        this(ctx, "default");
    }

    // New constructor to support tag-specific configurations
    public Config(Context ctx, String tag) {
        this.prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        this.prefix = (tag == null || tag.isEmpty()) ? "default:" : (tag + ":");
    }

    // Helper to generate unique keys per tag
    private String key(String base) {
        return prefix + base;
    }

    public boolean isAutoStartOnBoot() {
        return prefs.getBoolean(key("auto_start_on_boot"), false);
    }

    public void setAutoStartOnBoot(boolean value) {
        prefs.edit().putBoolean(key("auto_start_on_boot"), value).apply();
    }

    public boolean isForeground() {
        return prefs.getBoolean(key("is_foreground_mode"), true);
    }

    public void setIsForeground(boolean value) {
        prefs.edit().putBoolean(key("is_foreground_mode"), value).apply();
    }

    public boolean isManuallyStopped() {
        return prefs.getBoolean(key("manually_stopped"), false);
    }

    public void setManuallyStopped(boolean value) {
        prefs.edit().putBoolean(key("manually_stopped"), value).apply();
    }

    public long getBackgroundHandle() {
        return prefs.getLong(key("background_handle"), 0L);
    }

    public void setBackgroundHandle(long handle) {
        prefs.edit().putLong(key("background_handle"), handle).apply();
    }

    public String getInitialNotificationTitle() {
        return prefs.getString(key("initial_notification_title"), "Background service");
    }

    public void setInitialNotificationTitle(String title) {
        prefs.edit().putString(key("initial_notification_title"), title).apply();
    }

    public String getInitialNotificationContent() {
        return prefs.getString(key("initial_notification_content"), "Running…");
    }

    public void setInitialNotificationContent(String content) {
        prefs.edit().putString(key("initial_notification_content"), content).apply();
    }
    
    public String getNotificationChannelId() {
        return prefs.getString(key("notification_channel_id"), null);
    }

    public void setNotificationChannelId(String id) {
        prefs.edit().putString(key("notification_channel_id"), id).apply();
    }

    public int getForegroundNotificationId() {
        return prefs.getInt(key("foreground_notification_id"), 1001);
    }

    public void setForegroundNotificationId(Integer id) {
        if (id == null) {
            prefs.edit().remove(key("foreground_notification_id")).apply();
        } else {
            prefs.edit().putInt(key("foreground_notification_id"), id).apply();
        }
    }

    public String getForegroundServiceTypes() {
        return prefs.getString(key("foreground_service_types"), null);
    }

    public void setForegroundServiceTypes(String csv) {
        prefs.edit().putString(key("foreground_service_types"), csv).apply();
    }

    public boolean isConfigured() {
        // Check if the minimal required setting exists in SharedPreferences
        return prefs.contains(key("background_handle"));
    }
}
