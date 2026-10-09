package dev.docuconf.examples.orders;

import dev.docuconf.Docuconf;
import dev.docuconf.Redacted;
import dev.docuconf.Secret;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The key set of the payment webhooks (SPEC section 6.1). The prefix {@code webhook} makes {@code keys} the
 * environment variable {@code WEBHOOK_KEYS}, a comma-separated list, so one Kubernetes Secret key holds it.
 *
 * @param keys Keys that verify the signature on incoming payment webhooks
 *        <p>A webhook is accepted when it is signed with any key in the list, so the key can be rotated without
 *        turning webhooks away. To rotate:
 *        <ol>
 *          <li>add the new key as the second item, and roll out;</li>
 *          <li>switch the sender to the new key;</li>
 *          <li>remove the old key, and roll out.</li>
 *        </ol>
 *        <p>Each key is 32 to 256 characters, so an empty or truncated key fails at boot. Without this variable,
 *        the service rejects every webhook.
 */
@Docuconf(service = "orders")
@Validated
@ConfigurationProperties("webhook")
public record WebhookProperties(
        // @Secret: from a Secret only, never printed. @Size bounds the list to 1 or 2 keys, and the @Size on the
        // item type bounds each key, so an empty or truncated key fails startup with out_of_range.
        @Secret @Size(min = 1, max = 2) List<@Size(min = 32, max = 256) String> keys) {

    /** Prints the keys as [redacted]; a record's generated toString() would print them. */
    @Override
    public String toString() {
        return Redacted.toString(this);
    }
}
