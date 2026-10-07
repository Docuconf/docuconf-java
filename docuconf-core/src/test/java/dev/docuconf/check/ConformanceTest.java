package dev.docuconf.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.docuconf.contract.GoDuration;
import dev.docuconf.contract.Json;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the shared conformance suite (SPEC §12, docuconf-go {@code conformance/README.md}) through
 * {@link ContractFirst}. Each case is a test named by its {@code id}, so a failure points at its YAML source.
 *
 * <p>{@code cases.json} is found through {@code DOCUCONF_CONFORMANCE}, else {@code docuconf-go/conformance/cases.json}
 * in this directory or a parent (a sibling checkout of docuconf-go). When it is missing the suite is skipped,
 * unless {@code DOCUCONF_REQUIRE_CONFORMANCE=1}.
 */
class ConformanceTest {

    /** Capability tags this SDK lacks. Java holds every 64-bit integer and validates JSON Schema. */
    static final Set<String> UNSUPPORTED = Set.of();

    @TempDir
    Path tmp;

    static Path casesFile() {
        String env = System.getenv("DOCUCONF_CONFORMANCE");
        if (env != null && !env.isEmpty()) {
            return Path.of(env);
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docuconf-go/conformance/cases.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Path.of("..", "docuconf-go", "conformance", "cases.json");
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> conformance() throws IOException {
        Path file = casesFile();
        if (!Files.isRegularFile(file)) {
            if ("1".equals(System.getenv("DOCUCONF_REQUIRE_CONFORMANCE"))) {
                fail("conformance cases not found at " + file.toAbsolutePath()
                        + " (set DOCUCONF_CONFORMANCE to docuconf-go's conformance/cases.json)");
            }
            Assumptions.abort("conformance cases not found at " + file.toAbsolutePath()
                    + "; set DOCUCONF_CONFORMANCE");
        }
        Map<String, Object> doc = (Map<String, Object>) Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(1L, doc.get("version"), "unsupported cases.json version");
        List<Map<String, Object>> cases = (List<Map<String, Object>>) doc.get("cases");
        AtomicInteger passed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        List<String> failed = new ArrayList<>();
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String id = (String) c.get("id");
            tests.add(DynamicTest.dynamicTest(id, () -> {
                List<String> missing = new ArrayList<>();
                for (Object tag : (List<Object>) c.getOrDefault("requires", List.of())) {
                    if (UNSUPPORTED.contains(tag)) {
                        missing.add((String) tag);
                    }
                }
                if (!missing.isEmpty()) {
                    skipped.incrementAndGet();
                    Assumptions.abort(id + ": requires " + String.join(", ", missing));
                }
                try {
                    run(c);
                    passed.incrementAndGet();
                } catch (AssertionError | RuntimeException e) {
                    failed.add(id);
                    throw e;
                }
            }));
        }
        tests.add(DynamicTest.dynamicTest("summary", () -> {
            System.out.printf("conformance: %d passed, %d skipped, %d failed of %d (%s)%n", passed.get(),
                    skipped.get(), failed.size(), cases.size(), file);
            for (String id : failed) {
                System.out.println("conformance: FAILED " + id);
            }
        }));
        return tests.stream();
    }

    @SuppressWarnings("unchecked")
    private void run(Map<String, Object> c) throws IOException {
        String id = (String) c.get("id");
        Map<String, Object> contract = (Map<String, Object>) c.get("contract");
        Map<String, String> env = new LinkedHashMap<>();
        ((Map<String, Object>) c.get("env")).forEach((k, v) -> env.put(k, (String) v));
        ContractFirst.Result r = ContractFirst.load(Json.write(contract), env);

        if (c.containsKey("expect")) {
            assertTrue(r.ok(), id + ": expected success, got " + r.violations());
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            for (Map.Entry<String, Object> e : expect.entrySet()) {
                assertTrue(r.values().containsKey(e.getKey()), id + ": no value for " + e.getKey());
                Object actual = toJson(r.values().get(e.getKey()));
                assertTrue(same(e.getValue(), actual), id + ": " + e.getKey() + " is " + Json.write(actual)
                        + ", expected " + Json.write(e.getValue()));
            }
            return;
        }

        List<Map<String, Object>> errors = (List<Map<String, Object>>) c.get("errors");
        Set<String> want = new TreeSet<>();
        for (Map<String, Object> e : errors) {
            want.add(e.get("var") + " " + e.get("code"));
        }
        Set<String> got = new TreeSet<>();
        for (Violation v : r.violations()) {
            got.add(v.input() + " " + v.code().id());
        }
        assertEquals(want, got, id + ": violations " + r.violations());

        // No error output may hold a secret's raw value: not the messages, not the termination log.
        Path log = tmp.resolve("termination-log");
        TerminationLog.write(r.violations(), k -> TerminationLog.ENV.equals(k) ? log.toString() : null);
        String output = r.violations().toString() + "\n" + new ContractFirst.InvalidConfigurationException(
                r.violations()).getMessage() + "\n" + Files.readString(log, StandardCharsets.UTF_8);
        Map<String, Object> vars = (Map<String, Object>) contract.get("vars");
        for (Map.Entry<String, Object> v : vars.entrySet()) {
            if (!Boolean.TRUE.equals(((Map<String, Object>) v.getValue()).get("secret"))) {
                continue;
            }
            for (Map.Entry<String, String> e : env.entrySet()) {
                boolean ofVar = e.getKey().equals(v.getKey()) || e.getKey().startsWith(v.getKey() + "__");
                if (ofVar && !e.getValue().isEmpty()) {
                    assertFalse(output.contains(e.getValue()), id + ": error output holds the value of secret "
                            + e.getKey() + ":\n" + output);
                }
            }
        }
    }

    /** A typed value as JSON data: durations in canonical Go form. */
    private static Object toJson(Object value) {
        if (value instanceof Duration d) {
            return GoDuration.format(d);
        }
        if (value instanceof List<?> l) {
            return l.stream().map(ConformanceTest::toJson).toList();
        }
        return value;
    }

    /** Integers exactly, floats numerically, everything else by equality. */
    private static boolean same(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected instanceof Number e) {
            return actual instanceof Number a
                    && new BigDecimal(e.toString()).compareTo(new BigDecimal(a.toString())) == 0;
        }
        if (expected instanceof List<?> e) {
            if (!(actual instanceof List<?> a) || a.size() != e.size()) {
                return false;
            }
            for (int i = 0; i < e.size(); i++) {
                if (!same(e.get(i), a.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof Map<?, ?> e) {
            if (!(actual instanceof Map<?, ?> a) || !a.keySet().equals(e.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> x : e.entrySet()) {
                if (!same(x.getValue(), a.get(x.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return expected.equals(actual);
    }
}
