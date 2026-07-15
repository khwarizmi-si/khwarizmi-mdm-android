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

package com.hmdm.launcher.util;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.LocationTable;
import com.hmdm.launcher.db.RemoteFileTable;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.AppUsageEvent;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.DeviceInfo;
import com.hmdm.launcher.json.InstalledApp;
import com.hmdm.launcher.json.RemoteFile;
import com.hmdm.launcher.pro.ProUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeviceInfoProvider {
    public static DeviceInfo getDeviceInfo(Context context, boolean queryPermissions, boolean queryApps) {
        DeviceInfo deviceInfo = new DeviceInfo();
        List<Integer> permissions = deviceInfo.getPermissions();
        List<Application> applications = deviceInfo.getApplications();
        List<RemoteFile> files = deviceInfo.getFiles();

        deviceInfo.setModel(Build.MODEL);

        if (queryPermissions) {
            permissions.add(Utils.checkAdminMode(context) ? 1 : 0);
            permissions.add(Utils.canDrawOverlays(context) ? 1 : 0);
            permissions.add(ProUtils.checkUsageStatistics(context) ? 1 : 0);
            permissions.add(!BuildConfig.USE_ACCESSIBILITY || !ProUtils.checkAccessibilityService(context) ? 0 : 1);
        }

        SettingsHelper config = SettingsHelper.getInstance(context);
        if (queryApps) {
            PackageManager packageManager = context.getPackageManager();
            if (config.getConfig() != null) {
                List<Application> requiredApps = SettingsHelper.getInstance(context).getConfig().getApplications();
                for (Application application : requiredApps) {
                    if (application.isRemove()) {
                        continue;
                    }
                    try {
                        PackageInfo packageInfo = packageManager.getPackageInfo(application.getPkg(), 0);

                        Application installedApp = new Application();
                        installedApp.setName(application.getName());
                        installedApp.setPkg(packageInfo.packageName);
                        installedApp.setVersion(packageInfo.versionName);

                        // Verify there's no duplicates (due to different versions in config), otherwise it causes an error on the server
                        boolean appPresents = false;
                        for (Application a : applications) {
                            if (a.getPkg().equalsIgnoreCase(installedApp.getPkg())) {
                                appPresents = true;
                                break;
                            }
                        }
                        if (!appPresents) {
                            applications.add(installedApp);
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        // Application not installed
                    }
                }

                List<RemoteFile> requiredFiles = SettingsHelper.getInstance(context).getConfig().getFiles();
                for (RemoteFile remoteFile : requiredFiles) {
                    if (remoteFile.getPath() == null || remoteFile.getPath().isEmpty()) {
                        // Protection against crash if the file configuration is invalid
                        // (sometimes happens after upgrading web panel to 5.38.1)
                        continue;
                    }
                    File file = new File(Environment.getExternalStorageDirectory(), remoteFile.getPath());
                    if (file.exists()) {
                        RemoteFile remoteFileDb = RemoteFileTable.selectByPath(DatabaseHelper.instance(context).getReadableDatabase(),
                                remoteFile.getPath());
                        if (remoteFileDb != null) {
                            files.add(remoteFileDb);
                        } else {
                            // How could that happen? The database entry should exist for each file
                            // Let's recalculate the checksum to check if the file matches
                            try {
                                RemoteFile copy = new RemoteFile(remoteFile);
                                copy.setChecksum(CryptoUtils.calculateChecksum(new FileInputStream(file)));
                                files.add(copy);
                            } catch (FileNotFoundException e) {
                            }
                        }
                    }
                }
            }

            // Full installed-app inventory: every package on the device, not just the
            // MDM-managed apps collected above. Reported separately in installedApplications
            // so the managed-app compliance logic is unaffected. Requires the
            // QUERY_ALL_PACKAGES permission (declared in the manifest).
            collectInstalledApplications(packageManager, deviceInfo.getInstalledApplications());

            // Lightweight app-usage history (which apps were brought to foreground).
            // Only collected when the usage-access permission has been granted — an
            // explicit, deliberate act — so this is effectively opt-in.
            if (ProUtils.checkUsageStatistics(context)) {
                collectAppUsageEvents(context, packageManager, deviceInfo.getAppUsageEvents());
            }
        }

        deviceInfo.setDeviceId( SettingsHelper.getInstance( context ).getDeviceId() );

        String phone = DeviceInfoProvider.getPhoneNumber(context, 0);
        if (phone == null || phone.equals("")) {
            phone = config.getConfig().getPhone();
        }
        deviceInfo.setPhone(phone);

        String imei = DeviceInfoProvider.getImei(context, 0);
        if (imei == null || imei.equals("")) {
            imei = config.getConfig().getImei();
        }
        deviceInfo.setImei(imei);

        // Battery
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

        Intent batteryStatus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                context.registerReceiver(null, ifilter, Context.RECEIVER_EXPORTED) :
                context.registerReceiver(null, ifilter);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL) {
            int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            switch (chargePlug) {
                case BatteryManager.BATTERY_PLUGGED_USB:
                    deviceInfo.setBatteryCharging(Const.DEVICE_CHARGING_USB);
                    break;
                case BatteryManager.BATTERY_PLUGGED_AC:
                    deviceInfo.setBatteryCharging(Const.DEVICE_CHARGING_AC);
                    break;
            }
        } else {
            deviceInfo.setBatteryCharging("");
        }

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        deviceInfo.setBatteryLevel(level * 100 / scale);

        deviceInfo.setAndroidVersion(Build.VERSION.RELEASE);
        deviceInfo.setLocation(getLocation(context));
        deviceInfo.setMdmMode(Utils.isDeviceOwner(context));
        deviceInfo.setKioskMode(ProUtils.isKioskModeRunning(context));
        deviceInfo.setLauncherType(Utils.getLauncherVariant());
        deviceInfo.setCpu(Build.CPU_ABI);
        deviceInfo.setSerial(getSerialNumber());

        deviceInfo.setImsi(getImsi(context, 0));
        deviceInfo.setIccid(getIccid(context, 0));
        deviceInfo.setImei2(getImei(context, 1));
        deviceInfo.setImsi2(getImsi(context, 1));
        deviceInfo.setPhone2(getPhoneNumber(context, 1));
        deviceInfo.setIccid2(getIccid(context, 1));

        String launcherPackage = Utils.getDefaultLauncher(context);
        deviceInfo.setLauncherPackage(launcherPackage != null ? launcherPackage : "");
        deviceInfo.setDefaultLauncher(context.getPackageName().equals(launcherPackage));

        deviceInfo.setCustom1(config.getUserCustom1());
        deviceInfo.setCustom2(config.getUserCustom2());
        deviceInfo.setCustom3(config.getUserCustom3());

        return deviceInfo;
    }

    /**
     * Collects the full inventory of applications present on the device into the given list.
     * Every package is reported (user-installed and system apps). Failures are logged and
     * swallowed so an inventory problem never breaks the surrounding device-info sync.
     */
    private static void collectInstalledApplications(PackageManager packageManager, List<InstalledApp> installedApplications) {
        try {
            List<PackageInfo> packages = packageManager.getInstalledPackages(0);
            for (PackageInfo packageInfo : packages) {
                if (packageInfo.applicationInfo == null) {
                    continue;
                }
                InstalledApp app = new InstalledApp();
                app.setPkg(packageInfo.packageName);
                CharSequence label = packageInfo.applicationInfo.loadLabel(packageManager);
                app.setName(label != null ? label.toString() : packageInfo.packageName);
                app.setVersion(packageInfo.versionName);
                app.setVersionCode(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? packageInfo.getLongVersionCode()
                        : (long) packageInfo.versionCode);
                app.setSystem((packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                try {
                    app.setInstaller(packageManager.getInstallerPackageName(packageInfo.packageName));
                } catch (Exception e) {
                    // Installer info is not available for every package / OEM
                }
                app.setFirstInstall(packageInfo.firstInstallTime);
                app.setLastUpdate(packageInfo.lastUpdateTime);
                installedApplications.add(app);
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to collect installed app inventory: " + e.getMessage());
        }
    }

    // Look back this far for foreground events, and cap how many are reported per sync.
    private static final long APP_USAGE_WINDOW_MS = 24 * 60 * 60 * 1000L;
    private static final int APP_USAGE_MAX_EVENTS = 200;

    /**
     * Collects the lightweight app-usage history (MOVE_TO_FOREGROUND events within a
     * bounded time window, capped in count) into the given list, newest last. Failures
     * are logged and swallowed so a usage problem never breaks the device-info sync.
     */
    private static void collectAppUsageEvents(Context context, PackageManager packageManager,
                                              List<AppUsageEvent> appUsageEvents) {
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return;
            }
            long end = System.currentTimeMillis();
            long begin = end - APP_USAGE_WINDOW_MS;
            UsageEvents events = usm.queryEvents(begin, end);
            if (events == null) {
                return;
            }
            // Resolve labels once per package to avoid repeated PackageManager lookups.
            Map<String, String> labelCache = new HashMap<>();
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    continue;
                }
                String pkg = event.getPackageName();
                if (pkg == null) {
                    continue;
                }
                String name = labelCache.get(pkg);
                if (name == null) {
                    name = resolveAppLabel(packageManager, pkg);
                    labelCache.put(pkg, name);
                }
                appUsageEvents.add(new AppUsageEvent(pkg, name, event.getTimeStamp()));
            }
            // Keep only the most recent events if the window produced too many.
            if (appUsageEvents.size() > APP_USAGE_MAX_EVENTS) {
                appUsageEvents.subList(0, appUsageEvents.size() - APP_USAGE_MAX_EVENTS).clear();
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to collect app usage events: " + e.getMessage());
        }
    }

    private static String resolveAppLabel(PackageManager packageManager, String pkg) {
        try {
            ApplicationInfo ai = packageManager.getApplicationInfo(pkg, 0);
            CharSequence label = packageManager.getApplicationLabel(ai);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }

    @SuppressWarnings({"MissingPermission"})
    public static DeviceInfo.Location getLocation(Context context) {
        try {
            LocationManager locationManager = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null || !hasLocationPermission(context)) {
                return getCachedLocation(context);
            }

            Location lastLocation = newerLocation(
                    getLastKnownLocation(locationManager, LocationManager.GPS_PROVIDER),
                    getLastKnownLocation(locationManager, LocationManager.NETWORK_PROVIDER));

            if (lastLocation != null) {
                return toDeviceLocation(lastLocation);
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to get last known location: " + e.getMessage());
        }
        return getCachedLocation(context);
    }

    private static boolean hasLocationPermission(Context context) {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings({"MissingPermission"})
    private static Location getLastKnownLocation(LocationManager locationManager, String provider) {
        try {
            if (locationManager.isProviderEnabled(provider)) {
                Location location = locationManager.getLastKnownLocation(provider);
                return isValidLocation(location) ? location : null;
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to get " + provider + " location: " + e.getMessage());
        }
        return null;
    }

    private static Location newerLocation(Location first, Location second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.getTime() >= second.getTime() ? first : second;
    }

    private static boolean isValidLocation(Location location) {
        return location != null && (location.getLatitude() != 0 || location.getLongitude() != 0);
    }

    private static DeviceInfo.Location toDeviceLocation(Location lastLocation) {
        DeviceInfo.Location location = new DeviceInfo.Location();
        location.setLat(lastLocation.getLatitude());
        location.setLon(lastLocation.getLongitude());
        location.setTs(lastLocation.getTime());
        return location;
    }

    private static DeviceInfo.Location getCachedLocation(Context context) {
        try {
            LocationTable.Location latest = LocationTable.selectLatest(
                    DatabaseHelper.instance(context).getReadableDatabase());
            if (latest == null || (latest.getLat() == 0 && latest.getLon() == 0)) {
                return null;
            }
            DeviceInfo.Location location = new DeviceInfo.Location();
            location.setLat(latest.getLat());
            location.setLon(latest.getLon());
            location.setTs(latest.getTs());
            return location;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to get cached location: " + e.getMessage());
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    public static String getSerialNumber() {
        String serialNumber = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                String s = Build.getSerial();
                Log.d(Const.LOG_TAG, "Serial number: " + s);
                return s;
            } catch (SecurityException e) {
                Log.w(Const.LOG_TAG, "Failed to get serial number from Build.getSerial()");
                e.printStackTrace();
            }
        }
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class);
            serialNumber = (String) get.invoke(c, "ril.serialnumber");
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to get serial number from ril.serialnumber");
            e.printStackTrace();
        }
        if (serialNumber != null && !serialNumber.equals("")) {
            return serialNumber;
        }
        Log.d(Const.LOG_TAG, "Build.SERIAL=" + Build.SERIAL);
        return Build.SERIAL;
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getPhoneNumber(Context context) {
        try {
            TelephonyManager tMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tMgr == null) {
                return null;
            }
            return tMgr.getLine1Number();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getIccid(Context context) {
        try {
            TelephonyManager tMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tMgr == null) {
                return null;
            }
            return tMgr.getSimSerialNumber();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getImsi(Context context) {
        try {
            TelephonyManager tMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tMgr == null) {
                return null;
            }
            return tMgr.getSubscriberId();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getImsi(Context context, int slot) {
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager == null) {
                return null;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                return slot == 0 ? getImsi(context) : null;
            }
            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptionList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptionList == null || slot >= subscriptionList.size()) {
                return null;
            }
            TelephonyManager subscriptionTelephonyManager = telephonyManager.createForSubscriptionId(
                    subscriptionList.get(slot).getSubscriptionId());
            return subscriptionTelephonyManager.getSubscriberId();
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to get IMSI for slot " + slot + ": " + e.getMessage());
        }
        return null;
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getPhoneNumber(Context context, int slot) {
        try {
            Utils.autoGrantPhonePermission(context);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (slot == 0) {
                    return getPhoneNumber(context);
                }
                return null;
            }
            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptionList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptionList == null || slot >= subscriptionList.size()) {
                // No mobile info at all
                return null;
            }
            return subscriptionList.get(slot).getNumber();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getIccid(Context context, int slot) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (slot == 0) {
                    return getPhoneNumber(context);
                }
                return null;
            }
            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            List<SubscriptionInfo> subscriptionList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptionList == null || slot >= subscriptionList.size()) {
                // No mobile info at all
                return null;
            }
            return subscriptionList.get(slot).getIccId();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getImei(Context context) {
        try {
            TelephonyManager tMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tMgr == null) {
                return null;
            }
            return tMgr.getDeviceId();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint( { "MissingPermission" } )
    public static String getImei(Context context, int slot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (slot == 0) {
                return getImei(context);
            }
            return null;
        }
        try {
            TelephonyManager tMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tMgr == null) {
                return null;
            }
            return tMgr.getDeviceId(slot);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the STB MacAddress
     */
    public static String getMacAddress() {
        try {
            return Utils.loadFileAsString("/sys/class/net/eth0/address")
                    .toUpperCase().substring(0, 17);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
