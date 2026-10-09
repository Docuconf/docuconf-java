package dev.docuconf.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes {@code META-INF/docuconf/contract.json}: the contract as JSON (the same data as
 * {@code contract.cue}, as {@code cue export} would print it) plus the Java {@link Bindings}.
 */
public final class ContractJson {

    /** Where the processor writes the files, relative to the class output. */
    public static final String LOCATION = "META-INF/docuconf/contract.json";

    /** Where the processor writes the CUE contract, relative to the class output. */
    public static final String CUE_LOCATION = "META-INF/docuconf/contract.cue";

    private ContractJson() {
    }

    /**
     * Writes a contract with its bindings.
     *
     * @param bundle the contract and bindings
     * @return indented JSON
     */
    public static String write(ContractBundle bundle) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("contract", toMap(bundle.contract()));
        Map<String, Object> b = new LinkedHashMap<>();
        List<Object> classes = new ArrayList<>();
        for (Bindings.ClassBinding c : bundle.bindings().classes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("class", c.className());
            m.put("prefix", c.prefix());
            classes.add(m);
        }
        b.put("classes", classes);
        b.put("vars", props(bundle.bindings().vars));
        b.put("files", props(bundle.bindings().files));
        if (bundle.bindings().springFiles != null) {
            b.put("springFiles", new java.util.TreeMap<>(bundle.bindings().springFiles));
        }
        root.put("bindings", b);
        return Json.writePretty(root);
    }

    /**
     * Converts a contract to plain JSON data, as {@code cue export} of the CUE contract would print it, except
     * that fields equal to their meta-schema defaults may be absent.
     *
     * @param c the contract
     * @return a JSON object
     */
    public static Map<String, Object> toMap(Contract c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("apiVersion", "docuconf.dev/v1alpha1");
        m.put("kind", "ConfigContract");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", c.name);
        put(meta, "appVersion", c.appVersion);
        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("language", Contract.LANGUAGE);
        gen.put("sdk", c.sdk);
        gen.put("version", c.sdkVersion);
        meta.put("generator", gen);
        m.put("metadata", meta);
        Map<String, Object> vars = new LinkedHashMap<>();
        for (VarSpec v : c.vars.values()) {
            vars.put(v.name, varMap(v));
        }
        m.put("vars", vars);
        if (!c.files.isEmpty()) {
            Map<String, Object> files = new LinkedHashMap<>();
            for (FileSpec f : c.files.values()) {
                files.put(f.name, fileMap(f));
            }
            m.put("files", files);
        }
        if (!c.overlays.isEmpty()) {
            Map<String, Object> overlays = new LinkedHashMap<>();
            for (OverlaySpec o : c.overlays.values()) {
                Map<String, Object> om = new LinkedHashMap<>();
                put(om, "description", o.description);
                om.put("format", o.format);
                om.put("path", o.path);
                om.put("keySeparator", o.keySeparator);
                om.put("reload", o.reload);
                overlays.put(o.name, om);
            }
            m.put("overlays", overlays);
        }
        if (c.profiles != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("selector", c.profiles.selector);
            p.put("default", c.profiles.defaultProfile);
            Map<String, Object> d = new LinkedHashMap<>();
            c.profiles.defaults.forEach((k, v) -> d.put(k, new LinkedHashMap<>(v)));
            p.put("defaults", d);
            m.put("profiles", p);
        }
        return m;
    }

    private static Map<String, Object> varMap(VarSpec v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", v.type.id());
        m.put("description", v.description);
        put(m, "details", v.details);
        if (v.required) {
            m.put("required", true);
        }
        if (v.secret) {
            m.put("secret", true);
        }
        put(m, "group", v.group);
        put(m, "configKey", v.configKey);
        put(m, "examples", v.examples);
        deprecated(m, v.deprecated);
        put(m, "minLength", v.minLength);
        put(m, "maxLength", v.maxLength);
        put(m, "pattern", v.pattern);
        put(m, "encoding", v.encoding);
        put(m, "min", v.min);
        put(m, "max", v.max);
        put(m, "schemes", v.schemes);
        put(m, "values", v.values);
        put(m, "items", v.items);
        if (v.separator != null && !v.separator.equals(",")) {
            m.put("separator", v.separator);
        }
        put(m, "minItems", v.minItems);
        put(m, "maxItems", v.maxItems);
        put(m, "itemMin", v.itemMin);
        put(m, "itemMax", v.itemMax);
        put(m, "itemMinLength", v.itemMinLength);
        put(m, "itemMaxLength", v.itemMaxLength);
        put(m, "minKeys", v.minKeys);
        put(m, "maxKeys", v.maxKeys);
        put(m, "keyMinLength", v.keyMinLength);
        put(m, "keyMaxLength", v.keyMaxLength);
        put(m, "schema", v.schema);
        put(m, "default", v.defaultValue);
        return m;
    }

    private static Map<String, Object> fileMap(FileSpec f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", f.type.id());
        put(m, "format", f.format);
        m.put("description", f.description);
        put(m, "details", f.details);
        if (f.required) {
            m.put("required", true);
        }
        if (f.secret) {
            m.put("secret", true);
        }
        put(m, "group", f.group);
        m.put("path", f.path);
        put(m, "pathEnv", f.pathEnv);
        if (!"restart".equals(f.reload)) {
            m.put("reload", f.reload);
        }
        put(m, "maxSize", f.maxSize);
        deprecated(m, f.deprecated);
        put(m, "schema", f.schema);
        put(m, "dnsNames", f.dnsNames);
        put(m, "keyAlgorithms", f.keyAlgorithms);
        put(m, "minRemaining", f.minRemaining);
        if (f.requireCA) {
            m.put("requireCA", true);
        }
        if (f.minCertificates != null && f.minCertificates != 1) {
            m.put("minCertificates", f.minCertificates);
        }
        put(m, "passwordVar", f.passwordVar);
        put(m, "pattern", f.pattern);
        put(m, "minLength", f.minLength);
        put(m, "maxLength", f.maxLength);
        return m;
    }

    private static void deprecated(Map<String, Object> m, Deprecation d) {
        if (d != null) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("message", d.message());
            put(dm, "replacedBy", d.replacedBy());
            m.put("deprecated", dm);
        }
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null && !(v instanceof List<?> l && l.isEmpty())) {
            m.put(k, v);
        }
    }

    private static List<Object> props(Map<String, Bindings.PropertyBinding> props) {
        List<Object> out = new ArrayList<>();
        props.forEach((name, p) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("input", name);
            m.put("class", p.className());
            m.put("path", p.javaPath());
            m.put("configKey", p.configKey());
            m.put("javaType", p.javaType());
            put(m, "elementType", p.elementType());
            put(m, "durationUnit", p.durationUnit());
            out.add(m);
        });
        return out;
    }

    /**
     * Reads a contract with bindings.
     *
     * @param in the JSON document
     * @return the bundle
     * @throws IOException if it cannot be read
     * @throws IllegalArgumentException if it is malformed
     */
    public static ContractBundle read(InputStream in) throws IOException {
        return read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * Reads a contract with bindings.
     *
     * @param json the JSON document
     * @return the bundle
     */
    @SuppressWarnings("unchecked")
    public static ContractBundle read(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        Contract c = fromMap((Map<String, Object>) root.get("contract"));
        Bindings bindings = new Bindings();
        Map<String, Object> b = (Map<String, Object>) root.getOrDefault("bindings", Map.of());
        for (Object o : (List<Object>) b.getOrDefault("classes", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            bindings.classes.add(new Bindings.ClassBinding((String) m.get("class"), (String) m.get("prefix")));
        }
        readProps((List<Object>) b.getOrDefault("vars", List.of()), bindings.vars);
        readProps((List<Object>) b.getOrDefault("files", List.of()), bindings.files);
        if (b.get("springFiles") instanceof Map<?, ?> hashes) {
            bindings.springFiles = new java.util.TreeMap<>();
            hashes.forEach((k, v) -> bindings.springFiles.put((String) k, (String) v));
        }
        return new ContractBundle(c, bindings);
    }

    @SuppressWarnings("unchecked")
    private static void readProps(List<Object> list, Map<String, Bindings.PropertyBinding> into) {
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            into.put((String) m.get("input"), new Bindings.PropertyBinding((String) m.get("class"),
                    (String) m.get("path"), (String) m.get("configKey"), (String) m.get("javaType"),
                    (String) m.get("elementType"), (String) m.get("durationUnit")));
        }
    }

    /**
     * Reads a contract from plain JSON data ({@code cue export} output, or {@link #toMap(Contract)}).
     *
     * @param m the JSON object
     * @return the contract
     */
    @SuppressWarnings("unchecked")
    public static Contract fromMap(Map<String, Object> m) {
        Contract c = new Contract();
        Map<String, Object> meta = (Map<String, Object>) m.get("metadata");
        c.name = (String) meta.get("name");
        c.appVersion = (String) meta.get("appVersion");
        Map<String, Object> gen = (Map<String, Object>) meta.getOrDefault("generator", Map.of());
        c.sdk = (String) gen.getOrDefault("sdk", c.sdk);
        c.sdkVersion = (String) gen.getOrDefault("version", c.sdkVersion);
        Map<String, Object> vars = (Map<String, Object>) m.getOrDefault("vars", Map.of());
        vars.forEach((name, o) -> {
            Map<String, Object> vm = (Map<String, Object>) o;
            VarSpec v = new VarSpec(name, VarType.of((String) vm.get("type")), (String) vm.get("description"));
            v.details = (String) vm.get("details");
            v.required = Boolean.TRUE.equals(vm.get("required"));
            v.secret = Boolean.TRUE.equals(vm.get("secret"));
            v.group = (String) vm.get("group");
            v.configKey = (String) vm.get("configKey");
            v.examples = (List<String>) vm.get("examples");
            v.deprecated = deprecation(vm.get("deprecated"));
            v.minLength = integer(vm.get("minLength"));
            v.maxLength = integer(vm.get("maxLength"));
            v.pattern = (String) vm.get("pattern");
            v.encoding = (String) vm.get("encoding");
            v.min = vm.get("min");
            v.max = vm.get("max");
            v.schemes = (List<String>) vm.get("schemes");
            v.values = (List<String>) vm.get("values");
            v.items = (String) vm.get("items");
            v.separator = (String) vm.get("separator");
            boolean listLike = v.type == VarType.LIST || v.type == VarType.KEY_SET;
            if (listLike && "csv".equals(v.encoding == null ? "csv" : v.encoding) && v.separator == null) {
                v.separator = ",";
            }
            v.minItems = integer(vm.get("minItems"));
            v.maxItems = integer(vm.get("maxItems"));
            v.itemMin = longValue(vm.get("itemMin"));
            v.itemMax = longValue(vm.get("itemMax"));
            v.itemMinLength = integer(vm.get("itemMinLength"));
            v.itemMaxLength = integer(vm.get("itemMaxLength"));
            v.minKeys = integer(vm.get("minKeys"));
            v.maxKeys = integer(vm.get("maxKeys"));
            v.keyMinLength = integer(vm.get("keyMinLength"));
            v.keyMaxLength = integer(vm.get("keyMaxLength"));
            v.schema = (Map<String, Object>) vm.get("schema");
            v.defaultValue = vm.get("default");
            c.vars.put(name, v);
        });
        Map<String, Object> files = (Map<String, Object>) m.getOrDefault("files", Map.of());
        files.forEach((name, o) -> {
            Map<String, Object> fm = (Map<String, Object>) o;
            FileSpec f = new FileSpec(name, FileType.of((String) fm.get("type")), (String) fm.get("description"),
                    (String) fm.get("path"));
            f.details = (String) fm.get("details");
            f.format = (String) fm.get("format");
            f.required = Boolean.TRUE.equals(fm.get("required"));
            f.secret = f.secret || Boolean.TRUE.equals(fm.get("secret"));
            f.group = (String) fm.get("group");
            f.pathEnv = (String) fm.get("pathEnv");
            f.reload = (String) fm.getOrDefault("reload", "restart");
            Object maxSize = fm.get("maxSize");
            f.maxSize = maxSize == null ? null : ((Number) maxSize).longValue();
            f.deprecated = deprecation(fm.get("deprecated"));
            f.schema = (Map<String, Object>) fm.get("schema");
            f.dnsNames = (List<String>) fm.get("dnsNames");
            f.keyAlgorithms = (List<String>) fm.get("keyAlgorithms");
            f.minRemaining = (String) fm.get("minRemaining");
            f.requireCA = Boolean.TRUE.equals(fm.get("requireCA"));
            f.minCertificates = integer(fm.get("minCertificates"));
            f.passwordVar = (String) fm.get("passwordVar");
            f.pattern = (String) fm.get("pattern");
            f.minLength = integer(fm.get("minLength"));
            f.maxLength = integer(fm.get("maxLength"));
            c.files.put(name, f);
        });
        Map<String, Object> overlays = (Map<String, Object>) m.getOrDefault("overlays", Map.of());
        overlays.forEach((name, o) -> {
            Map<String, Object> om = (Map<String, Object>) o;
            OverlaySpec spec = new OverlaySpec(name, (String) om.get("path"));
            spec.description = (String) om.get("description");
            spec.format = (String) om.getOrDefault("format", spec.format);
            spec.keySeparator = (String) om.getOrDefault("keySeparator", spec.keySeparator);
            spec.reload = (String) om.getOrDefault("reload", "restart");
            c.overlays.put(name, spec);
        });
        Map<String, Object> p = (Map<String, Object>) m.get("profiles");
        if (p != null) {
            c.profiles = new Profiles();
            c.profiles.selector = (String) p.get("selector");
            c.profiles.defaultProfile = (String) p.get("default");
            ((Map<String, Object>) p.getOrDefault("defaults", Map.of())).forEach(
                    (k, v) -> c.profiles.defaults.put(k, new java.util.TreeMap<>((Map<String, Object>) v)));
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Deprecation deprecation(Object o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        return new Deprecation((String) m.get("message"), (String) m.get("replacedBy"));
    }

    private static Long longValue(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static Integer integer(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }
}
