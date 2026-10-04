package dev.docuconf.spring;

import dev.docuconf.spring.fixture.ShopApp;
import dev.docuconf.testing.TestCerts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** A file root with valid files for every shop input, and an environment map. */
final class ShopFixture {

    static final String DB_SECRET = "s3cr3t-pa55";
    static final String KS_PASSWORD = "ks-pa55word";

    final Path root;
    final Map<String, Object> env = new HashMap<>();

    ShopFixture(Path root) {
        this.root = root;
        env.put("DOCUCONF_FILE_ROOT", root.toString());
        env.put("DOCUCONF_TERMINATION_LOG", root.resolve("termination-log").toString());
        env.put("SHOP_DATABASEURL", "postgres://shop:" + DB_SECRET + "@db.internal/shop");
        env.put("SHOP_KEYSTOREPASSWORD", KS_PASSWORD);
        TestCerts.Issued leaf = TestCerts.selfSigned(TestCerts.ec(), 90, "shop.internal");
        tls(leaf, TestCerts.pkcs8(leaf.keys().getPrivate()));
        write("etc/shop/routes/routes.yaml", """
                routes:
                  - match: /api
                    upstream: https://api.internal
                    timeout: PT5S
                """);
        write("etc/shop/license/license.key", "ABCD\n");
        write("etc/shop/ca/bundle.pem", TestCerts.pem(TestCerts.ca("Shop CA").certificate()));
        write("etc/shop/partner/keystore.p12",
                TestCerts.keystore("PKCS12", TestCerts.selfSigned(TestCerts.rsa(), 30), KS_PASSWORD));
        write("data/geo/db.mmdb", new byte[] {1, 2, 3});
    }

    void tls(TestCerts.Issued leaf, String keyPem) {
        TestCerts.writeTls(root.resolve("etc/shop/tls"), keyPem, new X509Certificate[] {leaf.certificate()}, null);
    }

    void write(String relative, Object content) {
        try {
            Path p = root.resolve(relative);
            Files.createDirectories(p.getParent());
            if (content instanceof byte[] b) {
                Files.write(p, b);
            } else {
                Files.writeString(p, content.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ConfigurableApplicationContext run(String... args) {
        return new SpringApplicationBuilder(ShopApp.class).web(WebApplicationType.NONE)
                .initializers(ctx -> ctx.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                new HashMap<>(env))))
                .logStartupInfo(false).run(args);
    }
}
