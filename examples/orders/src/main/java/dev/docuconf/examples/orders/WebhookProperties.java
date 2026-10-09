package dev.docuconf.examples.orders;

import dev.docuconf.Docuconf;
import dev.docuconf.KeySet;
import dev.docuconf.KeySetLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The key set of the payment webhooks (SPEC section 6.1). The prefix {@code webhook} makes {@code keys} the
 * environment variable {@code WEBHOOK_KEYS}, a comma-separated list, so one Kubernetes Secret key holds it.
 *
 * @param keys Keys that verify the signature on incoming payment webhooks
 *        <p>A webhook is accepted when it is signed with any key in the set. Each key is 32 to 256 characters, so
 *        an empty or truncated key fails at boot. Without this variable, the service rejects every webhook.
 */
@Docuconf(service = "orders")
@Validated
@ConfigurationProperties("webhook")
public record WebhookProperties(
        // A KeySet is always secret: from a Secret only, never printed, not even by this record's toString().
        // 1 or 2 keys (the default), so one can be rotated; each key 32 to 256 characters, so an empty or
        // truncated key fails startup with out_of_range.
        @KeySetLimits(keyMinLength = 32, keyMaxLength = 256) KeySet keys) {
}
