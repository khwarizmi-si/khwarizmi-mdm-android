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

package com.hmdm.launcher.pro.worker;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.InfoHistoryTable;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.DetailedInfo;
import com.hmdm.launcher.json.DetailedInfoConfig;
import com.hmdm.launcher.json.DetailedInfoConfigResponse;
import com.hmdm.launcher.json.DeviceInfo;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;
import com.hmdm.launcher.util.DeviceInfoProvider;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * Collects and uploads device parameter history used by the deviceinfo plugin.
 */
public class DetailedInfoWorker extends Worker {
    private static final String WORK_TAG_DETAILED_INFO = "com.hmdm.launcher.WORK_TAG_DETAILED_INFO";
    private static final String WORK_TAG_DETAILED_INFO_CONFIG = "com.hmdm.launcher.WORK_TAG_DETAILED_INFO_CONFIG";
    private static final String INPUT_REFRESH_CONFIG = "refreshConfig";
    private static final int MIN_INTERVAL_MINS = 15;
    private static final int DEFAULT_INTERVAL_MINS = 15;
    private static final int MAX_UPLOADED_ITEMS = 20;

    private final Context context;
    private final SettingsHelper settingsHelper;

    public DetailedInfoWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.context = context;
        this.settingsHelper = SettingsHelper.getInstance(context);
    }

    public static void schedule(Context context) {
        requestConfigUpdate(context);
    }

    public static void requestConfigUpdate(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DetailedInfoWorker.class)
                .addTag(Const.WORK_TAG_COMMON)
                .setInputData(new Data.Builder().putBoolean(INPUT_REFRESH_CONFIG, true).build())
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(WORK_TAG_DETAILED_INFO_CONFIG, ExistingWorkPolicy.KEEP, request);
    }

    private static void schedulePeriodic(Context context, int intervalMins) {
        int safeInterval = Math.max(intervalMins, MIN_INTERVAL_MINS);
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(DetailedInfoWorker.class, safeInterval, TimeUnit.MINUTES)
                .addTag(Const.WORK_TAG_COMMON)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(WORK_TAG_DETAILED_INFO, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    private static void cancelPeriodic(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(WORK_TAG_DETAILED_INFO);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (settingsHelper == null || settingsHelper.getDeviceId().isEmpty()) {
            return Result.success();
        }

        DetailedInfoConfig config = loadConfig();
        if (config == null) {
            return Result.retry();
        }
        if (!Boolean.TRUE.equals(config.getSendData())) {
            cancelPeriodic(context);
            return Result.success();
        }

        if (getInputData().getBoolean(INPUT_REFRESH_CONFIG, false)) {
            schedulePeriodic(context, config.getIntervalMins() != null ? config.getIntervalMins() : DEFAULT_INTERVAL_MINS);
        }

        DatabaseHelper dbHelper = DatabaseHelper.instance(context);
        InfoHistoryTable.deleteOldItems(dbHelper.getWritableDatabase());
        InfoHistoryTable.insert(dbHelper.getWritableDatabase(), collectDetailedInfo());

        List<DetailedInfo> unsentItems = InfoHistoryTable.select(dbHelper.getReadableDatabase(), MAX_UPLOADED_ITEMS);
        if (unsentItems.isEmpty()) {
            return Result.success();
        }
        if (!upload(unsentItems)) {
            return Result.retry();
        }
        InfoHistoryTable.delete(dbHelper.getWritableDatabase(), unsentItems);
        return Result.success();
    }

    private DetailedInfoConfig loadConfig() {
        ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
        ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);
        Response<DetailedInfoConfigResponse> response = null;

        try {
            response = serverService.getDetailedInfoConfig(settingsHelper.getServerProject(), settingsHelper.getDeviceId()).execute();
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to load detailed info config from primary server: " + e.getMessage());
        }

        try {
            if (response == null || !response.isSuccessful()) {
                response = secondaryServerService.getDetailedInfoConfig(settingsHelper.getServerProject(), settingsHelper.getDeviceId()).execute();
            }
            if (response != null && response.isSuccessful() && response.body() != null) {
                return response.body().getData();
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to load detailed info config: " + e.getMessage());
        }

        return null;
    }

    private boolean upload(List<DetailedInfo> infoItems) {
        ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
        ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);
        Response<ResponseBody> response = null;

        try {
            response = serverService.sendDetailedInfo(settingsHelper.getServerProject(), settingsHelper.getDeviceId(), infoItems).execute();
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to upload detailed info to primary server: " + e.getMessage());
        }

        try {
            if (response == null || !response.isSuccessful()) {
                response = secondaryServerService.sendDetailedInfo(settingsHelper.getServerProject(), settingsHelper.getDeviceId(), infoItems).execute();
            }
            return response != null && response.isSuccessful();
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to upload detailed info: " + e.getMessage());
        }
        return false;
    }

    private DetailedInfo collectDetailedInfo() {
        DetailedInfo info = new DetailedInfo();
        info.setTs(System.currentTimeMillis());
        info.setDevice(collectDeviceInfo());
        info.setWifi(collectWifiInfo());
        info.setGps(collectGpsInfo());
        info.setMobile(collectMobileInfo(0));
        info.setMobile2(collectMobileInfo(1));
        return info;
    }

    private DetailedInfo.Device collectDeviceInfo() {
        DetailedInfo.Device device = new DetailedInfo.Device();
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? context.registerReceiver(null, ifilter, Context.RECEIVER_EXPORTED)
                : context.registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                device.setBatteryLevel(level * 100 / scale);
            }
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) {
                int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                if (chargePlug == BatteryManager.BATTERY_PLUGGED_USB) {
                    device.setBatteryCharging(Const.DEVICE_CHARGING_USB);
                } else if (chargePlug == BatteryManager.BATTERY_PLUGGED_AC) {
                    device.setBatteryCharging(Const.DEVICE_CHARGING_AC);
                }
            } else {
                device.setBatteryCharging("");
            }
        }

        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = connectivityManager != null ? connectivityManager.getActiveNetworkInfo() : null;
        device.setWifi(activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_WIFI && activeNetwork.isConnected());
        device.setMobileData(activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE && activeNetwork.isConnected());

        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        try {
            device.setGps(locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER));
        } catch (Exception e) {
            device.setGps(null);
        }

        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        device.setKeyguard(keyguardManager != null && keyguardManager.isKeyguardLocked());
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            device.setRingVolume(audioManager.getStreamVolume(AudioManager.STREAM_RING));
        }
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            try {
                device.setBluetooth(bluetoothAdapter.isEnabled());
            } catch (SecurityException e) {
                device.setBluetooth(null);
            }
        }

        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            device.setMemoryTotal((int) (memoryInfo.totalMem / 1024 / 1024));
            device.setMemoryAvailable((int) (memoryInfo.availMem / 1024 / 1024));
        }
        device.setIp(settingsHelper.getExternalIp());
        return device;
    }

    @SuppressLint("MissingPermission")
    private DetailedInfo.Wifi collectWifiInfo() {
        DetailedInfo.Wifi wifi = new DetailedInfo.Wifi();
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo connectionInfo = wifiManager != null ? wifiManager.getConnectionInfo() : null;
        if (connectionInfo != null) {
            wifi.setRssi(connectionInfo.getRssi());
            wifi.setSsid(cleanSsid(connectionInfo.getSSID()));
            wifi.setIp(formatIp(connectionInfo.getIpAddress()));
            wifi.setState(connectionInfo.getNetworkId() >= 0 ? Const.WIFI_STATE_CONNECTED : Const.WIFI_STATE_DISCONNECTED);
        } else {
            wifi.setState(Const.WIFI_STATE_INACTIVE);
        }

        long totalTx = TrafficStats.getTotalTxBytes();
        long totalRx = TrafficStats.getTotalRxBytes();
        long mobileTx = TrafficStats.getMobileTxBytes();
        long mobileRx = TrafficStats.getMobileRxBytes();
        wifi.setTx(diffOrNull(totalTx, mobileTx));
        wifi.setRx(diffOrNull(totalRx, mobileRx));
        return wifi;
    }

    private DetailedInfo.Gps collectGpsInfo() {
        DetailedInfo.Gps gps = new DetailedInfo.Gps();
        DeviceInfo.Location location = DeviceInfoProvider.getLocation(context);
        if (location != null) {
            gps.setLat(location.getLat());
            gps.setLon(location.getLon());
            gps.setState(Const.GPS_STATE_ACTIVE);
        } else {
            gps.setState(Const.GPS_STATE_INACTIVE);
        }
        return gps;
    }

    private DetailedInfo.Mobile collectMobileInfo(int slot) {
        DetailedInfo.Mobile mobile = new DetailedInfo.Mobile();
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager != null && slot == 0) {
            mobile.setCarrier(telephonyManager.getNetworkOperatorName());
        }
        mobile.setNumber(DeviceInfoProvider.getPhoneNumber(context, slot));
        mobile.setImsi(DeviceInfoProvider.getImsi(context, slot));
        mobile.setTx(unavailableToNull(TrafficStats.getMobileTxBytes()));
        mobile.setRx(unavailableToNull(TrafficStats.getMobileRxBytes()));

        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = connectivityManager != null ? connectivityManager.getActiveNetworkInfo() : null;
        boolean connected = activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE && activeNetwork.isConnected();
        mobile.setData(connected);
        mobile.setState(connected ? Const.MOBILE_STATE_CONNECTED : Const.MOBILE_STATE_DISCONNECTED);
        return mobile;
    }

    private String cleanSsid(String ssid) {
        if (ssid == null || "<unknown ssid>".equals(ssid)) {
            return null;
        }
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private String formatIp(int ipAddress) {
        if (ipAddress == 0) {
            return null;
        }
        return (ipAddress & 0xff) + "." +
                ((ipAddress >> 8) & 0xff) + "." +
                ((ipAddress >> 16) & 0xff) + "." +
                ((ipAddress >> 24) & 0xff);
    }

    private Long diffOrNull(long total, long mobile) {
        if (total == TrafficStats.UNSUPPORTED || mobile == TrafficStats.UNSUPPORTED) {
            return null;
        }
        return Math.max(0, total - mobile);
    }

    private Long unavailableToNull(long value) {
        return value == TrafficStats.UNSUPPORTED ? null : value;
    }

}
