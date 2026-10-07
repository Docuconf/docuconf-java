package dev.docuconf.processor;

import dev.docuconf.contract.Names;
import dev.docuconf.contract.SpringFileHashes;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The {@code application*.yml} and {@code application*.properties} files that ship in the jar (SPEC §4.4): base
 * values become defaults, {@code application-{profile}} values become profile defaults. Spring's precedence is
 * followed: {@code config/} over the classpath root, {@code .properties} over {@code .yml}, later documents over
 * earlier ones.
 */
final class SpringFiles {

    private static final Pattern PROFILE_FILE = Pattern.compile("^application-([^.]+)\\.(yml|yaml|properties)$");

    /** A value and the file it came from. */
    record Value(Object value, String source) {
    }

    /** Base values, by canonical property name. */
    final Map<String, Value> base = new LinkedHashMap<>();
    /** Profile values, by profile then canonical property name. */
    final Map<String, Map<String, Value>> profiles = new TreeMap<>();
    /** Files read, for messages. */
    final List<String> read = new ArrayList<>();
    /** The SHA-256 of every file read, by name relative to the root, so a stale contract can be detected. */
    final Map<String, String> hashes = new TreeMap<>();

    private final Consumer<String> warn;

    private SpringFiles(Consumer<String> warn) {
        this.warn = warn;
    }

    /** Reads the files under a resources directory (a class output directory or src/main/resources). */
    static SpringFiles read(Path root, Consumer<String> warn) throws IOException {
        SpringFiles files = new SpringFiles(warn);
        if (root == null) {
            return files;
        }
        for (String dir : new String[] {"", "config/"}) {
            Path d = root.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            for (String ext : new String[] {"yml", "yaml", "properties"}) {
                Path f = d.resolve("application." + ext);
                if (Files.isRegularFile(f)) {
                    files.load(f, dir + f.getFileName(), null);
                }
            }
            List<Path> profileFiles = new ArrayList<>();
            try (Stream<Path> s = Files.list(d)) {
                s.filter(p -> PROFILE_FILE.matcher(p.getFileName().toString()).matches()).sorted((a, b) -> {
                    // yml before properties, so properties win.
                    String ea = a.getFileName().toString().endsWith(".properties") ? "1" : "0";
                    String eb = b.getFileName().toString().endsWith(".properties") ? "1" : "0";
                    return (ea + a.getFileName()).compareTo(eb + b.getFileName());
                }).forEach(profileFiles::add);
            }
            for (Path f : profileFiles) {
                Matcher m = PROFILE_FILE.matcher(f.getFileName().toString());
                m.matches();
                files.load(f, dir + f.getFileName(), m.group(1));
            }
        }
        return files;
    }

    /** Whether any value was found. */
    boolean isEmpty() {
        return base.isEmpty() && profiles.isEmpty();
    }

    /** A base value by property name, in any relaxed form. */
    Value baseValue(String key) {
        return base.get(Names.canonical(key));
    }

    private void load(Path file, String label, String fileProfile) throws IOException {
        read.add(label);
        byte[] bytes = Files.readAllBytes(file);
        hashes.put(label, SpringFileHashes.sha256(bytes));
        String text = new String(bytes, file.toString().endsWith(".properties")
                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        List<Map<String, Object>> docs = new ArrayList<>();
        if (file.toString().endsWith(".properties")) {
            for (String part : text.split("(?m)^[#!]---\\s*$")) {
                Properties p = new Properties();
                p.load(new StringReader(part));
                Map<String, Object> m = new LinkedHashMap<>();
                for (String k : p.stringPropertyNames()) {
                    m.put(k, p.getProperty(k));
                }
                docs.add(m);
            }
        } else {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            for (Object doc : yaml.loadAll(text)) {
                if (doc instanceof Map<?, ?> m) {
                    Map<String, Object> flat = new LinkedHashMap<>();
                    flatten("", m, flat);
                    docs.add(flat);
                }
            }
        }
        for (Map<String, Object> doc : docs) {
            String profile = fileProfile;
            Object onProfile = find(doc, "spring.config.activate.on-profile");
            if (onProfile == null) {
                onProfile = find(doc, "spring.profiles");
            }
            if (onProfile != null) {
                String expr = onProfile.toString().trim();
                if (fileProfile != null || !expr.matches("[A-Za-z0-9_.-]+")) {
                    warn.accept(label + ": a document activated by profile expression \"" + expr
                            + "\" is not exported; only single profile names are supported");
                    continue;
                }
                profile = expr;
            }
            Map<String, Value> target = profile == null ? base : profiles.computeIfAbsent(profile, k -> new LinkedHashMap<>());
            for (Map.Entry<String, Object> e : doc.entrySet()) {
                target.put(Names.canonical(e.getKey()), new Value(e.getValue(), label));
            }
        }
    }

    private static Object find(Map<String, Object> doc, String key) {
        String c = Names.canonical(key);
        for (Map.Entry<String, Object> e : doc.entrySet()) {
            if (Names.canonical(e.getKey()).equals(c)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Flattens nested maps into dotted keys. Lists stay whole, as Spring binds a list from one source. */
    private static void flatten(String prefix, Map<?, ?> m, Map<String, Object> out) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> inner) {
                flatten(key, inner, out);
            } else {
                out.put(key, e.getValue());
            }
        }
    }
}
