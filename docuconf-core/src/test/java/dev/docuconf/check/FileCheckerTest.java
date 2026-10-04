package dev.docuconf.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.CaBundle;
import dev.docuconf.Keystore;
import dev.docuconf.TlsKeyPair;
import dev.docuconf.contract.FileSpec;
import dev.docuconf.contract.FileType;
import dev.docuconf.testing.TestCerts;
import dev.docuconf.testing.TestCerts.Issued;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCheckerTest {

    @TempDir
    Path tmp;

    private static final Instant NOW = Instant.now();

    private static FileSpec tls(boolean requireCA, String... dns) {
        FileSpec f = new FileSpec("serving-tls", FileType.TLS, "Serving certificate", "/etc/app/tls");
        f.required = true;
        f.requireCA = requireCA;
        f.dnsNames = dns.length == 0 ? null : List.of(dns);
        return f;
    }

    private static List<String> codes(FileChecker.Outcome o) {
        return o.violations().stream().map(v -> v.code().id()).collect(Collectors.toList());
    }

    private FileChecker.Outcome check(FileSpec f, Path p) {
        return FileChecker.check(f, p, NOW, k -> null);
    }

    @Test
    void validChainedKeyPair() throws Exception {
        Issued ca = TestCerts.ca("Test CA");
        Issued leaf = TestCerts.leaf(TestCerts.rsa(), ca, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(90)),
                "gw.internal", "*.example.com");
        Path dir = TestCerts.writeTls(tmp.resolve("tls"), TestCerts.pkcs8(leaf.keys().getPrivate()),
                new X509Certificate[] {leaf.certificate()}, ca.certificate());
        FileSpec f = tls(true, "gw.internal", "api.example.com");
        f.keyAlgorithms = List.of("RSA", "ECDSA");
        f.minRemaining = "720h";
        FileChecker.Outcome o = check(f, dir);
        assertEquals(List.of(), o.violations());
        TlsKeyPair pair = assertInstanceOf(TlsKeyPair.class, o.value());
        assertNotNull(pair.sslContext());
        assertEquals(1, pair.certificateChain().size());
    }

    @Test
    void traditionalKeyEncodingsParse() {
        for (KeyPair keys : new KeyPair[] {TestCerts.rsa(), TestCerts.ec()}) {
            Issued leaf = TestCerts.selfSigned(keys, 30, "a.internal");
            Path dir = TestCerts.writeTls(tmp.resolve("t-" + keys.getPrivate().getAlgorithm()),
                    TestCerts.traditional(keys.getPrivate()), new X509Certificate[] {leaf.certificate()}, null);
            assertEquals(List.of(), check(tls(false, "a.internal"), dir).violations(), keys.getPrivate().getAlgorithm());
        }
    }

    @Test
    void expiringCertificate() {
        Issued leaf = TestCerts.selfSigned(TestCerts.ec(), 10, "gw.internal");
        Path dir = TestCerts.writeTls(tmp.resolve("tls"), TestCerts.pkcs8(leaf.keys().getPrivate()),
                new X509Certificate[] {leaf.certificate()}, null);
        FileSpec f = tls(false);
        f.minRemaining = "720h";
        FileChecker.Outcome o = check(f, dir);
        assertEquals(List.of("certificate_expiring"), codes(o));
        assertTrue(o.violations().get(0).message().contains("at least 720h"), o.violations().toString());
    }

    @Test
    void expiredAndNotYetValid() {
        KeyPair keys = TestCerts.ec();
        Issued expired = TestCerts.leaf(keys, null, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(1)));
        Path dir = TestCerts.writeTls(tmp.resolve("expired"), TestCerts.pkcs8(keys.getPrivate()),
                new X509Certificate[] {expired.certificate()}, null);
        assertEquals(List.of("certificate_invalid"), codes(check(tls(false), dir)));

        Issued future = TestCerts.leaf(keys, null, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        Path dir2 = TestCerts.writeTls(tmp.resolve("future"), TestCerts.pkcs8(keys.getPrivate()),
                new X509Certificate[] {future.certificate()}, null);
        assertEquals(List.of("certificate_invalid"), codes(check(tls(false), dir2)));
    }

    @Test
    void dnsNameMismatch() {
        Issued leaf = TestCerts.selfSigned(TestCerts.ec(), 90, "other.internal", "*.example.com", "10.0.0.1");
        Path dir = TestCerts.writeTls(tmp.resolve("tls"), TestCerts.pkcs8(leaf.keys().getPrivate()),
                new X509Certificate[] {leaf.certificate()}, null);
        FileChecker.Outcome o = check(tls(false, "gw.internal", "a.example.com", "a.b.example.com", "10.0.0.1"), dir);
        assertEquals(List.of("certificate_name_mismatch", "certificate_name_mismatch"), codes(o));
        assertTrue(o.violations().get(0).message().contains("gw.internal"));
        assertTrue(o.violations().get(1).message().contains("a.b.example.com"), "a wildcard covers one label only");
    }

    @Test
    void keyMismatch() {
        Issued leaf = TestCerts.selfSigned(TestCerts.rsa(), 90);
        Path dir = TestCerts.writeTls(tmp.resolve("tls"), TestCerts.pkcs8(TestCerts.rsa().getPrivate()),
                new X509Certificate[] {leaf.certificate()}, null);
        assertEquals(List.of("key_mismatch"), codes(check(tls(false), dir)));

        Issued ec = TestCerts.selfSigned(TestCerts.ec(), 90);
        Path dir2 = TestCerts.writeTls(tmp.resolve("tls2"), TestCerts.pkcs8(TestCerts.ec().getPrivate()),
                new X509Certificate[] {ec.certificate()}, null);
        assertEquals(List.of("key_mismatch"), codes(check(tls(false), dir2)));

        Path dir3 = TestCerts.writeTls(tmp.resolve("tls3"), "not a key\n",
                new X509Certificate[] {ec.certificate()}, null);
        assertEquals(List.of("key_mismatch"), codes(check(tls(false), dir3)));
    }

    @Test
    void disallowedKeyAlgorithm() {
        Issued leaf = TestCerts.selfSigned(TestCerts.ed25519(), 90);
        Path dir = TestCerts.writeTls(tmp.resolve("tls"), TestCerts.pkcs8(leaf.keys().getPrivate()),
                new X509Certificate[] {leaf.certificate()}, null);
        FileSpec f = tls(false);
        f.keyAlgorithms = List.of("RSA", "ECDSA");
        FileChecker.Outcome o = check(f, dir);
        assertEquals(List.of("certificate_invalid"), codes(o));
        assertTrue(o.violations().get(0).message().contains("Ed25519"));
        f.keyAlgorithms = List.of("Ed25519");
        assertEquals(List.of(), check(f, dir).violations());
    }

    @Test
    void chainToTheWrongCa() {
        Issued ca = TestCerts.ca("Real CA");
        Issued other = TestCerts.ca("Other CA");
        Issued leaf = TestCerts.leaf(TestCerts.ec(), ca, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(90)));
        Path dir = TestCerts.writeTls(tmp.resolve("tls"), TestCerts.pkcs8(leaf.keys().getPrivate()),
                new X509Certificate[] {leaf.certificate()}, other.certificate());
        FileChecker.Outcome o = check(tls(true), dir);
        assertEquals(List.of("certificate_invalid"), codes(o));
        assertTrue(o.violations().get(0).message().contains("does not chain to ca.crt"));
    }

    @Test
    void chainThroughAnIntermediateInAnyOrder() {
        Issued root = TestCerts.ca("Root");
        Issued intermediate = TestCerts.intermediate("Intermediate", root);
        Issued leaf = TestCerts.leaf(TestCerts.ec(), intermediate, NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(90)));
        Path dir = TestCerts.writeTls(tmp.resolve("tls"), TestCerts.pkcs8(leaf.keys().getPrivate()),
                new X509Certificate[] {leaf.certificate(), intermediate.certificate()}, root.certificate());
        assertEquals(List.of(), check(tls(true), dir).violations());

        // Leaf only: the intermediate is missing, so there is no path to the root.
        Path dir2 = TestCerts.writeTls(tmp.resolve("tls2"), TestCerts.pkcs8(leaf.keys().getPrivate()),
                new X509Certificate[] {leaf.certificate()}, root.certificate());
        assertEquals(List.of("certificate_invalid"), codes(check(tls(true), dir2)));
    }

    @Test
    void missingFiles() {
        assertEquals(List.of("file_missing"), codes(check(tls(false), tmp.resolve("nope"))));
        FileSpec optional = tls(false);
        optional.required = false;
        FileChecker.Outcome o = check(optional, tmp.resolve("nope"));
        assertEquals(List.of(), o.violations());
        assertEquals(false, o.present());

        Issued leaf = TestCerts.selfSigned(TestCerts.ec(), 90);
        Path dir = TestCerts.writeTls(tmp.resolve("tls"), TestCerts.pkcs8(leaf.keys().getPrivate()),
                new X509Certificate[] {leaf.certificate()}, null);
        FileChecker.Outcome noCa = check(tls(true), dir);
        assertEquals(List.of("file_missing"), codes(noCa));
        assertTrue(noCa.violations().get(0).message().contains("ca.crt"));
    }

    @Test
    void caBundle() throws Exception {
        FileSpec f = new FileSpec("trusted", FileType.CA_BUNDLE, "Trusted CAs", "/etc/app/ca/bundle.pem");
        Path p = tmp.resolve("bundle.pem");
        Files.writeString(p, "# comment\n" + TestCerts.pem(TestCerts.ca("A").certificate(), TestCerts.ca("B").certificate()));
        FileChecker.Outcome ok = check(f, p);
        assertEquals(List.of(), ok.violations());
        assertEquals(2, assertInstanceOf(CaBundle.class, ok.value()).certificates().size());
        f.minCertificates = 3;
        assertEquals(List.of("file_malformed"), codes(check(f, p)));
        Files.writeString(p, "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n");
        f.minCertificates = 1;
        assertEquals(List.of("file_malformed"), codes(check(f, p)));
    }

    @Test
    void keystore() throws Exception {
        FileSpec f = new FileSpec("partner", FileType.KEYSTORE, "Partner client cert", "/etc/app/p/ks.p12");
        f.format = "pkcs12";
        f.passwordVar = "KS_PASSWORD";
        Path p = tmp.resolve("ks.p12");
        Files.write(p, TestCerts.keystore("PKCS12", TestCerts.selfSigned(TestCerts.ec(), 30), "hunter2"));
        FileChecker.Outcome ok = FileChecker.check(f, p, NOW, Map.of("KS_PASSWORD", "hunter2")::get);
        assertEquals(List.of(), ok.violations());
        assertNotNull(assertInstanceOf(Keystore.class, ok.value()).load("hunter2".toCharArray()));
        FileChecker.Outcome bad = FileChecker.check(f, p, NOW, Map.of("KS_PASSWORD", "wrong-pass")::get);
        assertEquals(List.of("keystore_unreadable"), codes(bad));
        assertTrue(!bad.violations().get(0).message().contains("wrong-pass"));

        f.format = "jks";
        Files.write(p, TestCerts.keystore("JKS", TestCerts.selfSigned(TestCerts.rsa(), 30), "hunter2"));
        assertEquals(List.of(), FileChecker.check(f, p, NOW, Map.of("KS_PASSWORD", "hunter2")::get).violations());
    }

    @Test
    void textUsesRe2EndOfText() throws Exception {
        FileSpec f = new FileSpec("license", FileType.TEXT, "Licence key", "/etc/app/license/key");
        Path p = tmp.resolve("license.key");
        Files.writeString(p, "ABCD\n");
        f.pattern = "^[A-Z]{4}$";
        // In RE2 (and CUE), $ does not match before a trailing newline; in plain Java it would.
        assertEquals(List.of("pattern_mismatch"), codes(check(f, p)));
        f.pattern = "^[A-Z]{4}\\n?$";
        FileChecker.Outcome ok = check(f, p);
        assertEquals(List.of(), ok.violations());
        assertEquals("ABCD\n", ok.value());
        f.maxLength = 3;
        assertEquals(List.of("out_of_range"), codes(check(f, p)));
    }

    @Test
    void sizeLimit() throws Exception {
        FileSpec f = new FileSpec("geoip", FileType.BINARY, "GeoIP database", "/data/geoip/db.mmdb");
        f.maxSize = 4L;
        Path p = tmp.resolve("db.mmdb");
        Files.write(p, new byte[] {1, 2, 3, 4, 5});
        assertEquals(List.of("file_too_large"), codes(check(f, p)));
        f.maxSize = 5L;
        assertEquals(p, check(f, p).value());
    }

    @Test
    void resolveHonoursPathEnvAndFileRoot() {
        FileSpec f = new FileSpec("ca", FileType.CA_BUNDLE, "Trusted CAs", "/etc/app/ca/bundle.pem");
        f.pathEnv = "SSL_CERT_FILE";
        assertEquals(Path.of("/etc/app/ca/bundle.pem"), FileChecker.resolve(f, k -> null));
        assertEquals(Path.of("/dev/root/etc/app/ca/bundle.pem"),
                FileChecker.resolve(f, Map.of("DOCUCONF_FILE_ROOT", "/dev/root")::get));
        assertEquals(Path.of("/dev/root/other/ca.pem"), FileChecker.resolve(f,
                Map.of("DOCUCONF_FILE_ROOT", "/dev/root", "SSL_CERT_FILE", "/other/ca.pem")::get));
    }
}
