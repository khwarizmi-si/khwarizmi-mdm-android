package com.hmdm.launcher.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.service.RemoteScreenCaptureService;
import com.hmdm.launcher.util.RemoteLogger;

public class RemoteScreenPermissionActivity extends Activity {
    private static final int REQUEST_MEDIA_PROJECTION = 9071;
    public static final String EXTRA_SESSION_ID = "sessionId";

    private String sessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        if (sessionId == null || sessionId.length() == 0) {
            finish();
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent captureIntent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            Intent intent = new Intent(this, RemoteScreenCaptureService.class);
            intent.setAction(RemoteScreenCaptureService.ACTION_START);
            intent.putExtra(RemoteScreenCaptureService.EXTRA_SESSION_ID, sessionId);
            intent.putExtra(RemoteScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            intent.putExtra(RemoteScreenCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } else {
            RemoteLogger.log(this, Const.LOG_WARN, "Remote screen permission denied");
            RemoteScreenCaptureService.reportStatus(this, sessionId, "failed", "permission_denied");
        }
        finish();
    }
}
