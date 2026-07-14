package com.hmdm.launcher.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.CryptoHelper;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.RemoteScreenFrame;
import com.hmdm.launcher.json.RemoteScreenStatus;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;
import com.hmdm.launcher.util.RemoteLogger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoteScreenCaptureService extends Service {
    public static final String ACTION_START = "remote_screen_start";
    public static final String ACTION_STOP = "remote_screen_stop";
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final int NOTIFICATION_ID = 118;
    private static final long FRAME_INTERVAL_MS = 1000;
    public static final String CHANNEL_ID = RemoteScreenCaptureService.class.getName();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private HandlerThread captureThread;
    private ImageReader imageReader;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private String sessionId;
    private long lastFrameAt;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        if (sessionId == null || resultData == null || resultCode == 0) {
            RemoteLogger.log(this, Const.LOG_WARN, "Remote screen capture failed: missing permission data");
            reportStatus(this, sessionId, "failed", "missing_permission_data");
            stopSelf();
            return START_NOT_STICKY;
        }

        startAsForeground();
        startCapture(resultCode, resultData);
        return START_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData) {
        stopCapture();
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            RemoteLogger.log(this, Const.LOG_WARN, "Remote screen capture failed: projection unavailable");
            reportStatus(this, sessionId, "failed", "projection_unavailable");
            stopSelf();
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        captureThread = new HandlerThread("RemoteScreenCapture");
        captureThread.start();
        Handler handler = new Handler(captureThread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                reportStatus(RemoteScreenCaptureService.this, sessionId, "ended", "projection_stopped");
                releaseCaptureResources(false);
                stopSelf();
            }
        }, handler);
        try {
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels,
                    PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);
            virtualDisplay = projection.createVirtualDisplay("RemoteScreen",
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, handler);
            RemoteLogger.log(this, Const.LOG_INFO, "Remote screen capture started: " + sessionId);
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN, "Remote screen capture failed: " + e.getMessage());
            reportStatus(this, sessionId, "failed", "capture_start_failed");
            stopCapture();
            stopSelf();
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFrameAt < FRAME_INTERVAL_MS) {
            image.close();
            return;
        }
        lastFrameAt = now;
        executor.execute(() -> uploadFrame(image));
    }

    private void uploadFrame(Image image) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap paddedBitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            paddedBitmap.copyPixelsFromBuffer(buffer);
            Bitmap bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height);
            paddedBitmap.recycle();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 45, output);
            bitmap.recycle();

            String imageData = "data:image/jpeg;base64," +
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            sendFrame(new RemoteScreenFrame(imageData, width, height));
        } catch (Exception e) {
            RemoteLogger.log(this, Const.LOG_WARN, "Remote screen frame upload failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            image.close();
        }
    }

    private void sendFrame(RemoteScreenFrame frame) throws Exception {
        SettingsHelper settings = SettingsHelper.getInstance(this);
        String deviceId = settings.getDeviceId();
        String signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + deviceId);
        ServerService service = ServerServiceKeeper.getServerServiceInstance(this);
        retrofit2.Response<?> response = service.uploadRemoteScreenFrame(
                settings.getServerProject(), deviceId, sessionId, signature, frame).execute();
        if (!response.isSuccessful()) {
            RemoteLogger.log(this, Const.LOG_WARN,
                    "Remote screen frame rejected: HTTP " + response.code());
            if (response.code() == 403 || response.code() == 404) {
                reportStatus(this, sessionId, "failed", "frame_rejected_" + response.code());
            }
        }
    }

    public static void reportStatus(Context context, String sessionId, String status, String reason) {
        if (sessionId == null || sessionId.length() == 0) {
            return;
        }
        ExecutorService statusExecutor = Executors.newSingleThreadExecutor();
        statusExecutor.execute(() -> {
            try {
                SettingsHelper settings = SettingsHelper.getInstance(context);
                String deviceId = settings.getDeviceId();
                String signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + deviceId);
                ServerService service = ServerServiceKeeper.getServerServiceInstance(context);
                retrofit2.Response<?> response = service.updateRemoteScreenStatus(
                        settings.getServerProject(), deviceId, sessionId, signature,
                        new RemoteScreenStatus(status, reason)).execute();
                if (!response.isSuccessful()) {
                    RemoteLogger.log(context, Const.LOG_WARN,
                            "Remote screen status rejected: HTTP " + response.code());
                }
            } catch (Exception e) {
                RemoteLogger.log(context, Const.LOG_WARN,
                        "Remote screen status update failed: " + e.getMessage());
            } finally {
                statusExecutor.shutdown();
            }
        });
    }

    private void stopCapture() {
        releaseCaptureResources(true);
    }

    private void releaseCaptureResources(boolean stopProjection) {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            if (stopProjection) {
                projection.stop();
            }
            projection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
    }

    @SuppressLint("WrongConstant")
    private void startAsForeground() {
        NotificationCompat.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Remote screen", NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder(this);
        }
        Notification notification = builder
                .setContentTitle(ProUtils.getAppName(this))
                .setTicker(ProUtils.getAppName(this))
                .setContentText(getString(R.string.remote_screen_service_text))
                .setSmallIcon(R.drawable.ic_mqtt_service)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        stopCapture();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
