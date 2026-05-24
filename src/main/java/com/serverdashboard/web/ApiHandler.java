package com.serverdashboard.web;

import com.google.gson.*;
import com.serverdashboard.DashboardPlugin;
import com.serverdashboard.managers.AnnouncementManager;
import com.serverdashboard.models.Announcement;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.BanList;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ApiHandler implements HttpHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final DashboardPlugin plugin;
    private final String token;

    public ApiHandler(DashboardPlugin plugin, String token) {
        this.plugin = plugin;
        this.token = token;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);

        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }

        String authHeader = ex.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.equals("Bearer " + token)) {
            send(ex, 401, obj("error", "Unauthorized"));
            return;
        }

        String path = ex.getRequestURI().getPath().replaceFirst("^/api", "");
        String method = ex.getRequestMethod();

        try {
            route(ex, method, path);
        } catch (Exception e) {
            plugin.getLogger().warning("API 오류: " + e.getMessage());
            send(ex, 500, obj("error", e.getMessage()));
        }
    }

    private void route(HttpExchange ex, String method, String path) throws Exception {
        // --- 플레이어 ---
        if (path.equals("/players") && method.equals("GET")) {
            handleGetPlayers(ex);
        } else if (path.equals("/kick") && method.equals("POST")) {
            handleKick(ex);
        } else if (path.equals("/ban") && method.equals("POST")) {
            handleBan(ex);
        } else if (path.equals("/unban") && method.equals("POST")) {
            handleUnban(ex);
        } else if (path.equals("/banned") && method.equals("GET")) {
            handleGetBanned(ex);
        }
        // --- 공지 ---
        else if (path.equals("/announcements") && method.equals("GET")) {
            handleGetAnnouncements(ex);
        } else if (path.equals("/announcements") && method.equals("POST")) {
            handleCreateAnnouncement(ex);
        } else if (path.matches("/announcements/[^/]+") && method.equals("PUT")) {
            String id = path.substring("/announcements/".length());
            handleUpdateAnnouncement(ex, id);
        } else if (path.matches("/announcements/[^/]+/toggle") && method.equals("POST")) {
            String id = path.substring("/announcements/".length()).replace("/toggle", "");
            handleToggleAnnouncement(ex, id);
        } else if (path.matches("/announcements/[^/]+") && method.equals("DELETE")) {
            String id = path.substring("/announcements/".length());
            handleDeleteAnnouncement(ex, id);
        }
        // --- 서버 상태 ---
        else if (path.equals("/status") && method.equals("GET")) {
            handleGetStatus(ex);
        } else {
            send(ex, 404, obj("error", "Not Found"));
        }
    }

    private void handleGetPlayers(HttpExchange ex) throws Exception {
        JsonArray arr = runOnMain(() -> {
            JsonArray a = new JsonArray();
            for (Player p : Bukkit.getOnlinePlayers()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getName());
                o.addProperty("uuid", p.getUniqueId().toString());
                o.addProperty("ping", p.getPing());
                o.addProperty("world", p.getWorld().getName());
                o.addProperty("op", p.isOp());
                a.add(o);
            }
            return a;
        });
        send(ex, 200, arr);
    }

    private void handleKick(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String name = getStr(body, "player");
        String reason = getStr(body, "reason", "대시보드에서 강퇴되었습니다.");

        Boolean result = runOnMain(() -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) return false;
            p.kick(LegacyComponentSerializer.legacyAmpersand().deserialize(reason));
            return true;
        });

        if (result) send(ex, 200, obj("message", name + " 강퇴 완료"));
        else send(ex, 404, obj("error", "플레이어를 찾을 수 없습니다."));
    }

    private void handleBan(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String name = getStr(body, "player");
        String reason = getStr(body, "reason", "대시보드에서 차단되었습니다.");
        String expiryStr = body.has("expiry") && !body.get("expiry").isJsonNull()
                ? body.get("expiry").getAsString() : null;

        Boolean result = runOnMain(() -> {
            Date expiry = null;
            if (expiryStr != null && !expiryStr.isEmpty()) {
                try {
                    expiry = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").parse(expiryStr);
                } catch (Exception ignored) {}
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(name);
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, expiry, "Dashboard");
            Player online = target.getPlayer();
            if (online != null) {
                online.kick(LegacyComponentSerializer.legacyAmpersand().deserialize(reason));
            }
            return true;
        });

        send(ex, 200, obj("message", name + " 밴 완료"));
    }

    private void handleUnban(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String name = getStr(body, "player");

        runOnMain(() -> {
            Bukkit.getBanList(BanList.Type.NAME).pardon(name);
            return null;
        });

        send(ex, 200, obj("message", name + " 밴 해제 완료"));
    }

    private void handleGetBanned(HttpExchange ex) throws Exception {
        JsonArray arr = runOnMain(() -> {
            JsonArray a = new JsonArray();
            for (var entry : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
                JsonObject o = new JsonObject();
                o.addProperty("player", entry.getTarget());
                o.addProperty("reason", entry.getReason());
                o.addProperty("source", entry.getSource());
                o.addProperty("created", entry.getCreated() != null ? entry.getCreated().toString() : "");
                o.addProperty("expires", entry.getExpiration() != null ? entry.getExpiration().toString() : "영구");
                a.add(o);
            }
            return a;
        });
        send(ex, 200, arr);
    }

    private void handleGetAnnouncements(HttpExchange ex) throws IOException {
        AnnouncementManager am = plugin.getAnnouncementManager();
        JsonArray arr = new JsonArray();
        for (Announcement a : am.getAll()) {
            arr.add(announcementToJson(a));
        }
        send(ex, 200, arr);
    }

    private void handleCreateAnnouncement(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String message = getStr(body, "message");
        int interval = body.has("interval") ? body.get("interval").getAsInt() : 300;
        boolean enabled = !body.has("enabled") || body.get("enabled").getAsBoolean();
        String permission = body.has("permission") && !body.get("permission").isJsonNull()
                ? body.get("permission").getAsString() : null;

        if (message == null || message.isBlank()) {
            send(ex, 400, obj("error", "message 필드가 필요합니다."));
            return;
        }

        Announcement created = runOnMain(() ->
            plugin.getAnnouncementManager().create(message, interval, enabled, permission)
        );

        send(ex, 201, announcementToJson(created));
    }

    private void handleUpdateAnnouncement(HttpExchange ex, String id) throws Exception {
        JsonObject body = readBody(ex);
        String message = getStr(body, "message");
        int interval = body.has("interval") ? body.get("interval").getAsInt() : 300;
        boolean enabled = !body.has("enabled") || body.get("enabled").getAsBoolean();
        String permission = body.has("permission") && !body.get("permission").isJsonNull()
                ? body.get("permission").getAsString() : null;

        Boolean ok = runOnMain(() ->
            plugin.getAnnouncementManager().update(id, message, interval, enabled, permission)
        );

        if (ok) send(ex, 200, obj("message", "공지 수정 완료"));
        else send(ex, 404, obj("error", "공지를 찾을 수 없습니다."));
    }

    private void handleToggleAnnouncement(HttpExchange ex, String id) throws Exception {
        Boolean ok = runOnMain(() -> plugin.getAnnouncementManager().toggle(id));
        if (ok) send(ex, 200, obj("message", "공지 토글 완료"));
        else send(ex, 404, obj("error", "공지를 찾을 수 없습니다."));
    }

    private void handleDeleteAnnouncement(HttpExchange ex, String id) throws Exception {
        Boolean ok = runOnMain(() -> plugin.getAnnouncementManager().delete(id));
        if (ok) send(ex, 200, obj("message", "공지 삭제 완료"));
        else send(ex, 404, obj("error", "공지를 찾을 수 없습니다."));
    }

    private void handleGetStatus(HttpExchange ex) throws Exception {
        JsonObject status = runOnMain(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("online", Bukkit.getOnlinePlayers().size());
            o.addProperty("max", Bukkit.getMaxPlayers());
            o.addProperty("motd", Bukkit.getMotd());
            o.addProperty("version", Bukkit.getVersion());
            o.addProperty("tps", String.format("%.2f", Bukkit.getTPS()[0]));
            return o;
        });
        send(ex, 200, status);
    }

    // --- 유틸리티 ---

    private <T> T runOnMain(java.util.concurrent.Callable<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { future.complete(task.call()); }
            catch (Exception e) { future.completeExceptionally(e); }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("메인 스레드 응답 시간 초과");
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    private JsonObject readBody(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) return new JsonObject();
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private String getStr(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private String getStr(JsonObject obj, String key, String def) {
        String v = getStr(obj, key);
        return v != null ? v : def;
    }

    private JsonObject announcementToJson(Announcement a) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.getId());
        o.addProperty("message", a.getMessage());
        o.addProperty("interval", a.getIntervalSeconds());
        o.addProperty("enabled", a.isEnabled());
        o.addProperty("permission", a.getPermission());
        return o;
    }

    private JsonObject obj(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    private void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Authorization, Content-Type");
        ex.getResponseHeaders().add("Connection", "close");
    }

    private void send(HttpExchange ex, int status, JsonElement body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }
}
