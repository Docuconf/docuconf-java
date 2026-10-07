package dev.docuconf.spring;

import dev.docuconf.contract.ContractBundle;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.contract.SpringFileHashes;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds contracts exported from {@code application*.yml} files other than the ones next to them on the class path.
 *
 * <p>The annotation processor bakes {@code application*.yml} values into the contract, but javac and Gradle do not
 * recompile when only a resource changes, so after such an edit the jar would ship a contract whose defaults
 * disagree with its own {@code application.yml}. The processor records a hash of every file it read; this compares
 * them with the files on the class path.
 */
final class StaleContracts {

    private static final String SUFFIX = ContractJson.LOCATION;

    private StaleContracts() {
    }

    /**
     * A contract that is out of date.
     *
     * @param location where the contract is, for the message
     * @param inJar whether it was read from a jar (a packaged app) rather than a directory (a development run)
     * @param differences what changed, such as {@code application.yml changed}
     */
    record Stale(String location, boolean inJar, List<String> differences) {

        String message() {
            return "docuconf: the contract in " + location + " was exported from different application*.yml files ("
                    + String.join(", ", differences) + "), so its defaults are out of date. Rebuild so the annotation"
                    + " processor runs again: mvn clean package (the docuconf-maven-plugin's refresh goal does this"
                    + " by itself), or with Gradle, declare application*.yml as an input of compileJava (see the"
                    + " docuconf README).";
        }
    }

    static List<Stale> find(ClassLoader classLoader) throws IOException {
        List<Stale> out = new ArrayList<>();
        Enumeration<URL> urls = classLoader.getResources(SUFFIX);
        Set<String> seen = new HashSet<>();
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (!seen.add(url.toString())) {
                continue;
            }
            ContractBundle bundle;
            try (InputStream in = url.openStream()) {
                bundle = ContractJson.read(in);
            }
            if (bundle.bindings().springFiles == null) {
                continue; // written by an older processor
            }
            Stale stale = check(url, bundle);
            if (stale != null) {
                out.add(stale);
            }
        }
        return out;
    }

    @SuppressWarnings("deprecation") // new URL(String): jar: and Spring Boot's nested: URLs are not all valid URIs.
    private static Stale check(URL contract, ContractBundle bundle) {
        String s = contract.toString();
        String root = s.substring(0, s.length() - SUFFIX.length());
        boolean directory = root.startsWith("file:");
        List<Path> dirs = new ArrayList<>();
        if (directory) {
            Path dir = Path.of(URI.create(root));
            dirs.add(dir);
            // Gradle keeps resources apart from classes: build/classes/java/main and build/resources/main.
            Path set = dir.getFileName();
            Path classes = dir.getParent() == null ? null : dir.getParent().getParent();
            if (set != null && classes != null && classes.getFileName() != null
                    && classes.getFileName().toString().equals("classes") && classes.getParent() != null) {
                dirs.add(classes.getParent().resolve("resources").resolve(set.toString()));
            }
        }
        List<String> differences;
        if (directory) {
            Set<String> present = new LinkedHashSet<>();
            for (Path dir : dirs) {
                present.addAll(listed(dir));
            }
            if (present.isEmpty() && !bundle.bindings().springFiles.isEmpty()) {
                return null; // the resources are somewhere this cannot see
            }
            differences = SpringFileHashes.differences(bundle.bindings().springFiles, name -> {
                for (Path dir : dirs) {
                    Path f = dir.resolve(name);
                    if (Files.isRegularFile(f)) {
                        try {
                            return Files.readAllBytes(f);
                        } catch (IOException e) {
                            return null;
                        }
                    }
                }
                return null;
            }, present);
        } else {
            // Spring Boot's repackaging moves META-INF/ to the root of the jar but keeps the resources in
            // BOOT-INF/classes/ (WEB-INF/classes/ in a war).
            differences = SpringFileHashes.differences(bundle.bindings().springFiles, name -> {
                for (String dir : new String[] {"", "BOOT-INF/classes/", "WEB-INF/classes/"}) {
                    try (InputStream in = new URL(root + dir + name).openStream()) {
                        return in.readAllBytes();
                    } catch (IOException e) {
                        // Not there; try the next place.
                    }
                }
                return null;
            }, null);
        }
        if (differences.isEmpty()) {
            return null;
        }
        String location = directory ? Path.of(URI.create(root)).toString() : root.replaceFirst("!/$", "");
        return new Stale(location, !directory, differences);
    }

    private static List<String> listed(Path dir) {
        List<String> out = new ArrayList<>();
        for (String sub : new String[] {"", "config/"}) {
            Path d = dir.resolve(sub);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> files = Files.list(d)) {
                files.map(p -> sub + p.getFileName()).filter(n -> SpringFileHashes.NAME.matcher(n).matches())
                        .forEach(out::add);
            } catch (IOException e) {
                // Treat as absent.
            }
        }
        return out;
    }
}
