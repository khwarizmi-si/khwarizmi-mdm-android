/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.pro;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.location.Location;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.LocationTable;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.pro.service.CheckForegroundAppAccessibilityService;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.Utils;
import com.hmdm.launcher.worker.SendDeviceInfoWorker;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Arrays;
import java.util.List;

/**
 * Kiosk (COSU / lock task) implementation and other advanced features.
 *
 * <p>The kiosk-related methods below implement single-app COSU mode using the
 * standard Android {@link DevicePolicyManager} lock task APIs. They require the
 * app to be a Device Owner. Non-kiosk advanced features (foreground app
 * monitoring, status bar prevention, crashlytics) remain stubs until enabled.</p>
 */
public class ProUtils {
    private static final long LOCATION_DEVICE_INFO_UPLOAD_INTERVAL_MS = 5 * 60 * 1000L;
    private static long lastLocationDeviceInfoUploadRequestTs = 0;

    // A settings package added to the lock task allowlist when settings access is granted
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final List<String> SYSTEM_SUPPORT_PACKAGES = Arrays.asList(
            "android",
            "com.android.systemui",
            "com.google.android.gsf",
            "com.google.android.gms",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.providers.downloads",
            "com.android.providers.media",
            "com.google.android.providers.media.module",
            "com.android.documentsui",
            "com.android.externalstorage",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.android.captiveportallogin",
            "com.google.android.captiveportallogin",
            "com.android.settings.intelligence",
            "com.google.android.modulemetadata",
            "com.google.android.webview"
    );

    public static boolean isPro() {
        // Advanced features are implemented in this edition:
        // - Kiosk/COSU (gated by kioskModeRequired)
        // - Foreground app monitoring (overlay + usage stats)
        return true;
    }

    public static boolean kioskModeRequired(Context context) {
        ServerConfig config = SettingsHelper.getInstance(context.getApplicationContext()).getConfig();
        return config != null && config.isKioskMode();
    }

    public static void initCrashlytics(Context context) {
        // Stub
    }

    public static void sendExceptionToCrashlytics(Throwable e) {
        // Stub
    }

    // Returns true if our accessibility service is currently enabled by the user
    public static boolean checkAccessibilityService(Context context) {
        try {
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabledServices)) {
                return false;
            }
            String target = context.getPackageName() + "/"
                    + CheckForegroundAppAccessibilityService.class.getName();
            String targetShort = context.getPackageName() + "/."
                    + CheckForegroundAppAccessibilityService.class.getSimpleName();
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabledServices);
            while (splitter.hasNext()) {
                String component = splitter.next();
                if (component.equalsIgnoreCase(target) || component.equalsIgnoreCase(targetShort)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // Returns true if the PACKAGE_USAGE_STATS access is granted to this app
    public static boolean checkUsageStatistics(Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
            } else {
                mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Decides whether a foreground application is allowed for the user.
     * Used by the foreground-app monitoring services to block unwanted apps.
     */
    public static boolean isForegroundAppAllowed(Context context, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) {
            // Unknown foreground app: do not block to avoid false positives
            return true;
        }
        ServerConfig config = SettingsHelper.getInstance(context.getApplicationContext()).getConfig();
        if (config == null) {
            return true;
        }
        // Persistent permissive / kiosk modes do not use foreground blocking
        if (config.isPermissive() || config.isKioskMode()) {
            return true;
        }
        // The launcher itself is always allowed
        if (pkg.equals(context.getPackageName())) {
            return true;
        }
        // Essential system packages that must never be blocked
        if (isSystemSupportPackage(pkg)) {
            return true;
        }
        // The active input method (on-screen keyboard) must stay usable
        if (isInputMethodPackage(context, pkg)) {
            return true;
        }
        // Applications explicitly allowed by the configuration
        List<Application> apps = config.getApplications();
        if (apps != null) {
            for (Application a : apps) {
                if (a != null && pkg.equals(a.getPkg())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isSystemSupportPackage(String pkg) {
        return pkg != null && SYSTEM_SUPPORT_PACKAGES.contains(pkg);
    }

    private static boolean isInputMethodPackage(Context context, String pkg) {
        try {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                return false;
            }
            for (InputMethodInfo imi : imm.getEnabledInputMethodList()) {
                if (imi.getPackageName().equals(pkg)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Ignore and treat as non-IME
        }
        return false;
    }

    // Add a transparent overlay on top of the status bar which prevents the user
    // from pulling down the notification shade (touches over the band are swallowed).
    @SuppressLint("ClickableViewAccessibility")
    public static View preventStatusBarExpansion(Activity activity) {
        if (!Utils.canDrawOverlays(activity)) {
            return null;
        }
        try {
            WindowManager manager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            int statusBarHeight = getStatusBarHeight(activity);
            // Cover a band a bit taller than the status bar to reliably intercept the pull-down
            int overlayHeight = statusBarHeight > 0 ? statusBarHeight * 2 : dpToPx(activity, 48);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.type = Utils.OverlayWindowType();
            params.gravity = Gravity.TOP;
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = overlayHeight;
            params.format = PixelFormat.TRANSPARENT;

            View blockView = new View(activity);
            // Swallow all touches over the status bar area
            blockView.setOnTouchListener((v, event) -> true);

            manager.addView(blockView, params);
            return blockView;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "preventStatusBarExpansion failed: " + e.getMessage());
            return null;
        }
    }

    // Add a thin transparent overlay at the right edge that blocks the swipe which
    // opens the applications list / edge panel on some devices (e.g. Samsung).
    @SuppressLint("ClickableViewAccessibility")
    public static View preventApplicationsList(Activity activity) {
        if (!Utils.canDrawOverlays(activity)) {
            return null;
        }
        try {
            WindowManager manager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.type = Utils.OverlayWindowType();
            params.gravity = Gravity.TOP | Gravity.RIGHT;
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            params.width = dpToPx(activity, 8);
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            params.format = PixelFormat.TRANSPARENT;

            View blockView = new View(activity);
            blockView.setOnTouchListener((v, event) -> true);

            manager.addView(blockView, params);
            return blockView;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "preventApplicationsList failed: " + e.getMessage());
            return null;
        }
    }

    // A small invisible hotspot in the top-left corner used to exit the kiosk mode
    // after a number of taps (the caller attaches the click listener).
    public static View createKioskUnlockButton(Activity activity) {
        if (!Utils.canDrawOverlays(activity)) {
            return null;
        }
        try {
            WindowManager manager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            int size = dpToPx(activity, 48);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.type = Utils.OverlayWindowType();
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            params.width = size;
            params.height = size;
            params.format = PixelFormat.TRANSPARENT;

            View button = new View(activity);
            manager.addView(button, params);
            return button;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "createKioskUnlockButton failed: " + e.getMessage());
            return null;
        }
    }

    private static int getStatusBarHeight(Context context) {
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId > 0 ? context.getResources().getDimensionPixelSize(resourceId) : 0;
    }

    private static int dpToPx(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    public static boolean isKioskAppInstalled(Context context) {
        ServerConfig config = SettingsHelper.getInstance(context.getApplicationContext()).getConfig();
        if (config == null) {
            return false;
        }
        String kioskApp = config.getMainApp();
        if (kioskApp == null || kioskApp.trim().isEmpty()) {
            return false;
        }
        // The launcher itself can always act as the kiosk app
        if (kioskApp.equals(context.getPackageName())) {
            return true;
        }
        try {
            context.getPackageManager().getPackageInfo(kioskApp, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean isKioskModeRunning(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
            }
            return am.isInLockTaskMode();
        } catch (Exception e) {
            return false;
        }
    }

    public static Intent getKioskAppIntent(String kioskApp, Activity activity) {
        if (kioskApp == null) {
            return null;
        }
        if (kioskApp.equals(activity.getPackageName())) {
            // The launcher itself is the kiosk app
            return activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        }
        return activity.getPackageManager().getLaunchIntentForPackage(kioskApp);
    }

    // Start COSU kiosk mode: whitelist the allowed apps, apply lock task features,
    // enter lock task mode and (unless the launcher is itself the kiosk app) launch it.
    public static boolean startCosuKioskMode(String kioskApp, Activity activity, boolean enableSettings) {
        if (kioskApp == null || kioskApp.trim().isEmpty()) {
            Log.e(Const.LOG_TAG, "startCosuKioskMode: kiosk app is not set");
            return false;
        }
        if (!Utils.isDeviceOwner(activity)) {
            Log.e(Const.LOG_TAG, "startCosuKioskMode: not a device owner, cannot start lock task");
            return false;
        }
        try {
            // Whitelist the packages allowed to run in lock task mode
            applyLockTaskPackages(kioskApp, activity, enableSettings);
            // Apply the lock task system-UI features (home, recents, notifications, ...)
            updateKioskOptions(activity);

            if (kioskApp.equals(activity.getPackageName())) {
                // The launcher is the kiosk app: just pin the current task
                activity.startLockTask();
                return true;
            }

            Intent launchIntent = activity.getPackageManager().getLaunchIntentForPackage(kioskApp);
            if (launchIntent == null) {
                Log.e(Const.LOG_TAG, "startCosuKioskMode: no launch intent for " + kioskApp);
                return false;
            }
            // Enter lock task from the launcher, then launch the whitelisted kiosk app.
            // Because the target package is whitelisted, lock task stays active on it.
            activity.startLockTask();
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(launchIntent);
            return true;
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "startCosuKioskMode failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Set/update kiosk mode options (lock task features) from the current configuration
    public static void updateKioskOptions(Activity activity) {
        // setLockTaskFeatures is available since Android P (API 28)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !Utils.isDeviceOwner(activity)) {
            return;
        }
        ServerConfig config = SettingsHelper.getInstance(activity.getApplicationContext()).getConfig();
        if (config == null) {
            return;
        }
        DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = LegacyUtils.getAdminComponentName(activity);

        int flags = DevicePolicyManager.LOCK_TASK_FEATURE_NONE;
        if (Boolean.TRUE.equals(config.getKioskHome())) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
        }
        if (Boolean.TRUE.equals(config.getKioskRecents())) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        }
        if (Boolean.TRUE.equals(config.getKioskNotifications())) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS;
        }
        if (Boolean.TRUE.equals(config.getKioskSystemInfo())) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO;
        }
        if (Boolean.TRUE.equals(config.getKioskKeyguard())) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD;
        }
        // Global actions (power long-press menu) are kept enabled unless the buttons
        // are explicitly locked, so the device can still be powered off in kiosk mode.
        if (!Boolean.TRUE.equals(config.getKioskLockButtons())) {
            flags |= DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
        }
        try {
            dpm.setLockTaskFeatures(admin, flags);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "updateKioskOptions: failed to set lock task features: " + e.getMessage());
        }
    }

    // Update the list of apps allowed to run in the kiosk mode
    public static void updateKioskAllowedApps(String kioskApp, Activity activity, boolean enableSettings) {
        if (!Utils.isDeviceOwner(activity)) {
            return;
        }
        applyLockTaskPackages(kioskApp, activity, enableSettings);
    }

    // Build and apply the lock task allowlist: the launcher, the kiosk app and,
    // optionally, the settings app (for temporary settings access).
    private static void applyLockTaskPackages(String kioskApp, Activity activity, boolean enableSettings) {
        DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = LegacyUtils.getAdminComponentName(activity);

        List<String> packages = new ArrayList<>();
        packages.add(activity.getPackageName());
        if (kioskApp != null && !kioskApp.trim().isEmpty() && !packages.contains(kioskApp)) {
            packages.add(kioskApp);
        }
        for (String systemPackage : SYSTEM_SUPPORT_PACKAGES) {
            if (!packages.contains(systemPackage)) {
                packages.add(systemPackage);
            }
        }
        if (enableSettings) {
            packages.add(SETTINGS_PACKAGE);
        }
        try {
            dpm.setLockTaskPackages(admin, packages.toArray(new String[0]));
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "applyLockTaskPackages failed: " + e.getMessage());
        }
    }

    public static void unlockKiosk(Activity activity) {
        try {
            if (isKioskModeRunning(activity)) {
                activity.stopLockTask();
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "unlockKiosk failed: " + e.getMessage());
        }
    }

    public static void processConfig(Context context, ServerConfig config) {
        // Stub
    }

    public static void processLocation(Context context, Location location, String provider) {
        if (location == null || (location.getLatitude() == 0 && location.getLongitude() == 0)) {
            return;
        }
        try {
            DatabaseHelper dbHelper = DatabaseHelper.instance(context);
            LocationTable.insert(dbHelper.getWritableDatabase(), new LocationTable.Location(location));
            LocationTable.deleteOldItems(dbHelper.getWritableDatabase());
            RemoteLogger.log(context, Const.LOG_VERBOSE, "Stored " + provider + " location: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            requestDeviceInfoUpload(context);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to store " + provider + " location: " + e.getMessage());
        }
    }

    private static void requestDeviceInfoUpload(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastLocationDeviceInfoUploadRequestTs < LOCATION_DEVICE_INFO_UPLOAD_INTERVAL_MS) {
            return;
        }
        lastLocationDeviceInfoUploadRequestTs = now;
        SendDeviceInfoWorker.requestDeviceInfoSending(context);
    }

    public static String getAppName(Context context) {
        return context.getString(R.string.app_name);
    }

    public static String getCopyright(Context context) {
        return "(c) " + Calendar.getInstance().get(Calendar.YEAR) + " " + context.getString(R.string.vendor);
    }
}
