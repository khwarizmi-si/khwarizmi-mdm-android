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

import com.hmdm.launcher.json.PushMessage;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PushSecurityTest {
    @Test
    public void allowsUnsignedNonSensitiveMqttMessages() throws Exception {
        assertTrue(PushSecurity.isMqttMessageAllowed(
                PushMessage.TYPE_CONFIG_UPDATED, null, null, "secret"));
    }

    @Test
    public void rejectsUnsignedSensitiveMqttMessages() throws Exception {
        assertFalse(PushSecurity.isMqttMessageAllowed(
                PushMessage.TYPE_WIPE, new JSONObject(), null, "secret"));
    }

    @Test
    public void acceptsSignedSensitiveMqttMessages() throws Exception {
        String signature = PushSecurity.calculatePushSignature("secret", PushMessage.TYPE_LOCK, null);

        assertTrue(PushSecurity.isMqttMessageAllowed(
                PushMessage.TYPE_LOCK, null, signature, "secret"));
    }

    @Test
    public void requiresSignatureForRemoteScreenControl() throws Exception {
        assertFalse(PushSecurity.isMqttMessageAllowed(
                PushMessage.TYPE_REMOTE_SCREEN_CONTROL, null, null, "secret"));
        assertTrue(PushSecurity.isMqttMessageAllowed(
                PushMessage.TYPE_REMOTE_SCREEN_CONTROL, null,
                PushSecurity.calculatePushSignature("secret", PushMessage.TYPE_REMOTE_SCREEN_CONTROL, null),
                "secret"));
    }

    @Test(expected = IOException.class)
    public void rejectsTraversalOutsideStorageRoot() throws Exception {
        PushSecurity.resolveChildPath(new File("/tmp/root"), "../outside.txt");
    }

    @Test(expected = IOException.class)
    public void rejectsStorageRootItself() throws Exception {
        PushSecurity.resolveChildPath(new File("/tmp/root"), ".");
    }

    @Test
    public void acceptsChildPathInsideStorageRoot() throws Exception {
        PushSecurity.resolveChildPath(new File("/tmp/root"), "downloads/file.txt");
    }
}
