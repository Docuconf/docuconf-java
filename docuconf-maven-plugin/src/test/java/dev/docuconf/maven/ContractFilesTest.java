package dev.docuconf.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.contract.Bindings;
import dev.docuconf.contract.Contract;
import dev.docuconf.contract.ContractBundle;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.contract.SpringFileHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractFilesTest {

    @TempDir
    Path classes;

    private void contract(String yml) throws Exception {
        Bindings b = new Bindings();
        b.classes.add(new Bindings.ClassBinding("demo.OrdersProperties", "orders"));
        b.springFiles = new TreeMap<>(Map.of("application.yml", SpringFileHashes.sha256(yml)));
        Contract c = new Contract();
        c.name = "orders";
        Files.createDirectories(classes.resolve("META-INF/docuconf"));
        Files.writeString(classes.resolve(ContractJson.LOCATION), ContractJson.write(new ContractBundle(c, b)));
        Files.createDirectories(classes.resolve("demo"));
        Files.writeString(classes.resolve("demo/OrdersProperties.class"), "bytes");
    }

    @Test
    void anUnchangedYmlLeavesTheClassesAlone() throws Exception {
        contract("orders.port: 8080\n");
        Files.writeString(classes.resolve("application.yml"), "orders.port: 8080\n");
        assertEquals(List.of(), ContractFiles.refresh(classes));
        assertTrue(Files.exists(classes.resolve("demo/OrdersProperties.class")));
    }

    @Test
    void aChangedYmlRemovesTheDocuconfClassesSoTheyAreRecompiled() throws Exception {
        contract("orders.port: 8080\n");
        Files.writeString(classes.resolve("application.yml"), "orders.port: 9090\n");
        assertEquals(List.of("application.yml changed"), ContractFiles.refresh(classes));
        assertFalse(Files.exists(classes.resolve("demo/OrdersProperties.class")));
    }

    @Test
    void noContractYetIsNotAnError() throws Exception {
        assertEquals(List.of(), ContractFiles.refresh(classes));
    }

    @Test
    void theFirstDifferenceIsNamed() throws Exception {
        Path exported = classes.resolve("exported.cue");
        Path committed = classes.resolve("contract.cue");
        Files.writeString(exported, "a: 1\nport: {\n\tdefault: 9090\n}\n");
        Files.writeString(committed, "a: 1\nport: {\n\tdefault: 8080\n}\n");
        assertEquals("line 3: committed `default: 8080`, exported `default: 9090`",
                ContractFiles.difference(exported, committed));
        Files.writeString(committed, Files.readString(exported));
        assertNull(ContractFiles.difference(exported, committed));
        assertTrue(ContractFiles.difference(exported, classes.resolve("missing.cue")).endsWith("does not exist"));
    }

    // Every release bumps the SDK version, which contracts record in metadata.generator.version. The check ignores
    // that value, and only that value, so a release does not make every committed contract stale.
    @Test
    void onlyTheGeneratorVersionIsIgnored() throws Exception {
        Path exported = classes.resolve("exported.cue");
        Path committed = classes.resolve("contract.cue");
        String cue = """
                metadata: {
                \tname: "orders"
                \tappVersion: "1.0.0"
                \tgenerator: {language: "java", sdk: "docuconf-spring", version: "0.2.0"}
                }
                vars: {
                \tORDERS_PORT: {type: "int", default: 8080}
                }
                """;
        Files.writeString(exported, cue);

        Files.writeString(committed, cue.replace("version: \"0.2.0\"", "version: \"0.1.0-SNAPSHOT\""));
        assertNull(ContractFiles.difference(exported, committed));

        for (String[] edit : new String[][] {
                {"sdk: \"docuconf-spring\"", "sdk: \"docuconf-core\""},
                {"language: \"java\"", "language: \"kotlin\""},
                {"appVersion: \"1.0.0\"", "appVersion: \"0.2.0\""},
                {"default: 8080", "default: 9090"},
                {"name: \"orders\"", "name: \"payments\""}}) {
            String other = cue.replace("version: \"0.2.0\"", "version: \"0.1.0-SNAPSHOT\"").replace(edit[0], edit[1]);
            assertTrue(!other.equals(cue), edit[0]);
            Files.writeString(committed, other);
            assertTrue(ContractFiles.difference(exported, committed) != null, "not detected: " + edit[1]);
        }
        Files.writeString(committed, cue.replace("default: 8080", "default: 9090"));
        assertEquals("line 7: committed `ORDERS_PORT: {type: \"int\", default: 9090}`, exported"
                + " `ORDERS_PORT: {type: \"int\", default: 8080}`", ContractFiles.difference(exported, committed));
    }

    @Test
    void theGeneratorVersionIsIgnoredInJsonToo() {
        String json = "{\n  \"metadata\": {\n    \"generator\": {\n      \"language\": \"java\",\n"
                + "      \"version\": \"0.2.0\"\n    },\n    \"appVersion\": \"1.0.0\"\n  }\n}\n";
        String older = json.replace("\"0.2.0\"", "\"0.1.0\"");
        assertEquals(ContractFiles.withoutGeneratorVersion(json), ContractFiles.withoutGeneratorVersion(older));
        String otherApp = older.replace("\"1.0.0\"", "\"2.0.0\"");
        assertTrue(!ContractFiles.withoutGeneratorVersion(json).equals(ContractFiles.withoutGeneratorVersion(otherApp)));
        String otherLanguage = older.replace("\"java\"", "\"kotlin\"");
        assertTrue(!ContractFiles.withoutGeneratorVersion(json).equals(
                ContractFiles.withoutGeneratorVersion(otherLanguage)));
    }
}
