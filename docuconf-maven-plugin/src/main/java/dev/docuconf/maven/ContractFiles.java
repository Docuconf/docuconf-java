package dev.docuconf.maven;

import dev.docuconf.contract.Bindings;
import dev.docuconf.contract.ContractBundle;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.contract.SpringFileHashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** What the goals do, apart from Maven, so it can be tested without a Maven build. */
final class ContractFiles {

    /**
     * The value of {@code metadata.generator.version}: {@code generator: {... version: "x"}} in CUE,
     * {@code "generator": {... "version": "x"}} in JSON.
     */
    private static final Pattern GENERATOR_VERSION =
            Pattern.compile("(\"?generator\"?\\s*:\\s*\\{[^{}]*?\\bversion\"?\\s*:\\s*)\"[^\"]*\"");

    private ContractFiles() {
    }

    /**
     * When the {@code application*.yml} files in the class output differ from the ones the contract was exported
     * from, deletes the compiled {@code @Docuconf} classes, so the compiler plugin recompiles them and the
     * annotation processor exports the contract again.
     *
     * @param classes the class output, with the resources already copied
     * @return what changed; empty when the contract is up to date (or there is none yet)
     * @throws IOException if a file cannot be read or deleted
     */
    static List<String> refresh(Path classes) throws IOException {
        List<String> changes = staleness(classes);
        if (changes.isEmpty()) {
            return changes;
        }
        ContractBundle bundle = read(classes);
        for (Bindings.ClassBinding c : bundle.bindings().classes) {
            Files.deleteIfExists(classes.resolve(c.className().replace('.', '/') + ".class"));
        }
        return changes;
    }

    /**
     * What changed in {@code application*.yml} since the contract in the class output was exported.
     *
     * @param classes the class output
     * @return one line per change; empty when up to date or when there is no contract
     * @throws IOException if a file cannot be read
     */
    static List<String> staleness(Path classes) throws IOException {
        Path json = classes.resolve(ContractJson.LOCATION);
        if (!Files.isRegularFile(json)) {
            return List.of();
        }
        ContractBundle bundle = read(classes);
        if (bundle.bindings().springFiles == null) {
            return List.of();
        }
        return SpringFileHashes.differences(bundle.bindings().springFiles, name -> {
            try {
                Path f = classes.resolve(name);
                return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
            } catch (IOException e) {
                return null;
            }
        }, listed(classes));
    }

    /**
     * Compares the committed contract with the exported one. Only the value of {@code metadata.generator.version}
     * is ignored: it is the SDK version, which changes with every SDK release (and every release of this
     * repository), so a contract exported by another SDK version is still up to date.
     *
     * @param exported the contract the processor wrote
     * @param committed the copy next to the pom
     * @return {@code null} when they are the same, else a one-line description of the first difference
     * @throws IOException if the exported contract cannot be read
     */
    static String difference(Path exported, Path committed) throws IOException {
        if (!Files.isRegularFile(committed)) {
            return committed + " does not exist";
        }
        List<String> a = withoutGeneratorVersion(Files.readString(committed, StandardCharsets.UTF_8)).lines().toList();
        List<String> b = withoutGeneratorVersion(Files.readString(exported, StandardCharsets.UTF_8)).lines().toList();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String was = i < a.size() ? a.get(i).strip() : "(end of file)";
            String now = i < b.size() ? b.get(i).strip() : "(end of file)";
            if (!was.equals(now)) {
                return "line " + (i + 1) + ": committed `" + was + "`, exported `" + now + "`";
            }
        }
        return null;
    }

    /**
     * Replaces the value of {@code metadata.generator.version} with a placeholder and leaves everything else as it is.
     *
     * @param contract a contract, CUE or JSON
     * @return the contract with {@code "<generator-version>"} as the generator's version
     */
    static String withoutGeneratorVersion(String contract) {
        return GENERATOR_VERSION.matcher(contract).replaceAll("$1\"<generator-version>\"");
    }

    private static ContractBundle read(Path classes) throws IOException {
        return ContractJson.read(Files.readString(classes.resolve(ContractJson.LOCATION), StandardCharsets.UTF_8));
    }

    private static List<String> listed(Path dir) throws IOException {
        List<String> out = new ArrayList<>();
        for (String sub : new String[] {"", "config/"}) {
            Path d = dir.resolve(sub);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> files = Files.list(d)) {
                files.map(p -> sub + p.getFileName()).filter(n -> SpringFileHashes.NAME.matcher(n).matches())
                        .forEach(out::add);
            }
        }
        return out;
    }
}
