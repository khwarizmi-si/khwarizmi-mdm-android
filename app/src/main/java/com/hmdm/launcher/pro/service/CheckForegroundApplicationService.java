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

package com.hmdm.launcher.pro.service;

import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.ui.MainActivity;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the current foreground application by polling {@link UsageStatsManager}
 * and blocks apps that are not allowed by the configuration (the "kid's shell").
 *
 * <p>When a disallowed app comes to the foreground the service broadcasts
 * {@link Const#ACTION_HIDE_SCREEN} (handled by {@link MainActivity} which shows a
 * blocking overlay) and brings the launcher back to the front. Requires the
 * PACKAGE_USAGE_STATS permission to be granted.</p>
 */
public class CheckForegroundApplicationService extends Service {

    // How often the foreground app is polled
    private static final long CHECK_INTERVAL_MS = 1000;
    // Look-back window for usage events (must be larger than the interval)
    private static final long EVENTS_WINDOW_MS = 10000;

    private ScheduledThreadPoolExecutor threadPoolExecutor = new ScheduledThreadPoolExecutor(1);
    // Paused while permissive / kiosk mode is temporarily enabled
    private volatile boolean paused = false;
    private String lastBlockedPackage = null;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == null) {
                return;
            }
            switch (intent.getAction()) {
                case Const.ACTION_SERVICE_STOP:
                    stopSelf();
                    break;
                case Const.ACTION_TOGGLE_PERMISSIVE:
                    paused = intent.getBooleanExtra(Const.EXTRA_ENABLED, false);
                    Log.i(Const.LOG_TAG, "CheckForegroundApplicationService: paused=" + paused);
                    break;
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(Const.LOG_TAG, "CheckForegroundApplicationService: service started");

        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        IntentFilter intentFilter = new IntentFilter(Const.ACTION_SERVICE_STOP);
        intentFilter.addAction(Const.ACTION_TOGGLE_PERMISSIVE);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, intentFilter);

        threadPoolExecutor.shutdownNow();
        threadPoolExecutor = new ScheduledThreadPoolExecutor(1);
        threadPoolExecutor.scheduleWithFixedDelay(this::checkForegroundApp,
                CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        return Service.START_STICKY;
    }

    private void checkForegroundApp() {
        try {
            if (paused) {
                return;
            }
            String foregroundPackage = getForegroundPackage();
            if (foregroundPackage == null) {
                return;
            }
            if (ProUtils.isForegroundAppAllowed(this, foregroundPackage)) {
                // Reset so re-opening the same disallowed app is blocked again
                if (foregroundPackage.equals(getPackageName())) {
                    lastBlockedPackage = null;
                }
                return;
            }

            Log.i(Const.LOG_TAG, "CheckForegroundApplicationService: blocking " + foregroundPackage);
            lastBlockedPackage = foregroundPackage;

            // Notify the launcher to show the "application not allowed" overlay
            Intent hideIntent = new Intent(Const.ACTION_HIDE_SCREEN);
            hideIntent.putExtra(Const.PACKAGE_NAME, foregroundPackage);
            LocalBroadcastManager.getInstance(this).sendBroadcast(hideIntent);

            // Bring the launcher back to the front to kick the user out of the app
            Intent launcherIntent = new Intent(this, MainActivity.class);
            launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(launcherIntent);
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "CheckForegroundApplicationService: check failed: " + e.getMessage());
        }
    }

    private String getForegroundPackage() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return null;
        }
        long end = System.currentTimeMillis();
        long begin = end - EVENTS_WINDOW_MS;
        UsageEvents events = usm.queryEvents(begin, end);
        if (events == null) {
            return null;
        }
        UsageEvents.Event event = new UsageEvents.Event();
        String lastForeground = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForeground = event.getPackageName();
            }
        }
        return lastForeground;
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        threadPoolExecutor.shutdownNow();
        Log.i(Const.LOG_TAG, "CheckForegroundApplicationService: service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
