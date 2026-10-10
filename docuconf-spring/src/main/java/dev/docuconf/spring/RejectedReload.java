package dev.docuconf.spring;

import java.time.Instant;
import java.util.List;

/**
 * A change to a watched file input that failed its checks, so the previous value stayed current (SPEC §4.6.2). It
 * names the violation codes, never the content.
 *
 * @param time when the change was rejected
 * @param input the input's name in the contract
 * @param codes the violation codes, such as {@code keystore_unreadable} or {@code certificate_expiring}
 */
public record RejectedReload(Instant time, String input, List<String> codes) {

    /**
     * Creates the record.
     *
     * @param time when the change was rejected
     * @param input the input's name
     * @param codes the violation codes
     */
    public RejectedReload {
        codes = List.copyOf(codes);
    }
}
