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
}
