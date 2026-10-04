package dev.docuconf.testing;

import dev.docuconf.check.Pem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Generates real keys and certificates for tests. */
public final class TestCerts {

    private static final AtomicLong SERIAL = new AtomicLong(System.currentTimeMillis());

    private TestCerts() {
    }

    /** A key pair and its certificate. */
    public record Issued(KeyPair keys, X509Certificate certificate) {
    }

    public static KeyPair rsa() {
        return generate("RSA", null);
    }

    public static KeyPair ec() {
        return generate("EC", new ECGenParameterSpec("secp256r1"));
    }

    public static KeyPair ed25519() {
        return generate("Ed25519", null);
    }

    private static KeyPair generate(String alg, java.security.spec.AlgorithmParameterSpec spec) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance(alg);
            if (spec != null) {
                g.initialize(spec);
            } else if (alg.equals("RSA")) {
                g.initialize(2048);
            }
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A CA certificate valid for a year. */
    public static Issued ca(String cn) {
        KeyPair keys = ec();
        Instant now = Instant.now();
        return new Issued(keys, build(keys, cn, null, keys.getPrivate(), now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(365)), true));
    }

    /** An intermediate CA certificate signed by {@code issuer}. */
    public static Issued intermediate(String cn, Issued issuer) {
        KeyPair keys = ec();
        Instant now = Instant.now();
        return new Issued(keys, build(keys, cn, issuer.certificate(), issuer.keys().getPrivate(),
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(200)), true));
    }

    /** A leaf certificate signed by {@code issuer}, or self-signed when it is null. */
    public static Issued leaf(KeyPair keys, Issued issuer, Instant notBefore, Instant notAfter, String... dns) {
        X509Certificate cert = issuer == null
                ? build(keys, "leaf", null, keys.getPrivate(), notBefore, notAfter, false, dns)
                : build(keys, "leaf", issuer.certificate(), issuer.keys().getPrivate(), notBefore, notAfter, false, dns);
        return new Issued(keys, cert);
    }

    /** A self-signed leaf valid from yesterday for {@code days} days. */
    public static Issued selfSigned(KeyPair keys, int days, String... dns) {
        Instant now = Instant.now();
        return leaf(keys, null, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(days)), dns);
    }

    private static X509Certificate build(KeyPair keys, String cn, X509Certificate issuer, PrivateKey signer,
            Instant notBefore, Instant notAfter, boolean ca, String... dns) {
        try {
            X500Name subject = new X500Name("CN=" + cn);
            X500Name issuerName = issuer == null ? subject : new X500Name(issuer.getSubjectX500Principal().getName());
            JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(issuerName,
                    BigInteger.valueOf(SERIAL.incrementAndGet()), Date.from(notBefore), Date.from(notAfter), subject,
                    keys.getPublic());
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            if (dns.length > 0) {
                GeneralName[] names = new GeneralName[dns.length];
                for (int i = 0; i < dns.length; i++) {
                    names[i] = dns[i].matches("[0-9.]+") ? new GeneralName(GeneralName.iPAddress, dns[i])
                            : new GeneralName(GeneralName.dNSName, dns[i]);
                }
                b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
            }
            String alg = switch (signer.getAlgorithm()) {
                case "RSA" -> "SHA256withRSA";
                case "EC" -> "SHA256withECDSA";
                default -> "Ed25519";
            };
            return new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder(alg).build(signer)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pem(X509Certificate... certs) {
        StringBuilder b = new StringBuilder();
        try {
            for (X509Certificate c : certs) {
                b.append(Pem.encode("CERTIFICATE", c.getEncoded()));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return b.toString();
    }

    public static String pkcs8(PrivateKey key) {
        return Pem.encode("PRIVATE KEY", key.getEncoded());
    }

    /** PKCS#1 for RSA, SEC1 for EC: the "traditional" forms cert-manager and openssl write. */
    public static String traditional(PrivateKey key) {
        try {
            PrivateKeyInfo info = PrivateKeyInfo.getInstance(key.getEncoded());
            byte[] inner = info.parsePrivateKey().toASN1Primitive().getEncoded();
            if (key.getAlgorithm().equals("RSA")) {
                return Pem.encode("RSA PRIVATE KEY", inner);
            }
            // SEC1 inside PKCS#8 usually omits the curve; add it as [0] so the file stands alone.
            org.bouncycastle.asn1.sec.ECPrivateKey sec1 = org.bouncycastle.asn1.sec.ECPrivateKey.getInstance(inner);
            org.bouncycastle.asn1.sec.ECPrivateKey full = new org.bouncycastle.asn1.sec.ECPrivateKey(256,
                    sec1.getKey(), sec1.getPublicKey(), info.getPrivateKeyAlgorithm().getParameters());
            return Pem.encode("EC PRIVATE KEY", full.getEncoded());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes tls.crt (leaf then intermediates), tls.key and, when given, ca.crt. */
    public static Path writeTls(Path dir, String keyPem, X509Certificate[] chain, X509Certificate ca) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("tls.crt"), pem(chain));
            Files.writeString(dir.resolve("tls.key"), keyPem);
            if (ca != null) {
                Files.writeString(dir.resolve("ca.crt"), pem(ca));
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] keystore(String type, Issued issued, String password) {
        try {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(null, null);
            ks.setKeyEntry("client", issued.keys().getPrivate(), password.toCharArray(),
                    new Certificate[] {issued.certificate()});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ks.store(out, password.toCharArray());
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
