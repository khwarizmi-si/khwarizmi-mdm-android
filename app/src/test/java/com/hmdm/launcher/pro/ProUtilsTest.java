package com.hmdm.launcher.pro;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProUtilsTest {

    @Test
    public void isSystemSupportPackageAllowsPermissionControllerVariants() {
        assertTrue(ProUtils.isSystemSupportPackage("com.google.android.permissioncontroller"));
        assertTrue(ProUtils.isSystemSupportPackage("com.android.permissioncontroller"));
    }

    @Test
    public void isSystemSupportPackageKeepsSettingsControlledByAdminMode() {
        assertFalse(ProUtils.isSystemSupportPackage("com.android.settings"));
    }

    @Test
    public void isSystemSupportPackageRejectsUnknownUserApps() {
        assertFalse(ProUtils.isSystemSupportPackage("com.example.unapproved"));
    }

    @Test
    public void resolvesBlankKioskAppToTheLauncher() {
        assertEquals("com.alkhwarizmi.mdm", ProUtils.resolveKioskApp(null, "com.alkhwarizmi.mdm"));
        assertEquals("com.alkhwarizmi.mdm", ProUtils.resolveKioskApp("  ", "com.alkhwarizmi.mdm"));
        assertEquals("com.example.kiosk", ProUtils.resolveKioskApp("com.example.kiosk", "com.alkhwarizmi.mdm"));
    }
}
