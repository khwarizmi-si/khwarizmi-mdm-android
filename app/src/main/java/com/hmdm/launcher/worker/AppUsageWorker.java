/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.hmdm.launcher.worker;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.db.AppUsageTable;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.helper.CryptoHelper;
import com.hmdm.launcher.json.AppUsageEvent;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class AppUsageWorker extends Worker {
    private static final int MAX_UPLOADED_EVENTS = 50;
    private static final int RETRY_DELAY_MINS = 15;
    private static final String WORK_TAG_APP_USAGE = "com.hmdm.launcher.WORK_TAG_APP_USAGE";

    private static boolean uploadScheduled = false;

    private final Context context;
    private final SettingsHelper settingsHelper;

    public AppUsageWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.context = context;
        this.settingsHelper = SettingsHelper.getInstance(context);
    }

    public static void resetState() {
        uploadScheduled = false;
    }

    public static void scheduleUpload(Context context) {
        scheduleUpload(context, 0);
    }

    public static void scheduleUpload(Context context, int delayMins) {
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(AppUsageWorker.class)
                .addTag(Const.WORK_TAG_COMMON);
        if (delayMins > 0) {
            builder.setInitialDelay(delayMins, TimeUnit.MINUTES);
        }
        OneTimeWorkRequest request = builder.build();
        if (!uploadScheduled) {
            uploadScheduled = true;
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(WORK_TAG_APP_USAGE, ExistingWorkPolicy.REPLACE, request);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        if (settingsHelper == null || settingsHelper.getDeviceId().isEmpty()) {
            uploadScheduled = false;
            return Result.success();
        }

        try {
            DatabaseHelper dbHelper = DatabaseHelper.instance(context);
            AppUsageTable.deleteOldItems(dbHelper.getWritableDatabase());

            while (true) {
                List<AppUsageEvent> unsentItems = AppUsageTable.select(dbHelper.getReadableDatabase(), MAX_UPLOADED_EVENTS);
                if (unsentItems.isEmpty()) {
                    uploadScheduled = false;
                    return Result.success();
                }
                if (!upload(unsentItems)) {
                    uploadScheduled = false;
                    scheduleUpload(context, RETRY_DELAY_MINS);
                    return Result.failure();
                }
                AppUsageTable.delete(dbHelper.getWritableDatabase(), unsentItems);
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to upload app usage: " + e.getMessage());
            uploadScheduled = false;
            scheduleUpload(context, RETRY_DELAY_MINS);
            return Result.failure();
        }
    }

    private boolean upload(List<AppUsageEvent> events) {
        ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
        ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);
        String deviceId = settingsHelper.getDeviceId();
        String signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + deviceId);
        Response<ResponseBody> response = null;

        try {
            response = serverService.sendAppUsage(settingsHelper.getServerProject(), deviceId, signature, events).execute();
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to upload app usage to primary server: " + e.getMessage());
        }

        try {
            if (response == null || !response.isSuccessful()) {
                response = secondaryServerService.sendAppUsage(settingsHelper.getServerProject(), deviceId, signature, events).execute();
            }
            return response != null && response.isSuccessful();
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Failed to upload app usage: " + e.getMessage());
        }
        return false;
    }
}
