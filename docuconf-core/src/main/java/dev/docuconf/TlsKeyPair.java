package dev.docuconf;

import dev.docuconf.check.Pem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A TLS key pair mounted in the {@code kubernetes.io/tls} layout. docuconf checks it at startup. Every method
 * reads the files again, so after a rotation (see {@link Reload#WATCH}) the new certificate is returned.
 */
public final class TlsKeyPair {

    private final Path directory;

    /**
     * Creates a handle for a directory.
     *
     * @param directory the directory holding {@code tls.crt} and {@code tls.key}
     */
    public TlsKeyPair(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /**
     * The directory.
     *
     * @return the directory
     */
    public Path directory() {
        return directory;
    }

    /**
     * The certificate chain file, leaf first.
     *
     * @return {@code tls.crt}
     */
    public Path certificatePath() {
        return directory.resolve("tls.crt");
    }

    /**
     * The private key file.
     *
     * @return {@code tls.key}
     */
    public Path keyPath() {
        return directory.resolve("tls.key");
    }

    /**
     * The issuing CA file, when present.
     *
     * @return {@code ca.crt}
     */
    public Path caPath() {
        return directory.resolve("ca.crt");
    }

    /**
     * Reads the certificate chain.
     *
     * @return the chain, leaf first
     * @throws GeneralSecurityException if it does not parse
     */
    public List<X509Certificate> certificateChain() throws GeneralSecurityException {
        return Pem.certificates(read(certificatePath()));
    }

    /**
     * Reads the private key (PKCS#8, PKCS#1 or SEC1 PEM).
     *
     * @return the key
     * @throws GeneralSecurityException if it does not parse
     */
    public PrivateKey privateKey() throws GeneralSecurityException {
        return Pem.privateKey(read(keyPath()));
    }

    /**
     * Reads {@code ca.crt}.
     *
     * @return the CA certificates, empty when the file is absent
     * @throws GeneralSecurityException if it does not parse
     */
    public List<X509Certificate> caCertificates() throws GeneralSecurityException {
        return Files.exists(caPath()) ? Pem.certificates(read(caPath())) : List.of();
    }

    /**
     * An in-memory PKCS#12 key store holding the key pair under {@code alias}, with an empty password.
     *
     * @param alias the entry alias
     * @return the key store
     * @throws GeneralSecurityException if the files do not parse
     */
    public KeyStore keyStore(String alias) throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try {
            store.load(null, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<X509Certificate> chain = certificateChain();
        store.setKeyEntry(alias, privateKey(), new char[0], chain.toArray(new Certificate[0]));
        return store;
    }

    /**
     * An {@link SSLContext} serving this key pair and, when {@code ca.crt} is present, trusting it (for mutual
     * TLS); otherwise trusting the JDK defaults.
     *
     * @return a new context
     * @throws GeneralSecurityException if the files do not parse
     */
    public SSLContext sslContext() throws GeneralSecurityException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore("tls"), new char[0]);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        List<X509Certificate> cas = caCertificates();
        if (cas.isEmpty()) {
            tmf.init((KeyStore) null);
        } else {
            tmf.init(CaBundle.trustStore(cas));
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "TlsKeyPair[" + directory + "]";
    }
}
