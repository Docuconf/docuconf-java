package dev.docuconf.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Both Jackson generations read config files the same way. */
class JsonMapperTest {

    public record Route(String match, Duration timeout) {
    }

    public record Routes(List<Route> routes) {
    }

    public record Limits(int perMinute, int burst) {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mappers")
    void aMissingPrimitiveIsZeroLikeSpringBindsIt(String name, Function<String, JsonMapper> mapper) throws Exception {
        assertEquals(new Limits(5, 0), mapper.apply("json").read("{\"perMinute\":5}", Limits.class));
    }

    static Stream<Arguments> mappers() {
        Function<String, JsonMapper> j2 = Jackson2Mapper::new;
        Function<String, JsonMapper> j3 = Jackson3Mapper::new;
        return Stream.of(Arguments.of("jackson2", j2), Arguments.of("jackson3", j3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mappers")
    void readsYamlTomlAndJson(String name, Function<String, JsonMapper> mapper) throws Exception {
        Routes expected = new Routes(List.of(new Route("/api", Duration.ofSeconds(5))));
        assertEquals(expected, read(mapper.apply("yaml"), "routes:\n  - match: /api\n    timeout: PT5S\n"));
        assertEquals(expected, read(mapper.apply("toml"), "[[routes]]\nmatch = \"/api\"\ntimeout = \"PT5S\"\n"));
        assertEquals(expected, mapper.apply("json").read("{\"routes\":[{\"match\":\"/api\",\"timeout\":\"PT5S\"}]}",
                Routes.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mappers")
    void reportsWhereAndWhat(String name, Function<String, JsonMapper> mapper) {
        JsonMapper yaml = mapper.apply("yaml");
        JsonMapper.Malformed malformed = assertThrows(JsonMapper.Malformed.class,
                () -> yaml.readTree("routes: [\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(malformed.line > 0, "line " + malformed.line);
        JsonMapper.Mismatch mismatch = assertThrows(JsonMapper.Mismatch.class,
                () -> read(yaml, "routes:\n  - match: /api\n    extra: 1\n"));
        assertEquals("routes[0].extra", mismatch.path);
        JsonMapper json = mapper.apply("json");
        assertThrows(JsonMapper.Malformed.class, () -> json.read("{} trailing", Routes.class));
    }

    private static Object read(JsonMapper m, String text) throws Exception {
        return m.convert(m.readTree(text.getBytes(StandardCharsets.UTF_8)), Routes.class);
    }

    @org.junit.jupiter.api.Test
    void withoutJacksonTheMessageSaysWhatToAdd() {
        ClassLoader noJackson = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("tools.jackson.") || name.startsWith("com.fasterxml.jackson.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        DocuconfSetupException e = assertThrows(DocuconfSetupException.class,
                () -> JsonMapper.forFormat("yaml", noJackson));
        assertTrue(e.getMessage().contains("spring-boot-starter-json"), e.getMessage());
        ClassLoader noToml = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.contains(".toml.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        DocuconfSetupException toml = assertThrows(DocuconfSetupException.class,
                () -> JsonMapper.forFormat("toml", noToml));
        assertTrue(toml.getMessage().contains("jackson-dataformat-toml"), toml.getMessage());
    }
}
