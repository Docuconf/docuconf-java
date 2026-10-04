package dev.docuconf.check;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PEM decoding with the JDK alone: certificates, and private keys in PKCS#8 ({@code PRIVATE KEY}), PKCS#1
 * ({@code RSA PRIVATE KEY}) and SEC1 ({@code EC PRIVATE KEY}) form, which is what cert-manager and openssl write.
 */
public final class Pem {

    private static final Pattern BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----\\r?\\n(.*?)-----END \\1-----", Pattern.DOTALL);

    private static final byte[] RSA_OID = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01};
    private static final byte[] EC_OID = {0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01};

    private Pem() {
    }

    /** One PEM block. */
    public record Block(String type, byte[] der) {
    }

    /**
     * Decodes every PEM block in the text. Text outside blocks (such as openssl's "Bag Attributes") is ignored.
     *
     * @param text PEM text
     * @return the blocks, in order
     * @throws IllegalArgumentException if a block's body is not valid base64, or a block has headers
     */
    public static List<Block> blocks(String text) {
        List<Block> blocks = new ArrayList<>();
        Matcher m = BLOCK.matcher(text);
        while (m.find()) {
            String body = m.group(2);
            if (body.contains(":")) {
                throw new IllegalArgumentException("PEM block " + m.group(1) + " has headers (an encrypted key?)");
            }
            blocks.add(new Block(m.group(1), Base64.getMimeDecoder().decode(body.replaceAll("\\s", ""))));
        }
        return blocks;
    }

    /**
     * Parses every {@code CERTIFICATE} block.
     *
     * @param text PEM text
     * @return the certificates, in file order
     * @throws CertificateException if a block does not parse
     */
    public static List<X509Certificate> certificates(String text) throws CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certificates = new ArrayList<>();
        List<Block> blocks;
        try {
            blocks = blocks(text);
        } catch (IllegalArgumentException e) {
            throw new CertificateException(e.getMessage(), e);
        }
        for (Block block : blocks) {
            if (block.type().equals("CERTIFICATE")) {
                certificates.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(block.der())));
            }
        }
        return certificates;
    }

    /**
     * Parses the first private key block.
     *
     * @param text PEM text
     * @return the key
     * @throws GeneralSecurityException if there is no supported, unencrypted private key
     */
    public static PrivateKey privateKey(String text) throws GeneralSecurityException {
        List<Block> blocks;
        try {
            blocks = blocks(text);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException(e.getMessage(), e);
        }
        for (Block block : blocks) {
            switch (block.type()) {
                case "PRIVATE KEY":
                    return pkcs8(block.der());
                case "RSA PRIVATE KEY":
                    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1(block.der())));
                case "EC PRIVATE KEY":
                    return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(wrapSec1(block.der())));
                case "ENCRYPTED PRIVATE KEY":
                    throw new GeneralSecurityException("the private key is encrypted");
                default:
                    break;
            }
        }
        throw new GeneralSecurityException("no PEM private key found");
    }

    private static PrivateKey pkcs8(byte[] der) throws GeneralSecurityException {
        GeneralSecurityException last = null;
        for (String algorithm : new String[] {"RSA", "EC", "Ed25519", "EdDSA", "RSASSA-PSS", "Ed448"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        throw new GeneralSecurityException("unsupported PKCS#8 private key", last);
    }

    /** PrivateKeyInfo ::= SEQUENCE { version 0, AlgorithmIdentifier { rsaEncryption, NULL }, OCTET STRING pkcs1 }. */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] algorithm = Der.sequence(concat(RSA_OID, new byte[] {0x05, 0x00}));
        return Der.sequence(concat(new byte[] {0x02, 0x01, 0x00}, algorithm, Der.tlv(0x04, pkcs1)));
    }

    /**
     * SEC1 ECPrivateKey ::= SEQUENCE { version 1, privateKey OCTET STRING, [0] parameters OPTIONAL, [1] publicKey
     * OPTIONAL }. The curve OID in [0] moves into the PKCS#8 AlgorithmIdentifier.
     */
    private static byte[] wrapSec1(byte[] sec1) throws GeneralSecurityException {
        byte[] curve = null;
        Der.Reader outer = new Der.Reader(sec1);
        Der.Reader seq = outer.enter(0x30);
        while (seq.hasMore()) {
            int tag = seq.peekTag();
            byte[] value = seq.read(tag);
            if (tag == 0xa0) {
                curve = value;
            }
        }
        if (curve == null) {
            throw new GeneralSecurityException("the EC private key does not name its curve");
        }
        byte[] algorithm = Der.sequence(concat(EC_OID, curve));
        return Der.sequence(concat(new byte[] {0x02, 0x01, 0x00}, algorithm, Der.tlv(0x04, sec1)));
    }

    /**
     * Encodes DER as PEM.
     *
     * @param type the block type, such as {@code CERTIFICATE}
     * @param der the content
     * @return PEM text ending in a newline
     */
    public static String encode(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /** Just enough DER to rewrap keys. */
    static final class Der {

        private Der() {
        }

        static byte[] sequence(byte[] content) {
            return tlv(0x30, content);
        }

        static byte[] tlv(int tag, byte[] content) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(tag);
            int len = content.length;
            if (len < 0x80) {
                out.write(len);
            } else {
                int bytes = len > 0xffffff ? 4 : len > 0xffff ? 3 : len > 0xff ? 2 : 1;
                out.write(0x80 | bytes);
                for (int i = bytes - 1; i >= 0; i--) {
                    out.write((len >>> (8 * i)) & 0xff);
                }
            }
            out.writeBytes(content);
            return out.toByteArray();
        }

        static final class Reader {
            private final byte[] data;
            private int pos;
            private final int end;

            Reader(byte[] data) {
                this(data, 0, data.length);
            }

            private Reader(byte[] data, int start, int end) {
                this.data = data;
                this.pos = start;
                this.end = end;
            }

            boolean hasMore() {
                return pos < end;
            }

            int peekTag() {
                return data[pos] & 0xff;
            }

            /** Reads one element with the given tag and returns its content. */
            byte[] read(int tag) throws GeneralSecurityException {
                int[] bounds = header(tag);
                byte[] value = java.util.Arrays.copyOfRange(data, bounds[0], bounds[1]);
                pos = bounds[1];
                return value;
            }

            Reader enter(int tag) throws GeneralSecurityException {
                int[] bounds = header(tag);
                pos = bounds[1];
                return new Reader(data, bounds[0], bounds[1]);
            }

            private int[] header(int tag) throws GeneralSecurityException {
                if (pos + 2 > end || (data[pos] & 0xff) != tag) {
                    throw new GeneralSecurityException("malformed DER");
                }
                int p = pos + 1;
                int len = data[p++] & 0xff;
                if (len >= 0x80) {
                    int n = len & 0x7f;
                    if (n == 0 || n > 4 || p + n > end) {
                        throw new GeneralSecurityException("malformed DER length");
                    }
                    len = 0;
                    for (int i = 0; i < n; i++) {
                        len = (len << 8) | (data[p++] & 0xff);
                    }
                }
                if (len < 0 || p + len > end) {
                    throw new GeneralSecurityException("malformed DER length");
                }
                return new int[] {p, p + len};
            }
        }
    }
}
