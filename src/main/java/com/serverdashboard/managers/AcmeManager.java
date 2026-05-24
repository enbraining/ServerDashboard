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

    public void issueCertificate(String domain, String email, int challengePort) throws Exception {
        log("Certificate issuance started: " + domain + " (challenge port: " + challengePort + ")");

        KeyPair accountKey = loadOrCreateKey("acme-account.key");
        KeyPair domainKey  = loadOrCreateKey("domain.key");

        Session session = new Session(ACME_URL);

        log("Preparing Let's Encrypt account...");
        Account account = new AccountBuilder()
                .agreeToTermsOfService()
                .useKeyPair(accountKey)
                .addEmail(email)
                .createLogin(session)
                .getAccount();

        log("Creating certificate order...");
        Order order = account.newOrder().domains(domain).create();

        for (Authorization auth : order.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) continue;
            processChallenge(auth, challengePort);
        }

        log("Generating CSR and finalizing order...");
        CSRBuilder csrb = new CSRBuilder();
        csrb.addDomain(domain);
        csrb.sign(domainKey);
        order.execute(csrb.getEncoded());

        waitFor("order", () -> order.getStatus() == Status.VALID, order::update,
                order.getStatus() == Status.INVALID ? "Order failed" : null);

        log("Downloading certificate...");
        Certificate cert = order.getCertificate();
        try (Writer fw = new FileWriter(getCertFile())) {
            cert.writeCertificate(fw);
        }
        byte[] pkcs8Der = domainKey.getPrivate().getEncoded();
        String pkcs8Pem = "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pkcs8Der)
                + "\n-----END PRIVATE KEY-----\n";
        try (Writer fw = new FileWriter(getKeyFile())) {
            fw.write(pkcs8Pem);
        }

        log("Certificate issued! " + getCertFile().getPath());
    }

    private void processChallenge(Authorization auth, int challengePort) throws Exception {
        Http01Challenge challenge = auth.<Http01Challenge>findChallenge(Http01Challenge.TYPE).orElse(null);
        if (challenge == null) throw new AcmeException("HTTP-01 challenge not found.");

        String token = challenge.getToken();
        String keyAuth = challenge.getAuthorization();

        log("Starting HTTP-01 challenge server on port " + challengePort + "...");
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
            log("Waiting for Let's Encrypt validation...");
            waitFor("domain validation",
                    () -> auth.getStatus() == Status.VALID,
                    auth::update,
                    auth.getStatus() == Status.INVALID
                            ? "Domain validation failed: " + auth.getIdentifier().getDomain()
                            : null);
            log("Domain validation successful!");
        } finally {
            srv.stop(0);
        }
    }

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

    public String getCertExpiry() {
        if (!hasCertificate()) return "N/A";
        try (InputStream is = new FileInputStream(getCertFile())) {
            X509Certificate cert = (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(is);
            return cert.getNotAfter().toString();
        } catch (Exception e) {
            return "Read failed";
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
        throw new AcmeException(what + " timed out");
    }

    private void log(String msg) {
        plugin.getLogger().info("[ACME] " + msg);
    }
}
