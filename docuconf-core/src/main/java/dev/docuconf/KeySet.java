package dev.docuconf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A key set (SPEC §4.3): secret keys that are all valid at once, so one can be rotated without an outage. It is
 * for the side that verifies: webhook signatures, inbound API keys, HMAC-signed tokens or cookies. Declare a
 * property of this type and docuconf exports it as {@code type: "keySet"}, always secret:
 *
 * <pre>{@code
 * @ConfigurationProperties("webhook")
 * public record WebhookProperties(@KeySetLimits(keyMinLength = 32, keyMaxLength = 256) KeySet keys) {
 * }
 *
 * boolean ok = props.keys().anyMatch(key -> MessageDigest.isEqual(hmac(key, body), signature));
 * }</pre>
 *
 * <p>The platform renders it as a list of strings ({@code old,new} during a rotation); keys are never trimmed, and
 * an empty key is an error at boot. The keys keep the order the platform gave them. {@link #toString()} never
 * prints them.
 */
public final class KeySet implements Iterable<String> {

    private final List<String> keys;

    private KeySet(List<String> keys) {
        this.keys = keys;
    }

    /**
     * A key set holding keys, in order.
     *
     * @param keys the keys, none {@code null}
     * @return the key set
     */
    public static KeySet of(Collection<String> keys) {
        List<String> copy = new ArrayList<>(keys.size());
        for (String k : keys) {
            copy.add(Objects.requireNonNull(k, "a key set holds no null key"));
        }
        return new KeySet(Collections.unmodifiableList(copy));
    }

    /**
     * A key set holding keys, in order.
     *
     * @param keys the keys, none {@code null}
     * @return the key set
     */
    public static KeySet of(String... keys) {
        return of(List.of(keys));
    }

    /**
     * The keys, in the order the platform gave them. During a rotation that is usually the old key, then the new
     * one.
     *
     * @return an unmodifiable list
     */
    public List<String> keys() {
        return keys;
    }

    /**
     * The number of keys.
     *
     * @return the count
     */
    public int size() {
        return keys.size();
    }

    /**
     * Whether a candidate equals one of the keys, such as an inbound API key. Every key is compared, and each
     * comparison takes the same time whatever the candidate, so the time taken says neither which key matched nor
     * how much of one did.
     *
     * @param candidate the value to look for; {@code null} matches nothing
     * @return whether it is one of the keys
     */
    public boolean contains(String candidate) {
        if (candidate == null) {
            return false;
        }
        byte[] want = sha256(candidate);
        boolean found = false;
        for (String key : keys) {
            found = MessageDigest.isEqual(sha256(key), want) | found;
        }
        return found;
    }

    /**
     * Runs a check the caller supplies against every key, such as comparing an HMAC made with the key to a
     * signature, and reports whether any key passed. It never stops at the first match, so the time taken does not
     * say which key matched; the check itself should compare in constant time ({@link MessageDigest#isEqual}).
     *
     * @param check the check, called once per key, in order
     * @return whether the check passed for at least one key
     */
    public boolean anyMatch(Predicate<String> check) {
        Objects.requireNonNull(check, "check");
        boolean found = false;
        for (String key : keys) {
            found = check.test(key) | found;
        }
        return found;
    }

    /** Iterates over the keys, in order. */
    @Override
    public Iterator<String> iterator() {
        return keys.iterator();
    }

    /**
     * Never the keys: {@code KeySet[2 keys, [redacted]]}.
     *
     * @return the text
     */
    @Override
    public String toString() {
        return "KeySet[" + keys.size() + (keys.size() == 1 ? " key, " : " keys, ") + Redacted.MARK + "]";
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
