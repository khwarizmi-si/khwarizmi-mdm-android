package com.hmdm.launcher.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteScreenCaptureServiceTest {

    @Test
    public void onlyAcceptsControlsForTheActiveSession() {
        assertTrue(RemoteScreenCaptureService.isCurrentSession("active-session", "active-session"));
        assertFalse(RemoteScreenCaptureService.isCurrentSession("active-session", "stale-session"));
        assertFalse(RemoteScreenCaptureService.isCurrentSession("active-session", ""));
        assertFalse(RemoteScreenCaptureService.isCurrentSession(null, "active-session"));
    }

    @Test
    public void onlyCapturesWhenThePreviousFrameIsNotBeingProcessed() {
        assertTrue(RemoteScreenCaptureService.shouldCaptureFrame(80, 0, false));
        assertFalse(RemoteScreenCaptureService.shouldCaptureFrame(79, 0, false));
        assertFalse(RemoteScreenCaptureService.shouldCaptureFrame(2000, 0, true));
    }

    @Test
    public void scalesCaptureDimensionsDownWithoutCreatingZeroSizedFrames() {
        assertTrue(RemoteScreenCaptureService.captureDimension(720) == 240);
        assertTrue(RemoteScreenCaptureService.captureDimension(1) == 1);
    }
}
