package dev.docuconf.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.contract.Bindings;
import dev.docuconf.contract.Contract;
import dev.docuconf.contract.ContractBundle;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.contract.SpringFileHashes;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A contract exported from another application.yml than the one shipped is noticed at startup. */
class StaleContractsTest {

    @TempDir
    Path tmp;

    private static String contract(Map<String, String> springFiles) {
        Contract c = new Contract();
        c.name = "orders";
        Bindings b = new Bindings();
        b.springFiles = new TreeMap<>(springFiles);
        return ContractJson.write(new ContractBundle(c, b));
    }

    private static List<StaleContracts.Stale> find(Path classpathEntry) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classpathEntry.toUri().toURL()}, null)) {
            return StaleContracts.find(loader);
        }
    }

    private Path classes(String contractJson, Map<String, String> files) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("classes"));
        Files.createDirectories(dir.resolve("META-INF/docuconf"));
        Files.writeString(dir.resolve(ContractJson.LOCATION), contractJson);
        for (Map.Entry<String, String> f : files.entrySet()) {
            Files.createDirectories(dir.resolve(f.getKey()).getParent());
            Files.writeString(dir.resolve(f.getKey()), f.getValue());
        }
        return dir;
    }

    @Test
    void anUnchangedApplicationYmlIsFine() throws Exception {
        String yml = "orders.port: 8080\n";
        Path dir = classes(contract(Map.of("application.yml", SpringFileHashes.sha256(yml))),
                Map.of("application.yml", yml));
        assertTrue(find(dir).isEmpty());
    }

    @Test
    void aChangedAddedOrRemovedFileIsReported() throws Exception {
        Path dir = classes(contract(Map.of("application.yml", SpringFileHashes.sha256("orders.port: 8080\n"),
                "application-old.yml", SpringFileHashes.sha256("x: 1\n"))),
                Map.of("application.yml", "orders.port: 9090\n", "application-prod.yml", "orders.port: 80\n"));
        List<StaleContracts.Stale> stale = find(dir);
        assertEquals(1, stale.size());
        assertFalse(stale.get(0).inJar());
        assertEquals(List.of("application-old.yml was removed", "application-prod.yml was added",
                "application.yml changed"), stale.get(0).differences());
        assertTrue(stale.get(0).message().contains("mvn clean package"), stale.get(0).message());
    }

    @Test
    void aStaleContractInAJarIsReportedAsPackaged() throws Exception {
        Path jar = tmp.resolve("app.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, ContractJson.LOCATION, contract(Map.of("application.yml", SpringFileHashes.sha256("a: 1\n"))));
            put(out, "application.yml", "a: 2\n");
        }
        List<StaleContracts.Stale> stale = find(jar);
        assertEquals(1, stale.size());
        assertTrue(stale.get(0).inJar());
        assertEquals(List.of("application.yml changed"), stale.get(0).differences());
    }

    @Test
    void springBootsJarLayoutIsUnderstood() throws Exception {
        Path jar = tmp.resolve("boot.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, ContractJson.LOCATION, contract(Map.of("application.yml", SpringFileHashes.sha256("a: 1\n"))));
            put(out, "BOOT-INF/classes/application.yml", "a: 1\n");
        }
        assertTrue(find(jar).isEmpty());
    }

    private static void put(JarOutputStream out, String name, String content) throws Exception {
        out.putNextEntry(new JarEntry(name));
        OutputStream o = out;
        o.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
