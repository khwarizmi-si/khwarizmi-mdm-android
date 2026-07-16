package com.hmdm.launcher.pro;

import com.hmdm.launcher.json.Application;

import org.junit.Test;

import java.util.Arrays;

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

    @Test
    public void allowlistsAppsShownInTheLauncherDuringKiosk() {
        Application shown = new Application();
        shown.setPkg("com.android.chrome");
        shown.setShowIcon(true);

        Application hidden = new Application();
        hidden.setPkg("com.example.hidden");

        assertTrue(ProUtils.getLockTaskPackages("com.alkhwarizmi.mdm", "com.alkhwarizmi.mdm",
                Arrays.asList(shown, hidden)).contains("com.android.chrome"));
        assertFalse(ProUtils.getLockTaskPackages("com.alkhwarizmi.mdm", "com.alkhwarizmi.mdm",
                Arrays.asList(shown, hidden)).contains("com.example.hidden"));
    }

    @Test
    public void onlyTemporarilyAllowsSettingsForAccessibilitySetup() {
        assertFalse(ProUtils.getLockTaskPackages("com.alkhwarizmi.mdm", "com.alkhwarizmi.mdm",
                null, false).contains("com.android.settings"));
        assertTrue(ProUtils.getLockTaskPackages("com.alkhwarizmi.mdm", "com.alkhwarizmi.mdm",
                null, true).contains("com.android.settings"));
    }
}
