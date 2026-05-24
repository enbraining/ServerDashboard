package com.serverdashboard.managers;

import com.serverdashboard.DashboardPlugin;
import com.serverdashboard.models.Announcement;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AnnouncementManager {
    private final DashboardPlugin plugin;
    private final File dataFile;
    private YamlConfiguration dataConfig;
    private final Map<String, Announcement> announcements = new LinkedHashMap<>();
    private final Map<String, BukkitTask> tasks = new ConcurrentHashMap<>();

    public AnnouncementManager(DashboardPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "announcements.yml");
    }

    public void load() {
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException ignored) {}
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        announcements.clear();

        if (dataConfig.contains("announcements")) {
            for (String id : dataConfig.getConfigurationSection("announcements").getKeys(false)) {
                String path = "announcements." + id;
                String message = dataConfig.getString(path + ".message", "");
                int interval = dataConfig.getInt(path + ".interval", 300);
                boolean enabled = dataConfig.getBoolean(path + ".enabled", true);
                String permission = dataConfig.getString(path + ".permission", null);
                announcements.put(id, new Announcement(id, message, interval, enabled, permission));
            }
        }
    }

    public void saveAll() {
        dataConfig = new YamlConfiguration();
        for (Announcement a : announcements.values()) {
            String path = "announcements." + a.getId();
            dataConfig.set(path + ".message", a.getMessage());
            dataConfig.set(path + ".interval", a.getIntervalSeconds());
            dataConfig.set(path + ".enabled", a.isEnabled());
            dataConfig.set(path + ".permission", a.getPermission());
        }
        try { dataConfig.save(dataFile); } catch (IOException e) {
            plugin.getLogger().severe("공지 저장 실패: " + e.getMessage());
        }
    }

    public void startAll() {
        for (Announcement a : announcements.values()) {
            if (a.isEnabled()) startTask(a);
        }
    }

    public void stopAll() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
    }

    public void startTask(Announcement a) {
        stopTask(a.getId());
        long ticks = a.getIntervalSeconds() * 20L;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Component msg = LegacyComponentSerializer.legacyAmpersand().deserialize(a.getMessage());
            Bukkit.getOnlinePlayers().stream()
                .filter(p -> a.getPermission() == null || p.hasPermission(a.getPermission()))
                .forEach(p -> p.sendMessage(msg));
        }, ticks, ticks);
        tasks.put(a.getId(), task);
    }

    public void stopTask(String id) {
        BukkitTask task = tasks.remove(id);
        if (task != null) task.cancel();
    }

    public Collection<Announcement> getAll() { return announcements.values(); }

    public Optional<Announcement> getById(String id) {
        return Optional.ofNullable(announcements.get(id));
    }

    public Announcement create(String message, int intervalSeconds, boolean enabled, String permission) {
        Announcement a = new Announcement(message, intervalSeconds, enabled, permission);
        announcements.put(a.getId(), a);
        if (enabled) startTask(a);
        saveAll();
        return a;
    }

    public boolean update(String id, String message, int intervalSeconds, boolean enabled, String permission) {
        Announcement a = announcements.get(id);
        if (a == null) return false;
        a.setMessage(message);
        a.setIntervalSeconds(intervalSeconds);
        a.setPermission(permission);
        if (a.isEnabled() != enabled) {
            a.setEnabled(enabled);
            if (enabled) startTask(a); else stopTask(id);
        } else if (enabled) {
            // 인터벌 변경 반영을 위해 재시작
            startTask(a);
        }
        saveAll();
        return true;
    }

    public boolean toggle(String id) {
        Announcement a = announcements.get(id);
        if (a == null) return false;
        a.setEnabled(!a.isEnabled());
        if (a.isEnabled()) startTask(a); else stopTask(id);
        saveAll();
        return true;
    }

    public boolean delete(String id) {
        if (!announcements.containsKey(id)) return false;
        stopTask(id);
        announcements.remove(id);
        saveAll();
        return true;
    }
}
