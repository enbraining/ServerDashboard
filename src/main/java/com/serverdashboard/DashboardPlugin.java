package com.serverdashboard;

import com.serverdashboard.managers.AcmeManager;
import com.serverdashboard.managers.AnnouncementManager;
import com.serverdashboard.managers.LogManager;
import com.serverdashboard.web.WebServer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.HexFormat;

public class DashboardPlugin extends JavaPlugin {
    private static DashboardPlugin instance;
    private WebServer webServer;
    private AnnouncementManager announcementManager;
    private AcmeManager acmeManager;
    private LogManager logManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        logManager = new LogManager();
        announcementManager = new AnnouncementManager(this);
        announcementManager.load();

        acmeManager = new AcmeManager(this);

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
        } catch (Exception e) {
            getLogger().severe("웹서버 시작 실패: " + e.getMessage());
        }

        // ACME 자동 갱신 스케줄러 (1일마다 체크)
        if (getConfig().getBoolean("web.https.acme.enabled", false)) {
            long dayTicks = 20L * 60 * 60 * 24;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::autoRenewIfNeeded, dayTicks, dayTicks);
            getLogger().info("[ACME] 자동 갱신 스케줄러 시작 (만료 " + AcmeManager.RENEW_BEFORE_DAYS + "일 전 갱신)");
        }

        announcementManager.startAll();
        getLogger().info("ServerDashboard 플러그인 활성화 완료");
    }

    @Override
    public void onDisable() {
        if (logManager != null) logManager.stop();
        if (webServer != null) webServer.stop();
        if (announcementManager != null) {
            announcementManager.stopAll();
            announcementManager.saveAll();
        }
        getLogger().info("ServerDashboard 플러그인 비활성화");
    }

    private void autoRenewIfNeeded() {
        if (!acmeManager.needsRenewal()) return;
        getLogger().info("[ACME] 인증서가 곧 만료됩니다. 자동 갱신을 시작합니다...");
        String domain        = getConfig().getString("web.https.acme.domain", "");
        String email         = getConfig().getString("web.https.acme.email", "");
        int challengePort    = getConfig().getInt("web.https.acme.challenge-port", 80);
        try {
            acmeManager.issueCertificate(domain, email, challengePort);
            webServer.reloadSsl();
            getLogger().info("[ACME] 자동 갱신 완료.");
        } catch (Exception e) {
            getLogger().severe("[ACME] 자동 갱신 실패: " + e.getMessage());
        }
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
            int httpsPort = getConfig().getInt("web.https.port", 8443);
            boolean httpsEnabled = getConfig().getBoolean("web.https.enabled", false);
            sender.sendMessage("§a[Dashboard] §fHTTP:  §bhttp://서버IP:" + port);
            if (httpsEnabled)
                sender.sendMessage("§a[Dashboard] §fHTTPS: §bhttps://서버IP:" + httpsPort);
            sender.sendMessage("§a[Dashboard] §fAPI 토큰: §e" + token);
            if (acmeManager.hasCertificate())
                sender.sendMessage("§a[Dashboard] §f인증서 만료: §e" + acmeManager.getCertExpiry());
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                sender.sendMessage("§a[Dashboard] §f설정 파일 새로고침 완료.");
            }
            case "reload-ssl" -> {
                if (webServer == null) { sender.sendMessage("§c웹서버가 실행 중이지 않습니다."); return true; }
                webServer.reloadSsl();
                sender.sendMessage("§a[Dashboard] §fSSL 인증서 리로드 완료.");
            }
            case "cert-issue" -> {
                if (!getConfig().getBoolean("web.https.acme.enabled", false)) {
                    sender.sendMessage("§c[Dashboard] §fconfig.yml 에서 web.https.acme.enabled: true 를 설정하세요.");
                    return true;
                }
                String domain     = getConfig().getString("web.https.acme.domain", "");
                String email      = getConfig().getString("web.https.acme.email", "");
                int challengePort = getConfig().getInt("web.https.acme.challenge-port", 80);
                if (domain.isBlank() || email.isBlank()) {
                    sender.sendMessage("§c[Dashboard] §fdomain 과 email 을 config.yml 에 설정하세요.");
                    return true;
                }
                sender.sendMessage("§a[Dashboard] §f인증서 발급 시작... 콘솔을 확인하세요.");
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        acmeManager.issueCertificate(domain, email, challengePort);
                        webServer.reloadSsl();
                        Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage("§a[Dashboard] §f인증서 발급 및 HTTPS 적용 완료!")
                        );
                    } catch (Exception e) {
                        getLogger().severe("[ACME] 발급 실패: " + e.getMessage());
                        Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage("§c[Dashboard] §f발급 실패: " + e.getMessage())
                        );
                    }
                });
            }
            case "token" -> {
                byte[] bytes = new byte[16];
                new SecureRandom().nextBytes(bytes);
                String newToken = HexFormat.of().formatHex(bytes);
                getConfig().set("web.token", newToken);
                saveConfig();
                sender.sendMessage("§a[Dashboard] §f새 토큰: §e" + newToken);
                sender.sendMessage("§c웹서버를 재시작해야 적용됩니다.");
            }
            default -> sender.sendMessage("§c사용법: /dashboard [reload|reload-ssl|cert-issue|token]");
        }
        return true;
    }

    public static DashboardPlugin getInstance() { return instance; }
    public AnnouncementManager getAnnouncementManager() { return announcementManager; }
    public AcmeManager getAcmeManager() { return acmeManager; }
    public LogManager getLogManager() { return logManager; }
}
