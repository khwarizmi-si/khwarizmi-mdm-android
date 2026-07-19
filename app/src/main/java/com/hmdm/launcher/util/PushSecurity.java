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

import com.hmdm.launcher.helper.CryptoHelper;
import com.hmdm.launcher.json.PushMessage;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

public class PushSecurity {
    public static final String SIGNATURE_FIELD = "signature";

    public static boolean isSensitiveMessageType(String messageType) {
        return PushMessage.TYPE_BROADCAST.equals(messageType) ||
                PushMessage.TYPE_UNINSTALL_APP.equals(messageType) ||
                PushMessage.TYPE_DELETE_FILE.equals(messageType) ||
                PushMessage.TYPE_DELETE_DIR.equals(messageType) ||
                PushMessage.TYPE_PURGE_DIR.equals(messageType) ||
                PushMessage.TYPE_PERMISSIVE_MODE.equals(messageType) ||
                PushMessage.TYPE_RUN_COMMAND.equals(messageType) ||
                PushMessage.TYPE_REBOOT.equals(messageType) ||
                PushMessage.TYPE_LOCK.equals(messageType) ||
                PushMessage.TYPE_WIPE.equals(messageType) ||
                PushMessage.TYPE_EXIT_KIOSK.equals(messageType) ||
                PushMessage.TYPE_CLEAR_DOWNLOADS.equals(messageType) ||
                PushMessage.TYPE_INTENT.equals(messageType) ||
                PushMessage.TYPE_GRANT_PERMISSIONS.equals(messageType) ||
                PushMessage.TYPE_ADMIN_PANEL.equals(messageType) ||
                PushMessage.TYPE_CLEAR_APP_DATA.equals(messageType) ||
                PushMessage.TYPE_REMOTE_SCREEN_START.equals(messageType) ||
                PushMessage.TYPE_REMOTE_SCREEN_STOP.equals(messageType) ||
                PushMessage.TYPE_REMOTE_SCREEN_CONTROL.equals(messageType);
    }

    public static boolean isMqttMessageAllowed(String messageType, JSONObject payload, String signature, String sharedSecret) {
        if (!isSensitiveMessageType(messageType)) {
            return true;
        }
        if (sharedSecret == null || sharedSecret.isEmpty() || signature == null || signature.isEmpty()) {
            return false;
        }
        String expected = calculatePushSignature(sharedSecret, messageType, payload);
        return expected.equalsIgnoreCase(signature);
    }

    public static String calculatePushSignature(String sharedSecret, String messageType, JSONObject payload) {
        String payloadText = payload != null ? payload.toString() : "";
        return CryptoHelper.getSHA1String(sharedSecret + messageType + payloadText);
    }

    public static File resolveChildPath(File root, String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IOException("Path is empty");
        }

        File rootCanonical = root.getCanonicalFile();
        File target = new File(rootCanonical, path).getCanonicalFile();
        String rootPath = rootCanonical.getPath();
        String targetPath = target.getPath();

        if (targetPath.equals(rootPath) || !targetPath.startsWith(rootPath + File.separator)) {
            throw new IOException("Path is outside the allowed storage root");
        }

        return target;
    }
}
