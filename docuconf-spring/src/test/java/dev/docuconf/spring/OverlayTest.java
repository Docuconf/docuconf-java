package dev.docuconf.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.check.Violation;
import dev.docuconf.contract.OverlaySpec;
import dev.docuconf.spring.fixture.ShopProperties;
import dev.docuconf.testing.CueVet;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;

/** Config-file overlays (SPEC §4.7). */
class OverlayTest {

    static final String OVERLAY = "etc/shop/overlay/shop.yaml";

    @TempDir
    Path tmp;

    ShopFixture shop;

    @BeforeEach
    void setUp() {
        shop = new ShopFixture(tmp);
    }

    private DocuconfValidationException fails() {
        Throwable t = assertThrows(Throwable.class, () -> shop.run().close());
        while (t != null && !(t instanceof DocuconfValidationException)) {
            t = t.getCause();
        }
        assertNotNull(t, "expected a DocuconfValidationException");
        return (DocuconfValidationException) t;
    }

    private static List<String> codes(DocuconfValidationException e) {
        return e.getViolations().stream().map(v -> v.code().id() + " " + v.input()).sorted()
                .collect(Collectors.toList());
    }

    static String exportedContract() throws Exception {
        try (InputStream in = OverlayTest.class.getResourceAsStream("/META-INF/docuconf/contract.cue")) {
            assertNotNull(in, "the processor did not write META-INF/docuconf/contract.cue");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void overlayIsExportedAndPassesTheMetaSchema() throws Exception {
        String cue = exportedContract();
        assertTrue(cue.contains("""
                	overlays: {
                		platform: {
                			description: "Platform overrides, layered over application.yml"
                			format: "yaml"
                			path: "/etc/shop/overlay/shop.yaml"
                			keySeparator: "."
                		}
                	}
                """), cue);
        assertTrue(cue.contains("configKey: \"shop.port\""), cue);
        CueVet.Result r = CueVet.vet(cue, tmp.resolve("vet"));
        assertEquals(0, r.exitCode(), r.output());
    }

    @Test
    void overlaySitsBetweenTheApplicationFilesAndTheEnvironment() {
        shop.write(OVERLAY, """
                shop:
                  contact: overlay@shop.example
                  port: 9091
                  origins:
                    - https://overlay.example
                """);
        shop.env.put("SPRING_PROFILES_ACTIVE", "layered");
        shop.env.put("SHOP_ORIGINS", "https://env.example");
        try (ConfigurableApplicationContext ctx = shop.run()) {
            ShopProperties p = ctx.getBean(ShopProperties.class);
            assertEquals("overlay@shop.example", p.contact(), "overlay over application.yml");
            assertEquals(9091, p.port(), "overlay over application-layered.yml");
            assertEquals(Duration.ofMinutes(2), p.timeout(), "application-layered.yml where the overlay is silent");
            assertEquals(List.of("https://env.example"), p.origins(), "environment over overlay");

            List<String> order = ctx.getEnvironment().getPropertySources().stream().map(PropertySource::getName)
                    .collect(Collectors.toList());
            int env = order.indexOf("systemEnvironment");
            int overlay = order.indexOf("docuconfOverlay [platform]");
            int profileFile = indexOf(order, "application-layered.yml");
            int baseFile = indexOf(order, "application.yml");
            assertTrue(env >= 0 && env < overlay && overlay < profileFile && profileFile < baseFile, order.toString());
        }
    }

    private static int indexOf(List<String> names, String file) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(file)) {
                return i;
            }
        }
        throw new AssertionError(file + " not in " + names);
    }

    @Test
    void aMissingOverlayIsNotAnError() {
        assertFalse(Files.exists(tmp.resolve(OVERLAY)));
        try (ConfigurableApplicationContext ctx = shop.run()) {
            ShopProperties p = ctx.getBean(ShopProperties.class);
            assertEquals(8080, p.port());
            assertEquals("base@shop.example", p.contact());
        }
    }

    @Test
    void overlayValuesAreValidatedLikeAnyOther() throws Exception {
        shop.write(OVERLAY, """
                shop:
                  port: 70000
                  level: LOUD
                  timeout: soon
                """);
        DocuconfValidationException e = fails();
        assertEquals(List.of("invalid_type SHOP_TIMEOUT", "not_in_enum SHOP_LEVEL", "out_of_range SHOP_PORT"),
                codes(e));
        assertTrue(e.getViolations().toString().contains("SHOP_PORT: is above max 65535 (got 70000)"), e.getViolations().toString());
    }

    @Test
    void aMalformedOverlayIsReportedWithTheOtherProblems() throws Exception {
        shop.write(OVERLAY, "shop:\n  port: [9091\n");
        shop.env.remove("SHOP_DATABASEURL");
        DocuconfValidationException e = fails();
        assertEquals(List.of("file_malformed platform", "missing_required SHOP_DATABASEURL"), codes(e));
        Violation v = e.getViolations().stream().filter(x -> x.input().equals("platform")).findFirst().orElseThrow();
        assertTrue(v.message().contains("is not valid YAML"), v.message());
        assertFalse(v.message().contains("9091"), v.message());
    }

    @Test
    void anOverlayInTheAppsOwnDirectoryIsRefused() {
        OverlaySpec o = new OverlaySpec("platform", "/app/overlay.yaml");
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                DocuconfOverlayEnvironmentPostProcessor.refuseAppDirectory(o, Path.of("/app/overlay.yaml"),
                        List.of(Path.of("/app"))));
        assertTrue(e.getMessage().contains("mounting it would hide the app's files"), e.getMessage());
        DocuconfOverlayEnvironmentPostProcessor.refuseAppDirectory(o, Path.of("/app/config/overlay.yaml"),
                List.of(Path.of("/app")));
    }

    // End to end: the platform renders the overlay from the exported contract with the CUE meta-schema, and the
    // app loads and binds the rendered file.
    @Test
    void anOverlayRenderedByThePlatformBindsInTheApp() throws Exception {
        Path module = tmp.resolve("cue");
        CueVet.module(exportedContract(), module);
        Files.createDirectories(module.resolve("platform"));
        Files.writeString(module.resolve("platform/render.cue"), """
                package platform

                import (
                	"docuconf.dev/contract"
                	app "docuconf.dev/svc:shop"
                )

                rendered: contract.#Render & {
                	contract: app
                	values: SHOP_DATABASEURL: secretKeyRef: {name: "shop-db", key: "url"}
                	overlays: platform: {
                		SHOP_PORT:    9091
                		SHOP_TIMEOUT: "1m30s"
                		SHOP_LEVEL:   "WARN"
                		SHOP_ORIGINS: ["https://a.example", "https://b.example"]
                		SHOP_CONTACT: "ops@shop.example"
                	}
                }
                file: rendered.configMaps[0].data["shop.yaml"]
                """);
        CueVet.Result r = CueVet.cue(module, "export", "./platform", "-e", "file", "--out", "text");
        assertEquals(0, r.exitCode(), r.output());
        assertTrue(r.output().contains("timeout: PT90S"), r.output());

        shop.write(OVERLAY, r.output());
        try (ConfigurableApplicationContext ctx = shop.run()) {
            ShopProperties p = ctx.getBean(ShopProperties.class);
            assertEquals(9091, p.port());
            assertEquals(Duration.ofSeconds(90), p.timeout());
            assertEquals(ShopProperties.Level.WARN, p.level());
            assertEquals(List.of("https://a.example", "https://b.example"), p.origins());
            assertEquals("ops@shop.example", p.contact());
        }
    }
}
