package com.serverdashboard.web;

import com.serverdashboard.DashboardPlugin;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class WebServer {
    private final DashboardPlugin plugin;
    private final int port;
    private final String token;
    private HttpServer server;

    public WebServer(DashboardPlugin plugin, int port, String token) {
        this.plugin = plugin;
        this.port = port;
        this.token = token;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", new ApiHandler(plugin, token));
        server.createContext("/", this::serveStatic);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";

        String resourcePath = "/web" + path;
        InputStream is = getClass().getResourceAsStream(resourcePath);

        if (is == null) {
            byte[] msg = "404 Not Found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, msg.length);
            ex.getResponseBody().write(msg);
            ex.getResponseBody().close();
            return;
        }

        String contentType = getContentType(path);
        byte[] bytes = is.readAllBytes();
        is.close();

        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css"))  return "text/css; charset=UTF-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }
}
