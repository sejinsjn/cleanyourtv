package com.gathin.tvhome;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Android takes an app's accessibility services off the enabled list when the app is force-stopped (Settings, a
 * cleaner app, or am force-stop), and the remote's Home, Input and app buttons then stop working until the helper
 * is turned on again over ADB. With restore_key_services on, a running helper records itself here and drops the
 * record when Android unbinds it (the user turned it off); a force stop kills the process before that, so the
 * record stays. Home starting again puts recorded helpers back on the list, when the installer granted
 * WRITE_SECURE_SETTINGS. Some TCL firmware also resets the global accessibility switch at boot while leaving the
 * helper list intact; in that case the list is briefly cleared and restored to make the services bind again.
 * That also reconnects other accessibility services, so it only runs behind the same explicit opt-in.
 */
final class ServiceRepair {
    private static final String TAG = "TvHomeServiceRepair";
    private static final String PREFS = "accessibility_helpers";
    private static final String PENDING_SERVICES = "pending_rebind_services";
    private static final long REBIND_DELAY_MS = 1000;

    private ServiceRepair() {
    }

    static void connected(Context context, Class<?> service) {
        prefs(context).edit().putBoolean(service.getName(), true).apply();
    }

    static void disconnected(Context context, Class<?> service) {
        prefs(context).edit().remove(service.getName()).apply();
    }

    static boolean hasRecordedHomeService(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return false;
        Context appContext = context.getApplicationContext();
        SharedPreferences repairPrefs = prefs(appContext);
        if (!recordedServices(repairPrefs).contains(HomeKeyService.class.getName())) return false;
        String list = Settings.Secure.getString(appContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return containsAllRecordedServices(list, appContext.getPackageName(),
                Collections.singleton(HomeKeyService.class.getName()));
    }

    static void restore(Context context) {
        restore(context, null);
    }

    static void restore(Context context, Runnable onComplete) {
        Runnable completion = onComplete == null ? () -> { } : onComplete;
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS is not granted; accessibility helpers were not changed");
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences repairPrefs = prefs(appContext);
        ContentResolver cr = appContext.getContentResolver();
        try {
            if (repairPrefs.contains(PENDING_SERVICES)) {
                beginRebind(cr, repairPrefs, completion);
                return;
            }

            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            Set<String> recorded = recordedServices(repairPrefs);
            if (recorded.isEmpty()) {
                completion.run();
                return;
            }

            boolean accessibilityEnabled =
                    Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 0;
            boolean allRecordedServicesEnabled =
                    containsAllRecordedServices(list, appContext.getPackageName(), recorded);
            if (needsAccessibilityRebind(accessibilityEnabled, allRecordedServicesEnabled)) {
                if (!repairPrefs.edit().putString(PENDING_SERVICES, normalizeList(list)).commit()) {
                    Log.e(TAG, "Could not persist the accessibility service list before rebind");
                    return;
                }
                beginRebind(cr, repairPrefs, completion);
                return;
            }

            String updated = appendMissingServices(list, appContext.getPackageName(), recorded);
            if (updated.equals(normalizeList(list))) {
                completion.run();
                return;
            }
            restoreCrashedServices(cr, list, updated, appContext.getPackageName(), recorded, completion);
        } catch (SecurityException denied) {
            Log.e(TAG, "Permission denied while restoring accessibility helpers", denied);
        }
    }

    private static Set<String> recordedServices(SharedPreferences repairPrefs) {
        Set<String> services = new HashSet<>();
        for (Map.Entry<String, ?> entry : repairPrefs.getAll().entrySet()) {
            if (!PENDING_SERVICES.equals(entry.getKey()) && Boolean.TRUE.equals(entry.getValue())) {
                services.add(entry.getKey());
            }
        }
        return services;
    }

    static String appendMissingServices(String list, String packageName, Set<String> recorded) {
        String normalized = normalizeList(list);
        StringBuilder updated = new StringBuilder(normalized);
        Set<String> enabled = new HashSet<>();
        for (String entry : normalized.split(":")) {
            String component = canonicalComponent(entry);
            if (component != null) enabled.add(component);
        }
        for (String service : new TreeSet<>(recorded)) {
            String component = canonicalComponent(packageName + "/" + service);
            if (component == null || enabled.contains(component)) continue;
            if (updated.length() > 0) updated.append(':');
            updated.append(flattenShort(packageName, service));
            enabled.add(component);
        }
        return updated.toString();
    }

    static boolean containsAllRecordedServices(String list, String packageName, Set<String> recorded) {
        if (recorded.isEmpty()) return false;
        Set<String> enabled = new HashSet<>();
        String normalized = normalizeList(list);
        for (String entry : normalized.split(":")) {
            String component = canonicalComponent(entry);
            if (component != null) enabled.add(component);
        }
        for (String service : recorded) {
            if (!enabled.contains(canonicalComponent(packageName + "/" + service))) return false;
        }
        return true;
    }

    static boolean needsAccessibilityRebind(boolean accessibilityEnabled, boolean allRecordedServicesEnabled) {
        return !accessibilityEnabled && allRecordedServicesEnabled;
    }

    private static void restoreCrashedServices(ContentResolver cr, String current, String updated,
                                                String packageName, Set<String> recorded, Runnable completion) {
        String without = removeRecordedServices(current, packageName, recorded);
        new Thread(() -> {
            boolean restored = false;
            try {
                // Add, remove, add: Android drops its "Crashed services" mark when a helper leaves the list.
                if (!replaceList(cr, current, updated)) {
                    Log.w(TAG, "Accessibility services changed during recovery; leaving the current list untouched");
                    return;
                }
                Thread.sleep(1000);
                if (!replaceList(cr, updated, without)) {
                    Log.w(TAG, "Accessibility services changed during recovery; leaving the current list untouched");
                    return;
                }
                Thread.sleep(1000);
                if (!replaceList(cr, without, updated)) {
                    Log.w(TAG, "Accessibility services changed during recovery; leaving the current list untouched");
                    return;
                }
                if (!Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)) {
                    Log.e(TAG, "Android refused to enable accessibility after restoring helper services");
                    return;
                }
                restored = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Accessibility helper recovery was interrupted", interrupted);
            } catch (SecurityException denied) {
                Log.e(TAG, "Permission denied while restoring accessibility helpers", denied);
            } finally {
                if (restored) completion.run();
            }
        }, "ServiceRepair").start();
    }

    private static String removeRecordedServices(String list, String packageName, Set<String> recorded) {
        Set<String> components = new HashSet<>();
        for (String service : recorded) {
            components.add(canonicalComponent(packageName + "/" + service));
        }
        StringBuilder remaining = new StringBuilder();
        for (String entry : normalizeList(list).split(":")) {
            String component = canonicalComponent(entry);
            if (component == null || components.contains(component)) continue;
            if (remaining.length() > 0) remaining.append(':');
            remaining.append(entry);
        }
        return remaining.toString();
    }

    private static String normalizeList(String list) {
        return list == null || list.equals("null") ? "" : list;
    }

    private static String canonicalComponent(String flattened) {
        int separator = flattened.indexOf('/');
        if (separator <= 0 || separator == flattened.length() - 1) return null;
        String packageName = flattened.substring(0, separator);
        String className = flattened.substring(separator + 1);
        if (className.startsWith(".")) className = packageName + className;
        return packageName + "/" + className;
    }

    private static String flattenShort(String packageName, String service) {
        String prefix = packageName + ".";
        String className = service.startsWith(prefix) ? "." + service.substring(prefix.length()) : service;
        return packageName + "/" + className;
    }

    private static void beginRebind(ContentResolver cr, SharedPreferences repairPrefs, Runnable completion) {
        String saved = repairPrefs.getString(PENDING_SERVICES, null);
        if (saved == null) {
            Log.e(TAG, "Pending accessibility service list is missing; no settings were changed");
            return;
        }
        if (!Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")) {
            Log.e(TAG, "Android refused to clear the accessibility service list for rebind");
            return;
        }
        boolean scheduled = new Handler(Looper.getMainLooper()).postDelayed(
                () -> finishRebind(cr, repairPrefs, saved, completion), REBIND_DELAY_MS);
        if (!scheduled) {
            Log.e(TAG, "Could not schedule accessibility service list restoration");
            finishRebind(cr, repairPrefs, saved, completion);
        }
    }

    private static void finishRebind(ContentResolver cr, SharedPreferences repairPrefs, String saved,
                                     Runnable completion) {
        boolean restored = false;
        try {
            if (!Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, saved)) {
                Log.e(TAG, "Android refused to restore the accessibility service list after rebind");
                return;
            }
            if (!Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)) {
                Log.e(TAG, "Android refused to enable accessibility after rebind");
                return;
            }
            restored = true;
            if (!repairPrefs.edit().remove(PENDING_SERVICES).commit()) {
                Log.e(TAG, "Accessibility was restored, but pending recovery state could not be cleared");
            }
        } catch (SecurityException denied) {
            Log.e(TAG, "Permission denied while completing accessibility rebind", denied);
        } finally {
            if (restored) completion.run();
        }
    }

    /** Writes the enabled list only while it still reads {@code expected}; an absent list counts as empty. */
    private static boolean replaceList(ContentResolver cr, String expected, String updated) {
        String now = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return Objects.equals(normalizeList(now), normalizeList(expected))
                && Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
