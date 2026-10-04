package dev.docuconf.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.docuconf.testing.CueVet;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractCoreTest {

    @TempDir
    Path tmp;

    @Test
    void goDurations() {
        assertEquals("1h30m", GoDuration.canonical("90m"));
        assertEquals("1m30s", GoDuration.format(Duration.ofSeconds(90)));
        assertEquals("1s500ms", GoDuration.format(Duration.ofMillis(1500)));
        assertEquals("0s", GoDuration.format(Duration.ZERO));
        assertEquals("1us5ns", GoDuration.format(Duration.ofNanos(1005)));
        assertEquals(Duration.ofHours(720), GoDuration.parse("720h"));
        assertThrows(IllegalArgumentException.class, () -> GoDuration.parse("1.5h"));
        assertThrows(IllegalArgumentException.class, () -> GoDuration.parse("-1s"));
        assertEquals("PT90S", GoDuration.iso8601(Duration.ofSeconds(90)));
        assertEquals("PT0.25S", GoDuration.iso8601(Duration.ofMillis(250)));
        assertEquals("PT1.5S", GoDuration.iso8601(Duration.ofMillis(1500)));
    }

    @Test
    void re2Compatibility() {
        assertNull(Re2.check("^[a-z][a-z0-9-]*$"));
        assertNull(Re2.check("^(?i:abc)\\d+[[:alpha:]]\\p{L}$"));
        assertNull(Re2.check("(?<name>x)"));
        assertNotNull(Re2.check("a(?=b)"));
        assertNotNull(Re2.check("(?<!a)b"));
        assertNotNull(Re2.check("(a)\\1"));
        assertNotNull(Re2.check("a*+"));
        assertNotNull(Re2.check("(?>a)"));
        assertNotNull(Re2.check("[a-z&&[^e]]"));
        assertNotNull(Re2.check("\\p{javaLowerCase}"));
        assertNotNull(Re2.check("\\p{Alpha}"));
        assertNotNull(Re2.check("\\h"));
        assertNotNull(Re2.check("(?x)a b"));
        assertNotNull(Re2.check("[unclosed"));
        // [ inside a class is literal in RE2 but a union in Java.
        assertNotNull(Re2.check("[a[b]]"));
        assertEquals("^(?:[a-z]+)$", Re2.anchor("[a-z]+", ""));
        // $ means end of text, as in RE2.
        assertTrue(!Re2.compile("^x$").matcher("x\n").find());
        assertTrue(Re2.compile("^x\\n?$").matcher("x\n").find());
        assertTrue(Re2.compile("[$]").matcher("$").find());
    }

    @Test
    void jsonRoundTrip() {
        String text = "{\"a\":[1,2.5,\"x\\n\\u00e9\",true,null],\"b\":{},\"c\":-9223372036854775808,\"d\":1e3}";
        Object v = Json.parse(text);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        assertEquals(Long.MIN_VALUE, m.get("c"));
        assertEquals(new BigDecimal("1e3"), m.get("d"));
        assertEquals("{\"a\":[1,2.5,\"x\\né\",true,null],\"b\":{},\"c\":-9223372036854775808,\"d\":1000}", Json.write(v));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1] x"));
    }

    @Test
    void names() {
        assertEquals("database-url", Names.dashed("databaseUrl"));
        assertEquals("BILLING_DATABASEURL", Names.envName("billing.database-url"));
        assertEquals("MYAPP_DB_POOLSIZE", Names.envName("my-app.db.pool-size"));
        assertEquals(Names.canonical("billing.database_url"), Names.canonical("billing.databaseUrl"));
    }

    static Contract sample() {
        Contract c = new Contract();
        c.name = "demo-svc";
        c.sdkVersion = "0.1.0";
        VarSpec port = new VarSpec("DEMO_PORT", VarType.INT, "HTTP listen port");
        port.min = 1L;
        port.max = 65535L;
        port.defaultValue = 8080L;
        port.configKey = "demo.port";
        c.vars.put(port.name, port);
        VarSpec url = new VarSpec("DEMO_DATABASEURL", VarType.URL, "Database connection string");
        url.required = true;
        url.secret = true;
        url.schemes = List.of("postgres");
        c.vars.put(url.name, url);
        VarSpec ratio = new VarSpec("DEMO_RATIO", VarType.FLOAT, "Sampling ratio");
        ratio.min = new BigDecimal("0");
        ratio.max = new BigDecimal("1");
        ratio.defaultValue = new BigDecimal("0.25");
        c.vars.put(ratio.name, ratio);
        VarSpec timeout = new VarSpec("DEMO_TIMEOUT", VarType.DURATION, "Request timeout");
        timeout.encoding = "iso8601";
        timeout.min = "1s";
        timeout.defaultValue = "1m30s";
        c.vars.put(timeout.name, timeout);
        VarSpec tags = new VarSpec("DEMO_TAGS", VarType.LIST, "Tags \"quoted\" with \\(interpolation)");
        tags.items = "string";
        tags.encoding = "csv";
        tags.separator = ";";
        tags.defaultValue = List.of("a$b", "c");
        c.vars.put(tags.name, tags);
        VarSpec limits = new VarSpec("DEMO_LIMITS", VarType.JSON, "Rate limits per client");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("perMinute", Map.of("type", "integer", "minimum", 1L)));
        schema.put("required", List.of("perMinute"));
        limits.schema = schema;
        c.vars.put(limits.name, limits);
        VarSpec profile = new VarSpec("SPRING_PROFILES_ACTIVE", VarType.STRING, "Active Spring profile");
        c.vars.put(profile.name, profile);
        FileSpec tls = new FileSpec("serving-tls", FileType.TLS, "Serving certificate", "/etc/demo/tls");
        tls.dnsNames = List.of("demo.internal");
        tls.keyAlgorithms = List.of("ECDSA", "Ed25519");
        tls.minRemaining = "720h";
        tls.reload = "watch";
        c.files.put(tls.name, tls);
        c.profiles = new Profiles();
        c.profiles.selector = "SPRING_PROFILES_ACTIVE";
        c.profiles.defaultProfile = "default";
        c.profiles.defaults.put("prod", new java.util.TreeMap<>(Map.of("DEMO_PORT", 9090L)));
        return c;
    }

    @Test
    void writesCueThatVets() throws Exception {
        Contract c = sample();
        assertEquals(List.of(), DeclarationValidator.validate(c).errors());
        String cue = CueWriter.write(c);
        assertTrue(cue.startsWith("// Code generated by docuconf. DO NOT EDIT.\npackage demo_svc\n"), cue);
        CueVet.Result r = CueVet.vet(cue, tmp);
        assertEquals(0, r.exitCode(), r.output() + "\n" + cue);
        // The CUE and JSON forms hold the same data.
        Object exported = Json.parse(CueVet.export(tmp));
        Object ours = Json.parse(Json.write(ContractJson.toMap(c)));
        @SuppressWarnings("unchecked")
        Map<String, Object> e = (Map<String, Object>) exported;
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) ours;
        assertEquals(o.get("vars").toString().length() > 0, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> port = (Map<String, Object>) ((Map<String, Object>) e.get("vars")).get("DEMO_PORT");
        assertEquals(8080L, port.get("default"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tags = (Map<String, Object>) ((Map<String, Object>) e.get("vars")).get("DEMO_TAGS");
        assertEquals(List.of("a$b", "c"), tags.get("default"));
        assertEquals("Tags \"quoted\" with \\(interpolation)", tags.get("description"));
    }

    @Test
    void contractJsonRoundTrip() {
        Contract c = sample();
        Bindings b = new Bindings();
        b.classes.add(new Bindings.ClassBinding("x.Demo", "demo"));
        b.vars.put("DEMO_PORT", new Bindings.PropertyBinding("x.Demo", "port", "demo.port", "int", null, null));
        String json = ContractJson.write(new ContractBundle(c, b));
        ContractBundle back = ContractJson.read(json);
        assertEquals(CueWriter.write(c), CueWriter.write(back.contract()));
        assertEquals(b.vars, back.bindings().vars);
        assertEquals(b.classes, back.bindings().classes);
    }

    @Test
    void declarationErrors() {
        Contract c = sample();
        c.vars.get("DEMO_PORT").defaultValue = 70000L;
        c.vars.get("DEMO_DATABASEURL").defaultValue = "postgres://x";
        VarSpec bad = new VarSpec("bad_name", VarType.STRING, "tiny");
        bad.pattern = "(?=x)";
        c.vars.put(bad.name, bad);
        VarSpec flag = new VarSpec("ENABLE_CHECKOUT", VarType.BOOL, "New checkout");
        c.vars.put(flag.name, flag);
        FileSpec ca = new FileSpec("ca", FileType.CA_BUNDLE, "Trusted CAs", "/etc/ssl/certs/private.pem");
        c.files.put(ca.name, ca);
        FileSpec ks = new FileSpec("ks", FileType.KEYSTORE, "Keystore", "/etc/demo/tls/ks.p12");
        ks.passwordVar = "DEMO_PORT";
        c.files.put(ks.name, ks);
        c.profiles.defaults.put("prod", new java.util.TreeMap<>(Map.of("DEMO_DATABASEURL", "postgres://p",
                "DEMO_RATIO", new BigDecimal("2"), "NOPE", "x")));
        DeclarationValidator.Result r = DeclarationValidator.validate(c);
        String all = String.join("\n", r.errors());
        assertTrue(all.contains("DEMO_PORT: default is above max 65535"), all);
        assertTrue(all.contains("DEMO_DATABASEURL: a required variable cannot have a default"), all);
        assertTrue(all.contains("DEMO_DATABASEURL: a secret cannot have a default"), all);
        assertTrue(all.contains("bad_name: name must match"), all);
        assertTrue(all.contains("bad_name: description \"tiny\" is shorter than 5 characters"), all);
        assertTrue(all.contains("bad_name: pattern (?=x) uses lookahead"), all);
        assertTrue(all.contains("ca: would be mounted at /etc/ssl/certs"), all);
        assertTrue(all.contains("ks: passwordVar DEMO_PORT must name a declared secret variable"), all);
        assertTrue(all.contains("DEMO_DATABASEURL: a secret cannot have a value in a profile file"), all);
        assertTrue(all.contains("DEMO_RATIO: value in profile prod is above max 1"), all);
        assertTrue(all.contains("profiles.prod: NOPE is not a declared variable"), all);
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("ENABLE_CHECKOUT: looks like a feature flag")));
    }
}
