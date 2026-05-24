package com.serverdashboard.models;

import java.util.UUID;

public class Announcement {
    private String id;
    private String message;
    private int intervalSeconds;
    private boolean enabled;
    private String permission; // null = 전체 공지

    public Announcement(String message, int intervalSeconds, boolean enabled, String permission) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.message = message;
        this.intervalSeconds = intervalSeconds;
        this.enabled = enabled;
        this.permission = permission;
    }

    public Announcement(String id, String message, int intervalSeconds, boolean enabled, String permission) {
        this.id = id;
        this.message = message;
        this.intervalSeconds = intervalSeconds;
        this.enabled = enabled;
        this.permission = permission;
    }

    public String getId() { return id; }
    public String getMessage() { return message; }
    public int getIntervalSeconds() { return intervalSeconds; }
    public boolean isEnabled() { return enabled; }
    public String getPermission() { return permission; }

    public void setMessage(String message) { this.message = message; }
    public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setPermission(String permission) { this.permission = permission; }
}
