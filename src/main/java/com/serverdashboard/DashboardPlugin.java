package com.serverdashboard;

import com.serverdashboard.managers.AnnouncementManager;
import com.serverdashboard.web.WebServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.HexFormat;

public class DashboardPlugin extends JavaPlugin {
    private static DashboardPlugin instance;
    private WebServer webServer;
    private AnnouncementManager announcementManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        announcementManager = new AnnouncementManager(this);
        announcementManager.load();

        int port = getConfig().getInt("web.port", 8080);
        String token = getConfig().getString("web.token", "changeme-please");

        if (token.equals("changeme-please")) {
            getLogger().warning("===========================================");
            getLogger().warning(" config.yml 의 web.token 을 반드시 변경하세요!");
            getLogger().warning("===========================================");
        }

        webServer = new WebServer(this, port, token);
        try {
            webServer.start();
            getLogger().info("대시보드 웹서버 시작: http://localhost:" + port);
            getLogger().info("API 토큰: " + token);
        } catch (Exception e) {
            getLogger().severe("웹서버 시작 실패: " + e.getMessage());
        }

        announcementManager.startAll();
        getLogger().info("ServerDashboard 플러그인 활성화 완료");
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop();
        if (announcementManager != null) {
            announcementManager.stopAll();
            announcementManager.saveAll();
        }
        getLogger().info("ServerDashboard 플러그인 비활성화");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("dashboard")) return false;

        if (!sender.hasPermission("serverdashboard.admin")) {
            sender.sendMessage("§c권한이 없습니다.");
            return true;
        }

        if (args.length == 0) {
            int port = getConfig().getInt("web.port", 8080);
            String token = getConfig().getString("web.token", "");
            sender.sendMessage("§a[Dashboard] §f웹 대시보드: §bhttp://서버IP:" + port);
            sender.sendMessage("§a[Dashboard] §fAPI 토큰: §e" + token);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§a[Dashboard] §f설정 파일 새로고침 완료. 웹서버를 재시작하려면 서버를 재시작하세요.");
            return true;
        }

        if (args[0].equalsIgnoreCase("token")) {
            // 랜덤 토큰 생성
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            String newToken = HexFormat.of().formatHex(bytes);
            getConfig().set("web.token", newToken);
            saveConfig();
            sender.sendMessage("§a[Dashboard] §f새 토큰 생성됨: §e" + newToken);
            sender.sendMessage("§c웹서버를 재시작해야 적용됩니다.");
            return true;
        }

        sender.sendMessage("§c사용법: /dashboard [reload|token]");
        return true;
    }

    public static DashboardPlugin getInstance() { return instance; }
    public AnnouncementManager getAnnouncementManager() { return announcementManager; }
}
