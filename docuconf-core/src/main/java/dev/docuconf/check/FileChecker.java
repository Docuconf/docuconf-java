package dev.docuconf.check;

import dev.docuconf.CaBundle;
import dev.docuconf.Keystore;
import dev.docuconf.TlsKeyPair;
import dev.docuconf.contract.FileSpec;
import dev.docuconf.contract.FileType;
import dev.docuconf.contract.GoDuration;
import dev.docuconf.contract.Re2;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.security.interfaces.EdECKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Checks file inputs at startup (SPEC §11.2 item 7): presence, readability, size, and for each type what the
 * platform could not see before deploy. Uses only the JDK's {@code java.security} and {@code javax.net.ssl}.
 */
public final class FileChecker {

    /** The variable that prefixes every absolute file path, for local development and tests. */
    public static final String FILE_ROOT_ENV = "DOCUCONF_FILE_ROOT";

    private static final String UNREADABLE_HINT = " is not readable by this process. Secret volumes are owned by"
            + " root with mode 0400; a non-root container needs the pod's securityContext.fsGroup set.";

    private FileChecker() {
    }

    /**
     * The result of checking one file input.
     *
     * @param violations problems found, empty when the input is usable
     * @param path the resolved location
     * @param present whether the file (or TLS directory) exists
     * @param value what the app receives: a {@link TlsKeyPair}, {@link CaBundle}, {@link Keystore}, the text of a
     *     text file, the {@link Path} of a binary file, or the bytes of a config file; {@code null} when absent or
     *     invalid
     */
    public record Outcome(List<Violation> violations, Path path, boolean present, Object value) {
    }

    /**
     * Where a file input is read from: the {@code pathEnv} variable when it is set, else the declared path, under
     * {@code DOCUCONF_FILE_ROOT} when that is set and the path is absolute.
     *
     * @param f the input
     * @param env looks up environment variables
     * @return the location
     */
    public static Path resolve(FileSpec f, Function<String, String> env) {
        String p = f.path;
        if (f.pathEnv != null) {
            String fromEnv = env.apply(f.pathEnv);
            if (fromEnv != null && !fromEnv.isEmpty()) {
                p = fromEnv;
            }
        }
        String root = env.apply(FILE_ROOT_ENV);
        if (root != null && !root.isEmpty() && p.startsWith("/")) {
            return Path.of(root, p.substring(1));
        }
        return Path.of(p);
    }

    /**
     * Checks one file input.
     *
     * @param f the input
     * @param path where it is, from {@link #resolve(FileSpec, Function)}
     * @param now the current time, for certificate validity
     * @param env looks up variables, for a keystore's password
     * @return the outcome
     */
    public static Outcome check(FileSpec f, Path path, Instant now, Function<String, String> env) {
        List<Violation> out = new ArrayList<>();
        String n = f.name;
        boolean dir = f.type == FileType.TLS;
        boolean exists = dir ? Files.isDirectory(path) : Files.isRegularFile(path);
        if (!exists) {
            if (f.required) {
                String what = dir ? "directory " : "";
                out.add(new Violation(Code.FILE_MISSING, n, what + path + " does not exist"));
            }
            return new Outcome(out, path, false, null);
        }
        Object value = null;
        try {
            if (!dir && f.maxSize != null && Files.size(path) > f.maxSize) {
                out.add(new Violation(Code.FILE_TOO_LARGE, n,
                        path + " is " + Files.size(path) + " bytes; the limit is " + f.maxSize));
                return new Outcome(out, path, true, null);
            }
            if (!dir && !Files.isReadable(path)) {
                out.add(new Violation(Code.FILE_UNREADABLE, n, path + UNREADABLE_HINT));
                return new Outcome(out, path, true, null);
            }
            value = switch (f.type) {
                case TLS -> tls(f, path, now, out);
                case CA_BUNDLE -> caBundle(f, path, out);
                case KEYSTORE -> keystore(f, path, env, out);
                case TEXT -> text(f, path, out);
                case BINARY -> path;
                case CONFIG -> Files.readAllBytes(path);
            };
        } catch (AccessDeniedException e) {
            out.add(new Violation(Code.FILE_UNREADABLE, n, e.getFile() + UNREADABLE_HINT));
        } catch (IOException e) {
            out.add(new Violation(Code.FILE_UNREADABLE, n, path + " could not be read: " + e.getMessage()));
        }
        return new Outcome(out, path, true, out.isEmpty() ? value : null);
    }

    private static String text(FileSpec f, Path path, List<Violation> out) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
        } catch (CharacterCodingException e) {
            out.add(new Violation(Code.FILE_MALFORMED, f.name, path + " is not valid UTF-8 text"));
            return null;
        }
        int len = text.codePointCount(0, text.length());
        if (f.minLength != null && len < f.minLength) {
            out.add(new Violation(Code.OUT_OF_RANGE, f.name,
                    path + " is shorter than " + f.minLength + " characters"));
        }
        if (f.maxLength != null && len > f.maxLength) {
            out.add(new Violation(Code.OUT_OF_RANGE, f.name, path + " is longer than " + f.maxLength + " characters"));
        }
        if (f.pattern != null && !Re2.compile(f.pattern).matcher(text).find()) {
            out.add(new Violation(Code.PATTERN_MISMATCH, f.name, path + " does not match " + f.pattern));
        }
        return text;
    }

    private static CaBundle caBundle(FileSpec f, Path path, List<Violation> out) throws IOException {
        List<X509Certificate> certs;
        try {
            certs = Pem.certificates(Files.readString(path, StandardCharsets.ISO_8859_1));
        } catch (GeneralSecurityException e) {
            out.add(new Violation(Code.FILE_MALFORMED, f.name, path + " contains a malformed certificate"));
            return null;
        }
        int min = f.minCertificates == null ? 1 : f.minCertificates;
        if (certs.size() < min) {
            out.add(new Violation(Code.FILE_MALFORMED, f.name,
                    path + " holds " + certs.size() + " PEM certificates; at least " + min + " required"));
        }
        return new CaBundle(path);
    }

    private static Keystore keystore(FileSpec f, Path path, Function<String, String> env, List<Violation> out)
            throws IOException {
        String type = "jks".equalsIgnoreCase(f.format) ? "JKS" : "PKCS12";
        String password = f.passwordVar == null ? null : env.apply(f.passwordVar);
        Keystore ks = new Keystore(path, type);
        try {
            KeyStore store = KeyStore.getInstance(type);
            store.load(new ByteArrayInputStream(Files.readAllBytes(path)),
                    password == null ? null : password.toCharArray());
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            String with = f.passwordVar == null ? "without a password" : "with the password in " + f.passwordVar;
            out.add(new Violation(Code.KEYSTORE_UNREADABLE, f.name,
                    path + " is not a " + type + " keystore that opens " + with));
            return null;
        }
        return ks;
    }

    private static TlsKeyPair tls(FileSpec f, Path dir, Instant now, List<Violation> out) throws IOException {
        String n = f.name;
        Path certPath = dir.resolve("tls.crt");
        Path keyPath = dir.resolve("tls.key");
        Path caPath = dir.resolve("ca.crt");
        List<Path> needed = f.requireCA ? List.of(certPath, keyPath, caPath) : List.of(certPath, keyPath);
        for (Path p : needed) {
            if (!Files.isRegularFile(p)) {
                out.add(new Violation(Code.FILE_MISSING, n, p + " does not exist"));
                return null;
            }
            if (!Files.isReadable(p)) {
                out.add(new Violation(Code.FILE_UNREADABLE, n, p + UNREADABLE_HINT));
                return null;
            }
        }
        List<X509Certificate> chain;
        try {
            chain = Pem.certificates(Files.readString(certPath, StandardCharsets.ISO_8859_1));
        } catch (GeneralSecurityException e) {
            out.add(new Violation(Code.CERTIFICATE_INVALID, n, certPath + " contains a malformed certificate"));
            return null;
        }
        if (chain.isEmpty()) {
            out.add(new Violation(Code.CERTIFICATE_INVALID, n, certPath + " holds no PEM certificate"));
            return null;
        }
        X509Certificate leaf = chain.get(0);

        PrivateKey key = null;
        try {
            key = Pem.privateKey(Files.readString(keyPath, StandardCharsets.ISO_8859_1));
        } catch (GeneralSecurityException e) {
            out.add(new Violation(Code.KEY_MISMATCH, n,
                    keyPath + " is not a supported PEM private key (" + e.getMessage() + ")"));
        }
        if (key != null && !matches(key, leaf.getPublicKey())) {
            out.add(new Violation(Code.KEY_MISMATCH, n, keyPath + " is not the private key of the certificate"));
        }

        Instant notBefore = leaf.getNotBefore().toInstant();
        Instant notAfter = leaf.getNotAfter().toInstant();
        if (now.isBefore(notBefore)) {
            out.add(new Violation(Code.CERTIFICATE_INVALID, n, "the certificate is not valid until " + notBefore));
        } else if (now.isAfter(notAfter)) {
            out.add(new Violation(Code.CERTIFICATE_INVALID, n, "the certificate expired at " + notAfter));
        } else if (f.minRemaining != null) {
            Duration min = GoDuration.parse(f.minRemaining);
            Duration left = Duration.between(now, notAfter);
            if (left.compareTo(min) < 0) {
                out.add(new Violation(Code.CERTIFICATE_EXPIRING, n, "the certificate expires at " + notAfter + ", "
                        + GoDuration.format(left.withNanos(0)) + " from now; at least " + f.minRemaining
                        + " must remain"));
            }
        }

        if (f.dnsNames != null) {
            Set<String> dns = new HashSet<>();
            Set<String> ips = new HashSet<>();
            sans(leaf, dns, ips);
            for (String name : f.dnsNames) {
                if (!covers(name, dns, ips)) {
                    out.add(new Violation(Code.CERTIFICATE_NAME_MISMATCH, n, "the certificate does not cover "
                            + name + " (subject alternative names: " + String.join(", ", sorted(dns, ips)) + ")"));
                }
            }
        }

        if (f.keyAlgorithms != null && !f.keyAlgorithms.isEmpty()) {
            String algorithm = keyAlgorithm(leaf.getPublicKey());
            if (!f.keyAlgorithms.contains(algorithm)) {
                out.add(new Violation(Code.CERTIFICATE_INVALID, n, "the certificate uses a " + algorithm
                        + " key; allowed: " + String.join(", ", f.keyAlgorithms)));
            }
        }

        if (f.requireCA) {
            String problem = chainProblem(chain, caPath, now);
            if (problem != null) {
                out.add(new Violation(Code.CERTIFICATE_INVALID, n, "the certificate does not chain to ca.crt: "
                        + problem));
            }
        }
        return new TlsKeyPair(dir);
    }

    /**
     * The contract name of a public key's algorithm.
     *
     * @param key the key
     * @return {@code RSA}, {@code ECDSA}, {@code Ed25519}, or the JDK's name for anything else
     */
    public static String keyAlgorithm(PublicKey key) {
        String a = key.getAlgorithm();
        if (a.equals("RSA")) {
            return "RSA";
        }
        if (a.equals("EC")) {
            return "ECDSA";
        }
        if (key instanceof EdECKey ed) {
            return ed.getParams().getName().equalsIgnoreCase("Ed25519") ? "Ed25519" : ed.getParams().getName();
        }
        return a;
    }

    private static boolean matches(PrivateKey key, PublicKey pub) {
        try {
            if (key instanceof RSAPrivateCrtKey rsa && pub instanceof RSAPublicKey rpub) {
                return rsa.getModulus().equals(rpub.getModulus())
                        && rsa.getPublicExponent().equals(rpub.getPublicExponent());
            }
            String alg = switch (keyAlgorithm(pub)) {
                case "RSA" -> "SHA256withRSA";
                case "ECDSA" -> "SHA256withECDSA";
                case "Ed25519" -> "Ed25519";
                case "Ed448" -> "Ed448";
                default -> null;
            };
            if (alg == null) {
                return false;
            }
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            Signature signer = Signature.getInstance(alg);
            signer.initSign(key);
            signer.update(challenge);
            byte[] sig = signer.sign();
            Signature verifier = Signature.getInstance(alg);
            verifier.initVerify(pub);
            verifier.update(challenge);
            return verifier.verify(sig);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    private static void sans(X509Certificate cert, Set<String> dns, Set<String> ips) {
        try {
            Collection<List<?>> names = cert.getSubjectAlternativeNames();
            if (names == null) {
                return;
            }
            for (List<?> entry : names) {
                int type = (Integer) entry.get(0);
                if (type == 2) {
                    dns.add(((String) entry.get(1)).toLowerCase(Locale.ROOT));
                } else if (type == 7) {
                    ips.add(normalizeIp((String) entry.get(1)));
                }
            }
        } catch (java.security.cert.CertificateParsingException e) {
            // No usable SANs; every name check fails.
        }
    }

    private static boolean covers(String name, Set<String> dns, Set<String> ips) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (isIpLiteral(lower)) {
            return ips.contains(normalizeIp(lower));
        }
        if (dns.contains(lower)) {
            return true;
        }
        int dot = lower.indexOf('.');
        return dot > 0 && !lower.startsWith("*.") && dns.contains("*" + lower.substring(dot));
    }

    private static boolean isIpLiteral(String s) {
        return s.matches("[0-9.]+") || s.contains(":");
    }

    private static String normalizeIp(String s) {
        try {
            return InetAddress.getByName(s).getHostAddress();
        } catch (IOException e) {
            return s;
        }
    }

    private static List<String> sorted(Set<String> dns, Set<String> ips) {
        List<String> all = new ArrayList<>(dns);
        all.addAll(ips);
        all.sort(null);
        return all.isEmpty() ? List.of("none") : all;
    }

    private static String chainProblem(List<X509Certificate> chain, Path caPath, Instant now) throws IOException {
        List<X509Certificate> cas;
        try {
            cas = Pem.certificates(Files.readString(caPath, StandardCharsets.ISO_8859_1));
        } catch (GeneralSecurityException e) {
            return "ca.crt contains a malformed certificate";
        }
        if (cas.isEmpty()) {
            return "ca.crt holds no PEM certificate";
        }
        X509Certificate leaf = chain.get(0);
        for (X509Certificate ca : cas) {
            if (ca.equals(leaf)) {
                return null;
            }
        }
        try {
            Set<TrustAnchor> anchors = new HashSet<>();
            for (X509Certificate ca : cas) {
                anchors.add(new TrustAnchor(ca, null));
            }
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(leaf);
            PKIXBuilderParameters params = new PKIXBuilderParameters(anchors, target);
            params.setRevocationEnabled(false);
            params.setDate(Date.from(now));
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
            CertPathBuilder.getInstance("PKIX").build(params);
            return null;
        } catch (GeneralSecurityException e) {
            String msg = e.getMessage();
            return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
        }
    }
}
