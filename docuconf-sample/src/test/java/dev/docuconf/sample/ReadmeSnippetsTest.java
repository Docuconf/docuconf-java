package dev.docuconf.sample;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Every code block in the READMEs must be in a file that CI compiles or runs, so the READMEs cannot drift from
 * working code:
 *
 * <ul>
 *   <li>{@code java}, {@code xml}, {@code kotlin} and {@code yaml} blocks appear as is (lines compared without
 *       indentation) in the examples, the build files or the test sources;</li>
 *   <li>each line of a {@code sh} block starts a line of a script CI runs ({@code examples/orders/quickstart.sh},
 *       the CI workflow);</li>
 *   <li>{@code cue} blocks appear in the orders example's committed contract, which CI checks against the exported
 *       one;</li>
 *   <li>{@code text} blocks are program output and are not compared.</li>
 * </ul>
 */
class ReadmeSnippetsTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private static final Pattern FENCE = Pattern.compile("(?ms)^```(\\w*)\\n(.*?)^```\\s*$");

    /** Where code must be found: compiled, built or run by CI. */
    private static final List<String> CODE = List.of("examples/orders", "examples/orders-gradle/build.gradle.kts",
            "docuconf-core/src/test/java", "docuconf-spring/src/test/java", "docuconf-sample/src/main/java");

    /** Where commands must be found: run by CI. */
    private static final List<String> COMMANDS = List.of("examples/orders/quickstart.sh",
            "examples/orders/smoke.sh", ".github/workflows/ci.yml");

    /** Commands CI cannot run as written, and why. */
    private static final Map<String, String> NOT_RUN = Map.of(
            "git clone https://github.com/docuconf/docuconf-java.git", "CI builds the checkout it already has",
            "cd docuconf-java", "CI builds the checkout it already has");

    @TestFactory
    Stream<DynamicTest> everyBlockIsChecked() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String readme : List.of("README.md", "examples/orders/README.md")) {
            String text = read(ROOT.resolve(readme));
            Matcher m = FENCE.matcher(text);
            int n = 0;
            while (m.find()) {
                n++;
                String lang = m.group(1);
                String body = m.group(2);
                int line = text.substring(0, m.start()).split("\n", -1).length;
                tests.add(DynamicTest.dynamicTest(readme + ":" + line + " (" + lang + ")", () -> check(lang, body)));
            }
            assertTrue(n > 0, "no code blocks in " + readme);
        }
        return tests.stream();
    }

    private static void check(String lang, String body) {
        switch (lang) {
            case "text" -> {
            }
            case "java", "xml", "kotlin", "yaml" -> {
                List<String> block = lines(body);
                for (Path f : files(CODE)) {
                    if (contains(lines(read(f)), block)) {
                        return;
                    }
                }
                fail("not found in any file CI compiles or runs:\n" + body);
            }
            case "cue" -> assertTrue(contains(lines(read(ROOT.resolve("examples/orders/contract.cue"))), lines(body)),
                    "not in examples/orders/contract.cue:\n" + body);
            case "sh" -> {
                List<String> scripts = new ArrayList<>();
                for (Path f : files(COMMANDS)) {
                    for (String l : lines(read(f))) {
                        scripts.add(l.replaceFirst("^(- )?run:\\s*", "")); // a one-line step in the workflow
                    }
                }
                for (String command : lines(body)) {
                    if (NOT_RUN.containsKey(command)) {
                        continue;
                    }
                    assertTrue(scripts.stream().anyMatch(s -> s.equals(command) || s.startsWith(command + " ")),
                            "command not run in CI: " + command);
                }
            }
            default -> fail("code block without a known language (java, xml, kotlin, yaml, sh, cue or text): "
                    + lang + "\n" + body);
        }
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            String s = l.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean contains(List<String> haystack, List<String> needle) {
        return java.util.Collections.indexOfSubList(haystack, needle) >= 0;
    }

    private static List<Path> files(List<String> roots) {
        List<Path> out = new ArrayList<>();
        for (String r : roots) {
            Path p = ROOT.resolve(r);
            if (Files.isRegularFile(p)) {
                out.add(p);
                continue;
            }
            try (Stream<Path> s = Files.walk(p)) {
                s.filter(Files::isRegularFile)
                        .filter(f -> !f.toString().contains("/target/") && !f.toString().contains("/build/"))
                        .filter(f -> !f.getFileName().toString().endsWith(".md"))
                        .filter(f -> f.getFileName().toString().matches(".*\\.(java|xml|kts|yml|yaml|sh|cue)"))
                        .forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
