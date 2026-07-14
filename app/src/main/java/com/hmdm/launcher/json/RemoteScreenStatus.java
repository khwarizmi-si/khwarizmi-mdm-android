package com.hmdm.launcher.json;

public class RemoteScreenStatus {
    private final String status;
    private final String reason;

    public RemoteScreenStatus(String status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }
}
