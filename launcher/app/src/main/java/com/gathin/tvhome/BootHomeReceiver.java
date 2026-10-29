package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Restores an opted-in HomeKeyService after boot; that service opens the launcher after it binds. */
public final class BootHomeReceiver extends BroadcastReceiver {
    static final String ACTION_SERVICE_READY = "com.gathin.tvhome.action.BOOT_HOME_SERVICE_READY";
    private static final String TAG = "TvHomeBootHome";
    private static final String PREFS = "boot_home";
    private static final String PENDING_HOME = "pending_home";
    private static final long RECEIVER_TIMEOUT_MS = 8000;

    static boolean enabledFor(Config config) {
        return enabledFor(config.restoreKeyServices, config.launchHomeAfterBoot, config.stockHomeRedirect);
    }

    static boolean enabledFor(boolean restoreKeyServices, boolean launchHomeAfterBoot, String stockHomeRedirect) {
        return restoreKeyServices && launchHomeAfterBoot && stockHomeRedirect != null && !stockHomeRedirect.isEmpty();
    }

    static void configure(Context context, boolean enabled) {
        ComponentName component = new ComponentName(context, BootHomeReceiver.class);
        PackageManager pm = context.getPackageManager();
        int state = pm.getComponentEnabledSetting(component);
        if (enabled && state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } else if (!enabled && state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            clearPending(context);
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }

    static boolean isPending(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PENDING_HOME, false);
    }

    static boolean clearPending(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(PENDING_HOME).commit();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Context appContext = context.getApplicationContext();
        Config config = Config.load(appContext);
        if (!enabledFor(config)) return;
        if (!ServiceRepair.hasRecordedHomeService(appContext)) {
            Log.w(TAG, "No previously enabled HomeKeyService with WRITE_SECURE_SETTINGS; boot Home was not started");
            return;
        }
        if (!appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PENDING_HOME, true).commit()) {
            Log.e(TAG, "Could not persist pending Home launch");
            return;
        }

        PendingResult pending = goAsync();
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<Runnable> timeoutCallback = new AtomicReference<>();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                Runnable timeout = timeoutCallback.get();
                if (timeout != null) handler.removeCallbacks(timeout);
                pending.finish();
            }
        };
        Runnable timeout = () -> {
            Log.e(TAG, "Timed out while restoring accessibility services after boot");
            finish.run();
        };
        timeoutCallback.set(timeout);
        handler.postDelayed(timeout, RECEIVER_TIMEOUT_MS);
        ServiceRepair.restore(appContext, () -> {
            appContext.sendBroadcast(new Intent(ACTION_SERVICE_READY).setPackage(appContext.getPackageName()));
            finish.run();
        });
    }
}
