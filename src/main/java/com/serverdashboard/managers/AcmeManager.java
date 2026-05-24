package com.serverdashboard.managers;

import com.serverdashboard.DashboardPlugin;
import com.sun.net.httpserver.HttpServer;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class AcmeManager {
    private static final String ACME_URL = "https://acme-v02.api.letsencrypt.org/directory";
    public static final int RENEW_BEFORE_DAYS = 30;

    private final DashboardPlugin plugin;
    private final File dataFolder;

    public AcmeManager(DashboardPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder();
    }

    public File getCertFile() { return new File(dataFolder, "fullchain.pem"); }
    public File getKeyFile()  { return new File(dataFolder, "privkey.pem"); }
    public boolean hasCertificate() { return getCertFile().exists() && getKeyFile().exists(); }

    /**
     * Let's Encrypt HTTP-01 챌린지로 인증서를 발급합니다.
     * challengePort 는 외부에서 80번으로 접근 가능해야 합니다.
     */
    public void issueCertificate(String domain, String email, int challengePort) throws Exception {
        log("인증서 발급 시작: " + domain + " (챌린지 포트: " + challengePort + ")");

        KeyPair accountKey = loadOrCreateKey("acme-account.key");
        KeyPair domainKey  = loadOrCreateKey("domain.key");

        Session session = new Session(ACME_URL);

        log("Let's Encrypt 계정 준비 중...");
        Account account = new AccountBuilder()
                .agreeToTermsOfService()
                .useKeyPair(accountKey)
                .addEmail(email)
                .createLogin(session)
                .getAccount();

        log("인증서 주문 생성 중...");
        Order order = account.newOrder().domains(domain).create();

        for (Authorization auth : order.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) continue;
            processChallenge(auth, challengePort);
        }

        log("CSR 생성 및 주문 완료 요청 중...");
        CSRBuilder csrb = new CSRBuilder();
        csrb.addDomain(domain);
        csrb.sign(domainKey);
        order.execute(csrb.getEncoded());

        waitFor("주문", () -> order.getStatus() == Status.VALID, order::update,
                order.getStatus() == Status.INVALID ? "주문 실패" : null);

        log("인증서 다운로드 중...");
        Certificate cert = order.getCertificate();
        try (Writer fw = new FileWriter(getCertFile())) {
            cert.writeCertificate(fw);
        }
        // PKCS8 PEM 형식으로 저장 (WebServer.loadPrivateKey 호환)
        byte[] pkcs8Der = domainKey.getPrivate().getEncoded();
        String pkcs8Pem = "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pkcs8Der)
                + "\n-----END PRIVATE KEY-----\n";
        try (Writer fw = new FileWriter(getKeyFile())) {
            fw.write(pkcs8Pem);
        }

        log("인증서 발급 완료! " + getCertFile().getPath());
    }

    private void processChallenge(Authorization auth, int challengePort) throws Exception {
        Http01Challenge challenge = auth.<Http01Challenge>findChallenge(Http01Challenge.TYPE).orElse(null);
        if (challenge == null) throw new AcmeException("HTTP-01 챌린지를 찾을 수 없습니다.");

        String token = challenge.getToken();
        String keyAuth = challenge.getAuthorization();

        log("HTTP-01 챌린지 서버 시작 (:" + challengePort + ")...");
        HttpServer srv = HttpServer.create(new InetSocketAddress(challengePort), 0);
        srv.createContext("/.well-known/acme-challenge/" + token, ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] resp = keyAuth.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.getResponseHeaders().add("Connection", "close");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
            ex.close();
        });
        srv.start();

        try {
            challenge.trigger();
            log("Let's Encrypt 검증 대기 중...");
            waitFor("도메인 검증",
                    () -> auth.getStatus() == Status.VALID,
                    auth::update,
                    auth.getStatus() == Status.INVALID
                            ? "도메인 검증 실패: " + auth.getIdentifier().getDomain()
                            : null);
            log("도메인 검증 성공!");
        } finally {
            srv.stop(0);
        }
    }

    /** 인증서가 30일 이내로 만료되는지 확인합니다. */
    public boolean needsRenewal() {
        if (!hasCertificate()) return false;
        try (InputStream is = new FileInputStream(getCertFile())) {
            X509Certificate cert = (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(is);
            Instant expiry = cert.getNotAfter().toInstant();
            return Instant.now().plus(RENEW_BEFORE_DAYS, ChronoUnit.DAYS).isAfter(expiry);
        } catch (Exception e) {
            return false;
        }
    }

    /** 인증서 만료일을 사람이 읽기 좋은 문자열로 반환합니다. */
    public String getCertExpiry() {
        if (!hasCertificate()) return "없음";
        try (InputStream is = new FileInputStream(getCertFile())) {
            X509Certificate cert = (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(is);
            return cert.getNotAfter().toString();
        } catch (Exception e) {
            return "읽기 실패";
        }
    }

    private KeyPair loadOrCreateKey(String filename) throws IOException {
        File keyFile = new File(dataFolder, filename);
        if (keyFile.exists()) {
            try (Reader r = new FileReader(keyFile)) {
                return KeyPairUtils.readKeyPair(r);
            }
        }
        KeyPair kp = KeyPairUtils.createKeyPair(2048);
        try (Writer w = new FileWriter(keyFile)) {
            KeyPairUtils.writeKeyPair(kp, w);
        }
        return kp;
    }

    @FunctionalInterface
    interface CheckedRunnable { void run() throws Exception; }

    private void waitFor(String what, java.util.function.BooleanSupplier done,
                         CheckedRunnable update, String failMsg) throws Exception {
        for (int i = 0; i < 20; i++) {
            if (done.getAsBoolean()) return;
            if (failMsg != null) throw new AcmeException(failMsg);
            Thread.sleep(3000);
            update.run();
        }
        throw new AcmeException(what + " 시간 초과");
    }

    private void log(String msg) {
        plugin.getLogger().info("[ACME] " + msg);
    }
}
