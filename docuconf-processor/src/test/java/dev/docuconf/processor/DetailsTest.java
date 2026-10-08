package dev.docuconf.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.check.ContractFirst;
import dev.docuconf.contract.Contract;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.testing.CueVet;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Descriptions from the Javadoc's first sentence, details from the rest (SPEC section 14.7). */
class DetailsTest {

    @TempDir
    Path tmp;

    private static final String IMPORTS = """
            package demo;

            import dev.docuconf.*;
            import jakarta.validation.constraints.*;
            import java.nio.file.Path;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            import org.springframework.boot.context.properties.bind.DefaultValue;
            """;

    private static final String RECORD = IMPORTS + """
            /**
             * Shop settings.
             *
             * @param workers Background workers that process orders. Each holds one connection to
             *        {@link #databaseUrl() the database}, e.g. {@code 4}.
             *        <p>Raise it when:
             *        <ul>
             *          <li>the queue backs up;</li>
             *          <li>orders wait for <b>minutes</b>, see <a href="https://example.com/q">the queue guide</a>.</li>
             *        </ul>
             *        <pre>{@code
             *        SHOP_WORKERS=8
             *          java -jar shop.jar
             *        }</pre>
             *        <ol><li>first</li><li>second</li></ol>
             * @param region Cloud region<p>Set by the platform.
             * @param databaseUrl Database URL
             * @param banner The banner text.
             */
            @Docuconf(service = "shop")
            @ConfigurationProperties("shop")
            public record ShopProperties(
                    @Min(1) @Max(64) @DefaultValue("4") int workers,
                    @DefaultValue("eu") String region,
                    @Description("Primary database") @DefaultValue("postgres://db") String databaseUrl,
                    @TextFile("/etc/shop/banner.txt") String banner) {}
            """;

    private static final String BEAN = IMPORTS + """
            @Docuconf(service = "shop")
            @ConfigurationProperties("shop")
            public class ShopProperties {
                /**
                 * Most items in one order.
                 *
                 * <p>Orders above it are split.
                 * Use {@literal <=} 100 for small shops.
                 *
                 * @see java.lang.Integer
                 */
                @Min(1) private int maxItems = 10;

                /** Only a summary. */
                private String region = "eu";

                public int getMaxItems() { return maxItems; }
                public void setMaxItems(int maxItems) { this.maxItems = maxItems; }
                public String getRegion() { return region; }
                public void setRegion(String region) { this.region = region; }
            }
            """;

    private Compilation compile(String source) throws Exception {
        return Compilation.compile(tmp, Map.of(), Map.of("demo.ShopProperties", source));
    }

    private Contract contract(String source) throws Exception {
        Compilation c = compile(source);
        assertTrue(c.success, c.allErrors());
        return ContractJson.read(c.contractJson()).contract();
    }

    @Test
    void theFirstSentenceIsTheDescriptionAndTheRestTheDetails() throws Exception {
        Contract k = contract(RECORD);

        assertEquals("Background workers that process orders.", k.vars.get("SHOP_WORKERS").description);
        assertEquals("""
                Each holds one connection to `databaseUrl()`, e.g. `4`.

                Raise it when:

                - the queue backs up;
                - orders wait for **minutes**, see [the queue guide](https://example.com/q).

                ```
                SHOP_WORKERS=8
                  java -jar shop.jar
                ```

                1. first
                2. second""", k.vars.get("SHOP_WORKERS").details);
        assertEquals("Cloud region", k.vars.get("SHOP_REGION").description);
        assertEquals("Set by the platform.", k.vars.get("SHOP_REGION").details);
        // @Description wins for the description; a one-sentence Javadoc has no details.
        assertEquals("Primary database", k.vars.get("SHOP_DATABASEURL").description);
        assertNull(k.vars.get("SHOP_DATABASEURL").details);
        assertEquals("The banner text.", k.files.get("banner").description);
        assertNull(k.files.get("banner").details);
    }

    @Test
    void fieldJavadocOfAJavaBeanWorksTooAndBlockTagsAreDropped() throws Exception {
        Contract k = contract(BEAN);

        assertEquals("Most items in one order.", k.vars.get("SHOP_MAXITEMS").description);
        assertEquals("Orders above it are split. Use `<=` 100 for small shops.", k.vars.get("SHOP_MAXITEMS").details);
        assertEquals("Only a summary.", k.vars.get("SHOP_REGION").description);
        assertNull(k.vars.get("SHOP_REGION").details);
    }

    @Test
    void detailsAreExportedAfterTheDescriptionAndPassTheMetaSchema() throws Exception {
        Compilation c = compile(RECORD);
        assertTrue(c.success, c.allErrors());
        String cue = c.contractCue();
        assertTrue(cue.matches("(?s).*description: \"Background workers that process orders\\.\"\\n\\s*details: \"Each holds.*"),
                cue);
        assertTrue(c.contractJson().matches("(?s).*\"description\"\\s*:\\s*\"Background workers that process orders\\.\",\\s*"
                + "\"details\"\\s*:\\s*\"Each holds.*"), c.contractJson());
        CueVet.Result vet = CueVet.vet(cue, tmp.resolve("vet"));
        assertEquals(0, vet.exitCode(), vet.output() + "\n" + cue);
    }

    @Test
    void aMissingDescriptionFailsTheBuild() throws Exception {
        Compilation c = compile(IMPORTS + """
                @Docuconf(service = "shop")
                @ConfigurationProperties("shop")
                public record ShopProperties(@DefaultValue("4") int workers) {}
                """);

        assertFalse(c.success);
        assertTrue(c.allErrors().contains("SHOP_WORKERS: needs a description (Javadoc or @Description)"), c.allErrors());
    }

    @Test
    void detailsOver4000CodePointsFailTheBuild() throws Exception {
        String atLimit = "日本".repeat(2000);
        Contract k = contract(IMPORTS + """
                /** @param workers Background workers.<p>%s */
                @Docuconf(service = "shop")
                @ConfigurationProperties("shop")
                public record ShopProperties(@DefaultValue("4") int workers) {}
                """.formatted(atLimit));
        assertEquals(atLimit, k.vars.get("SHOP_WORKERS").details);

        Compilation c = compile(IMPORTS + """
                /** @param workers Background workers.<p>%s */
                @Docuconf(service = "shop")
                @ConfigurationProperties("shop")
                public record ShopProperties(@DefaultValue("4") int workers) {}
                """.formatted(atLimit + "語"));
        assertFalse(c.success);
        assertTrue(c.allErrors().contains("SHOP_WORKERS: details are 4001 characters (Unicode code points); the most is 4000"),
                c.allErrors());
    }

    private static String contractJson(String details) {
        return """
                {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract", "metadata": {"name": "shop"},
                 "vars": {"PORT": {"type": "int", "description": "Port to listen on", "details": %s, "default": 8080}}}
                """.formatted(details);
    }

    @Test
    void contractFirstLoadsAContractWithDetailsAndIgnoresThem() {
        ContractFirst.Result r = ContractFirst.load(contractJson("\"# Why\\n\\nBehind the mesh, keep the default.\""),
                Map.of("PORT", "9090"));
        assertTrue(r.violations().isEmpty(), r.violations().toString());
        assertEquals(9090L, ((Number) r.values().get("PORT")).longValue());
    }

    @Test
    void contractFirstRejectsDetailsTheMetaSchemaRejects() {
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> ContractFirst.load(contractJson("\"  \""), Map.of()));
        assertTrue(blank.getMessage().contains("PORT: details must not be blank"), blank.getMessage());
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> ContractFirst.load(contractJson("\"" + "日本".repeat(2001) + "\""), Map.of()));
        assertTrue(tooLong.getMessage().contains("PORT: details are 4002 characters"), tooLong.getMessage());
    }
}
