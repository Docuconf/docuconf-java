package dev.docuconf.examples.orders;

import dev.docuconf.KeySet;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Checks the signature on incoming payment webhooks against the key set in {@code WEBHOOK_KEYS}. */
public final class Webhooks {

    /** The largest body {@code POST /webhooks/payments} reads. */
    public static final int MAX_BODY = 1 << 20;

    private Webhooks() {
    }

    /**
     * Reports whether {@code signature}, the hex-encoded HMAC-SHA256 of {@code body}, was made with any of
     * {@code keys}. Accepting every key in the set is what lets a key be rotated: during the overlap the old and the
     * new key both work. {@link KeySet#anyMatch} tries every key, so the time taken does not say which one matched.
     *
     * @param keys the key set; null accepts nothing
     * @param body the request body
     * @param signature the {@code X-Signature} header, or null
     * @return whether any key made the signature
     */
    public static boolean verify(KeySet keys, byte[] body, String signature) {
        if (keys == null || signature == null) {
            return false;
        }
        byte[] got;
        try {
            got = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        // Every key is tried; MessageDigest.isEqual compares in constant time.
        return keys.anyMatch(key -> MessageDigest.isEqual(hmac(key, body), got));
    }

    static byte[] hmac(String key, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
