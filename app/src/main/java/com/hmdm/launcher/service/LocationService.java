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

package com.hmdm.launcher.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.util.RemoteLogger;

public class LocationService extends Service {
    private LocationManager locationManager;

    private static final int NOTIFICATION_ID = 112;
    public static String CHANNEL_ID = LocationService.class.getName();

    public static final String ACTION_UPDATE_GPS = "gps";
    public static final String ACTION_UPDATE_NETWORK = "network";
    public static final String ACTION_STOP = "stop";

    boolean updateViaGps = false;
    boolean started = false;

    private static final int LOCATION_UPDATE_INTERVAL = 60000;

    // Use different location listeners for GPS and Network
    // Not sure what happens if we share the same listener for both providers
    private LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Do nothing here: we use getLastKnownLocation() to determine the location!
            //Toast.makeText(LocationService.this, "Location updated from GPS", Toast.LENGTH_SHORT).show();
            RemoteLogger.log(LocationService.this, Const.LOG_VERBOSE, "GPS location update: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            ProUtils.processLocation(LocationService.this, location, LocationManager.GPS_PROVIDER);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    private LocationListener networkLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Do nothing here: we use getLastKnownLocation() to determine the location!
            //Toast.makeText(LocationService.this, "Location updated from Network", Toast.LENGTH_SHORT).show();
            RemoteLogger.log(LocationService.this, Const.LOG_VERBOSE, "Network location update: lat="
                    + location.getLatitude() + ", lon=" + location.getLongitude());
            ProUtils.processLocation(LocationService.this, location, LocationManager.NETWORK_PROVIDER);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private Handler handler = new Handler();
    private GnssStatus.Callback gnssStatusCallback = null;
    private boolean gnssStatusRegistered = false;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager)this.getSystemService(LOCATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                    super.onSatelliteStatusChanged(status);
                    try {
                        Log.d(Const.LOG_TAG, "Satellite status changed, count: " + status.getSatelliteCount());
                        SettingsHelper.getInstance(LocationService.this.getApplicationContext()).setSatelliteCount(status.getSatelliteCount());
                    } catch (Exception e) {
                        RemoteLogger.log(LocationService.this, Const.LOG_WARN, "Failed to update satellite count: " + e.getMessage());
                    }
                }
            };
        }
    }

    @SuppressLint("WrongConstant")
    private void startAsForeground() {
        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Notification Channel", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder( this );
        }
        Notification notification = builder
                .setContentTitle(ProUtils.getAppName(this))
                .setTicker(ProUtils.getAppName(this))
                .setContentText( getString( R.string.location_service_text ) )
                .setSmallIcon( R.drawable.ic_location_service ).build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private boolean requestLocationUpdates() {
        boolean hasFineLocation = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED;
        boolean hasCoarseLocation = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED;
        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        boolean passiveEnabled = locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER);

        if (updateViaGps && (!hasFineLocation || !gpsEnabled)) {
            updateViaGps = false;
        }
        if (!hasCoarseLocation && !hasFineLocation) {
            // No permission, so give up!
            return false;
        }

        RemoteLogger.log(this, Const.LOG_VERBOSE,
                "Request location updates. gps=" + gpsEnabled + ", network=" + networkEnabled + ", passive=" + passiveEnabled);

        locationManager.removeUpdates(networkLocationListener);
        locationManager.removeUpdates(gpsLocationListener);
        boolean requested = false;
        if (networkEnabled && (hasCoarseLocation || hasFineLocation)) {
            requested = requestProvider(LocationManager.NETWORK_PROVIDER, networkLocationListener);
        }
        if (updateViaGps) {
            boolean gpsRequested = requestProvider(LocationManager.GPS_PROVIDER, gpsLocationListener);
            requested = gpsRequested || requested;
            if (gpsRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                registerGnssStatusCallback();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            unregisterGnssStatusCallback();
        }
        return requested;
    }

    private boolean requestProvider(String provider, LocationListener listener) {
        try {
            locationManager.requestLocationUpdates(provider, LOCATION_UPDATE_INTERVAL, 0, listener);
            return true;
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN, "Failed to request " + provider + " location: " + e.getMessage());
            return false;
        }
    }

    private void registerGnssStatusCallback() {
        if (gnssStatusRegistered) {
            return;
        }
        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
            gnssStatusRegistered = true;
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN, "Failed to register GNSS status callback: " + e.getMessage());
        }
    }

    private void unregisterGnssStatusCallback() {
        if (!gnssStatusRegistered) {
            return;
        }
        try {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            gnssStatusRegistered = false;
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN, "Failed to unregister GNSS status callback: " + e.getMessage());
        }
    }


    @Override
    public void onDestroy() {
        locationManager.removeUpdates(networkLocationListener);
        locationManager.removeUpdates(gpsLocationListener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            unregisterGnssStatusCallback();
        }
        started = false;

        super.onDestroy();
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent inputIntent, int flags, int startId) {
        boolean legacyGpsFlag = updateViaGps;
        if (inputIntent != null && inputIntent.getAction() != null) {
            if (inputIntent.getAction().equals(ACTION_STOP)) {
                // Stop service
                started = false;
                stopForeground(true);
                stopSelf();
                return Service.START_NOT_STICKY;
            } else if (inputIntent.getAction().equals(ACTION_UPDATE_GPS)) {
                updateViaGps = true;
            } else {
                updateViaGps = false;
            }
        } else {
            updateViaGps = false;
        }
        if (!started || legacyGpsFlag != updateViaGps) {
            if (!requestLocationUpdates()) {
                // No permissions!
                started = false;
                stopForeground(true);
                stopSelf();
                return Service.START_NOT_STICKY;
            }
        }
        if (!started) {
            startAsForeground();
            started = true;
        }
        return START_STICKY;
    }
}
