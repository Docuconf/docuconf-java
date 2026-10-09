package dev.docuconf.examples.orders;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The orders service. docuconf checks the environment against the contract before Spring binds
 * {@link OrdersProperties}, so a bad value stops startup with every problem listed.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@RestController
public class OrdersApplication {

    private final OrdersProperties config;
    private final WebhookProperties webhook;

    OrdersApplication(OrdersProperties config, WebhookProperties webhook) {
        this.config = config;
        this.webhook = webhook;
    }

    /**
     * Starts the service.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }

    @GetMapping("/healthz")
    String healthz() {
        return "ok";
    }

    /** The typed configuration, with the secrets redacted. */
    @GetMapping("/config")
    Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("port", config.port());
        out.put("logLevel", config.logLevel());
        out.put("databaseUrl", "***");
        out.put("allowedOrigins", config.allowedOrigins());
        out.put("requestTimeout", config.requestTimeout());
        out.put("workerCount", config.workerCount());
        out.put("webhookKeys", "***"); // a KeySet is always secret, set or not
        return out;
    }

    /**
     * Payment webhooks, signed with any key in {@code WEBHOOK_KEYS}. CONFIG.md, generated from the contract, says how
     * to rotate a key.
     */
    @PostMapping("/webhooks/payments")
    ResponseEntity<Void> paymentWebhook(InputStream in,
            @RequestHeader(name = "X-Signature", required = false) String signature) throws IOException {
        byte[] body = in.readNBytes(Webhooks.MAX_BODY + 1);
        if (body.length > Webhooks.MAX_BODY) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        boolean ok = Webhooks.verify(webhook.keys(), body, signature);
        return ResponseEntity.status(ok ? HttpStatus.NO_CONTENT : HttpStatus.UNAUTHORIZED).build();
    }
}
