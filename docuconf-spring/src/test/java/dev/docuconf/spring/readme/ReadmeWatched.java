package dev.docuconf.spring.readme;

import dev.docuconf.CaBundle;
import dev.docuconf.TlsKeyPair;
import dev.docuconf.spring.DocuconfFileReloadedEvent;
import dev.docuconf.spring.DocuconfFiles;
import dev.docuconf.spring.ReloadStatus;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** The README's "Using a watched value" snippets, compiled so they stay correct (ReadmeSnippetsTest finds them). */
final class ReadmeWatched {

    private ReadmeWatched() {
    }

    static Map<String, ReloadStatus> status(DocuconfFiles files) {
        ReloadStatus tls = files.reloadStatus("tls");
        Map<String, ReloadStatus> all = files.reloadStatuses(); // for a health endpoint
        return tls == null ? Map.of() : all;
    }
}

/** Stands in for the app's properties. */
record OrdersProperties(TlsKeyPair tls) {
}

@Component
class ServerTls {
    private final AtomicReference<SSLContext> context = new AtomicReference<>();

    ServerTls(OrdersProperties props) throws GeneralSecurityException {
        context.set(props.tls().sslContext());
    }

    @EventListener(condition = "#event.input == 'tls'")
    void renewed(DocuconfFileReloadedEvent event) throws GeneralSecurityException {
        context.set(event.getValue(TlsKeyPair.class).sslContext());
    }

    SSLContext current() { // call on each accept, never cache the result
        return context.get();
    }
}

@Component
class Upstream {
    private final AtomicReference<HttpClient> client = new AtomicReference<>();

    Upstream(DocuconfFiles files) {
        CaBundle ca = files.get("upstreamCa", CaBundle.class).orElseThrow();
        client.set(build(ca));
        files.onChange("upstreamCa", CaBundle.class, next -> client.set(build(next)));
    }

    private static HttpClient build(CaBundle ca) {
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, ca.trustManagerFactory().getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(ssl).build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    HttpClient client() { // per request
        return client.get();
    }
}
