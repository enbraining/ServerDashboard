package com.serverdashboard.web;

import com.serverdashboard.DashboardPlugin;
import com.sun.net.httpserver.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.Executors;

public class WebServer {
    private final DashboardPlugin plugin;
    private final int httpPort;
    private final String token;
    private HttpServer httpServer;
    private HttpsServer httpsServer;

    public WebServer(DashboardPlugin plugin, int httpPort, String token) {
        this.plugin = plugin;
        this.httpPort = httpPort;
        this.token = token;
    }

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(httpPort), 50);
        setupContexts(httpServer);
        httpServer.setExecutor(Executors.newFixedThreadPool(4));
        httpServer.start();

        if (plugin.getConfig().getBoolean("web.https.enabled", false)) {
            int httpsPort = plugin.getConfig().getInt("web.https.port", 8443);
            String certPath = plugin.getConfig().getString("web.https.cert", "");
            String keyPath  = plugin.getConfig().getString("web.https.key",  "");
            try {
                startHttps(httpsPort, certPath, keyPath);
                plugin.getLogger().info("HTTPS 서버 시작: " + httpsPort + "번 포트");
            } catch (Exception e) {
                plugin.getLogger().severe("HTTPS 시작 실패: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void startHttps(int port, String certPath, String keyPath) throws Exception {
        if (certPath.isBlank() || keyPath.isBlank())
            throw new IllegalArgumentException("web.https.cert / web.https.key 경로가 비어 있습니다.");

        SSLContext ctx = buildSslContext(certPath, keyPath);
        httpsServer = HttpsServer.create(new InetSocketAddress(port), 50);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(ctx) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(getSSLContext().getDefaultSSLParameters());
            }
        });
        setupContexts(httpsServer);
        httpsServer.setExecutor(Executors.newFixedThreadPool(4));
        httpsServer.start();
    }

    /** PEM 인증서 + PKCS8 개인키로 SSLContext 구성 */
    private SSLContext buildSslContext(String certPath, String keyPath) throws Exception {
        // 인증서 체인 로드 (fullchain.pem 지원)
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> certs;
        try (InputStream is = new FileInputStream(certPath)) {
            certs = new ArrayList<>(cf.generateCertificates(is));
        }
        if (certs.isEmpty()) throw new IllegalStateException("인증서를 찾을 수 없습니다: " + certPath);

        // 개인키 로드
        PrivateKey privateKey = loadPrivateKey(keyPath);

        // 인메모리 KeyStore 생성
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("server", privateKey, new char[0], certs.toArray(new Certificate[0]));

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /**
     * PKCS8 PEM 형식 개인키 로드 (BEGIN PRIVATE KEY).
     * Let's Encrypt 기본 certbot 출력 형식과 호환됩니다.
     * PKCS1 형식(BEGIN RSA/EC PRIVATE KEY)은 먼저 변환 필요:
     *   openssl pkcs8 -topk8 -nocrypt -in privkey.pem -out privkey_pkcs8.pem
     */
    private PrivateKey loadPrivateKey(String path) throws Exception {
        String pem = Files.readString(Path.of(path));
        String base64 = pem
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String alg : new String[]{"RSA", "EC"}) {
            try { return KeyFactory.getInstance(alg).generatePrivate(spec); }
            catch (Exception ignored) {}
        }
        throw new InvalidKeyException(
            "키 알고리즘을 인식할 수 없습니다. PKCS8 형식인지 확인하세요.\n" +
            "변환: openssl pkcs8 -topk8 -nocrypt -in privkey.pem -out privkey_pkcs8.pem"
        );
    }

    /** SSL 인증서 핫 리로드 */
    public void reloadSsl() {
        if (!plugin.getConfig().getBoolean("web.https.enabled", false)) {
            plugin.getLogger().warning("HTTPS가 비활성화 상태입니다.");
            return;
        }
        if (httpsServer != null) {
            httpsServer.stop(0);
            httpsServer = null;
        }
        int httpsPort = plugin.getConfig().getInt("web.https.port", 8443);
        String certPath = plugin.getConfig().getString("web.https.cert", "");
        String keyPath  = plugin.getConfig().getString("web.https.key",  "");
        try {
            startHttps(httpsPort, certPath, keyPath);
            plugin.getLogger().info("SSL 인증서 리로드 완료.");
        } catch (Exception e) {
            plugin.getLogger().severe("SSL 리로드 실패: " + e.getMessage());
        }
    }

    public void stop() {
        if (httpServer  != null) httpServer.stop(0);
        if (httpsServer != null) httpsServer.stop(0);
    }

    private void setupContexts(HttpServer server) {
        server.createContext("/api", new ApiHandler(plugin, token));
        server.createContext("/", this::serveStatic);
    }

    private void serveStatic(HttpExchange ex) throws IOException {
        ex.getRequestBody().readAllBytes();

        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";

        ex.getResponseHeaders().add("Connection", "close");

        String resourcePath = "/web" + path;
        InputStream is = getClass().getResourceAsStream(resourcePath);

        if (is == null) {
            byte[] msg = "404 Not Found".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
            ex.close();
            return;
        }

        String contentType = getContentType(path);
        byte[] bytes = is.readAllBytes();
        is.close();

        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
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
