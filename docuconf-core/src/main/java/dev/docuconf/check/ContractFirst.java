package dev.docuconf.check;

import dev.docuconf.contract.Contract;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.contract.DeclarationValidator;
import dev.docuconf.contract.FileSpec;
import dev.docuconf.contract.FileType;
import dev.docuconf.contract.GoDuration;
import dev.docuconf.contract.Json;
import dev.docuconf.contract.VarSpec;
import dev.docuconf.contract.VarType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;

/**
 * Contract-first mode (SPEC §11.2 item 11): validates an environment against a contract given as JSON, such as
 * {@code cue export contract.cue} prints it, with no Java declaration, and returns typed values.
 *
 * <p>Every wire encoding is parsed (SPEC §5): lists as {@code csv} with their separator, {@code json} or
 * {@code indexed} ({@code NAME__0}, {@code NAME__1}, ..., numbered from 0 with no gap); durations as {@code go}, {@code iso8601},
 * {@code seconds} or {@code timespan}, each exactly as SPEC §5 writes it. Layers apply in the order of SPEC §4.7: the
 * variable's default, the selected profile's default (SPEC §4.4), a config-file overlay, then the environment. File
 * inputs (SPEC §4.6) and overlays are read under {@code DOCUCONF_FILE_ROOT}. Constraints are checked by {@link VarChecker}, the same code
 * that checks declared defaults at compile time and Spring-bound values at startup.
 *
 * <p>Typed values: {@code String} for string, url and enum; {@code Long} for int; {@code BigDecimal} for float;
 * {@code Boolean}; {@link Duration}; a {@code List} of {@code String} or {@code Long}; a
 * {@link dev.docuconf.KeySet} for a key set; for json, the parsed value
 * ({@code Map}, {@code List}, {@code String}, {@code Long}, {@code BigDecimal}, {@code Boolean} or {@code null}).
 * An optional variable that is not set and has no default is {@code null}.
 *
 * <pre>{@code
 * ContractFirst.Result r = ContractFirst.load(Files.readString(Path.of("contract.json")), System.getenv());
 * long port = (Long) r.require().get("PORT");
 * }</pre>
 */
public final class ContractFirst {

    private ContractFirst() {
    }

    /**
     * What loading found.
     *
     * @param values each variable's typed value, by name, in contract order; {@code null} when unset
     * @param files each file input's value, as {@link FileChecker.Outcome#value()} describes it, by name; a
     *     {@code json} config file is parsed
     * @param violations every problem, empty when the configuration is valid; messages never hold secret values
     * @param warnings hints, such as deprecated variables that are set
     */
    public record Result(Map<String, Object> values, Map<String, Object> files, List<Violation> violations,
            List<String> warnings) {

        /**
         * Whether there are no violations.
         *
         * @return whether the configuration is valid
         */
        public boolean ok() {
            return violations.isEmpty();
        }

        /**
         * The typed values, or an exception listing every violation.
         *
         * @return the values
         * @throws InvalidConfigurationException when there are violations
         */
        public Map<String, Object> require() {
            if (!ok()) {
                throw new InvalidConfigurationException(violations);
            }
            return values;
        }
    }

    /** Thrown by {@link Result#require()} and {@link #boot}: the configuration is invalid. */
    public static final class InvalidConfigurationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final transient List<Violation> violations;

        /**
         * Creates the exception.
         *
         * @param violations every problem
         */
        public InvalidConfigurationException(List<Violation> violations) {
            super(message(violations));
            this.violations = List.copyOf(violations);
        }

        /**
         * Every problem.
         *
         * @return the violations
         */
        public List<Violation> violations() {
            return violations;
        }

        private static String message(List<Violation> violations) {
            return report(violations).stripTrailing();
        }
    }

    /**
     * Loads the process environment against a contract file at startup. On failure, writes the violations to the
     * termination log (SPEC §11.2) and throws.
     *
     * @param contractJson the contract as JSON
     * @return the typed values
     * @throws IOException if the contract cannot be read
     * @throws InvalidConfigurationException when the environment does not satisfy the contract
     */
    public static Map<String, Object> boot(Path contractJson) throws IOException {
        Map<String, String> env = System.getenv();
        Result r = load(Files.readString(contractJson, StandardCharsets.UTF_8), env);
        if (!r.ok()) {
            TerminationLog.write(r.violations(), env::get);
        }
        return r.require();
    }

    /**
     * Loads the process environment against a contract file at startup, or ends the process: on failure it prints
     * {@code docuconf: N configuration problems:} and one line per problem to standard error, writes the
     * termination log and exits with status 1, without a stack trace.
     *
     * <pre>{@code
     * Map<String, Object> config = ContractFirst.bootOrExit(Path.of("contract.json"));
     * }</pre>
     *
     * @param contractJson the contract as JSON
     * @return the typed values
     */
    public static Map<String, Object> bootOrExit(Path contractJson) {
        Map<String, String> env = System.getenv();
        Result r;
        try {
            r = load(Files.readString(contractJson, StandardCharsets.UTF_8), env);
        } catch (IOException | RuntimeException e) {
            System.err.println("docuconf: cannot load the contract " + contractJson + ": " + e.getMessage());
            System.exit(1);
            return Map.of();
        }
        for (String w : r.warnings()) {
            System.err.println("docuconf: " + w);
        }
        if (!r.ok()) {
            TerminationLog.write(r.violations(), env::get);
            System.err.print(report(r.violations()));
            System.exit(1);
        }
        return r.values();
    }

    /**
     * The report {@link #bootOrExit} prints: {@code docuconf: N configuration problems:} and one line per problem.
     *
     * @param violations the problems
     * @return the text, ending in a newline
     */
    public static String report(List<Violation> violations) {
        StringBuilder b = new StringBuilder("docuconf: ").append(violations.size())
                .append(violations.size() == 1 ? " configuration problem:\n" : " configuration problems:\n");
        for (Violation v : violations) {
            b.append("  ").append(v).append('\n');
        }
        return b.toString();
    }

    /**
     * Loads an environment against a contract given as JSON: {@code cue export} output, or the
     * {@code META-INF/docuconf/contract.json} the annotation processor writes.
     *
     * @param contractJson the contract
     * @param env the whole environment
     * @return values and violations
     * @throws IllegalArgumentException if the contract is not valid, or declares {@code reload: watch}, which this
     *     mode cannot keep
     */
    @SuppressWarnings("unchecked")
    public static Result load(String contractJson, Map<String, String> env) {
        Object doc = Json.parse(contractJson);
        if (!(doc instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("a contract is a JSON object");
        }
        Map<String, Object> root = (Map<String, Object>) m;
        if (!root.containsKey("kind") && root.get("contract") instanceof Map<?, ?> inner) {
            root = (Map<String, Object>) inner;
        }
        if (!"ConfigContract".equals(root.get("kind"))) {
            throw new IllegalArgumentException("not a docuconf contract: kind is " + root.get("kind"));
        }
        return load(ContractJson.fromMap(root), env);
    }

    /**
     * Loads an environment against a contract.
     *
     * @param contract the contract
     * @param env the whole environment
     * @return values and violations
     * @throws IllegalArgumentException if the contract is not valid, or declares {@code reload: watch}, which this
     *     mode cannot keep
     */
    public static Result load(Contract contract, Map<String, String> env) {
        DeclarationValidator.Result decl = DeclarationValidator.validate(contract);
        if (!decl.errors().isEmpty()) {
            throw new IllegalArgumentException("invalid contract: " + String.join("; ", decl.errors()));
        }
        List<String> watched = watchedInputs(contract);
        if (!watched.isEmpty()) {
            throw new IllegalArgumentException("contract-first mode reads each input once, so it cannot reload "
                    + String.join(", ", watched) + " declared reload: watch (SPEC section 11.2 item 8); declare"
                    + " reload: restart, or use the Spring Boot integration, which reloads watched inputs");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        List<Violation> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> profile = profileDefaults(contract, env);
        Map<String, Overlays.Value> overlay = Overlays.load(contract, env::get, violations, warnings);
        for (VarSpec v : contract.vars.values()) {
            values.put(v.name, var(v, env, profile, overlay.get(v.name), violations, warnings));
        }
        warnings.addAll(typoHints(contract, env));
        Map<String, Object> files = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (FileSpec f : contract.files.values()) {
            files.put(f.name, file(f, env::get, now, violations, warnings));
        }
        return new Result(Collections.unmodifiableMap(values), Collections.unmodifiableMap(files),
                List.copyOf(violations), List.copyOf(warnings));
    }

    /**
     * The file inputs and overlays a contract declares {@code reload: watch}. Contract-first mode returns values
     * read once, so it rejects them at load rather than record a promise it does not keep (SPEC §11.2 item 8).
     */
    private static List<String> watchedInputs(Contract contract) {
        List<String> out = new ArrayList<>();
        for (FileSpec f : contract.files.values()) {
            if ("watch".equals(f.reload)) {
                out.add("file input " + f.name);
            }
        }
        for (dev.docuconf.contract.OverlaySpec o : contract.overlays.values()) {
            if ("watch".equals(o.reload)) {
                out.add("overlay " + o.name);
            }
        }
        return out;
    }

    /**
     * Set variables that are not declared but are within two edits of a declared one sharing its first segment
     * ({@code ORDERS_PROT} for {@code ORDERS_PORT}): most likely typos. The values are never printed.
     *
     * @param contract the contract
     * @param env the environment
     * @return one warning per likely typo
     */
    public static List<String> typoHints(Contract contract, Map<String, String> env) {
        Set<String> declared = new LinkedHashSet<>(contract.vars.keySet());
        for (FileSpec f : contract.files.values()) {
            if (f.pathEnv != null) {
                declared.add(f.pathEnv);
            }
        }
        List<String> out = new ArrayList<>();
        for (String name : new TreeSet<>(env.keySet())) {
            int us = name.indexOf('_');
            if (declared.contains(name) || us <= 0) {
                continue;
            }
            String prefix = name.substring(0, us + 1);
            if (declared.stream().anyMatch(d -> name.startsWith(d + "_"))) {
                continue; // an indexed list item, NAME__0
            }
            String best = null;
            int bestDistance = 3;
            for (String d : declared) {
                if (!d.startsWith(prefix)) {
                    continue;
                }
                int distance = dev.docuconf.contract.Names.editDistance(name, d, bestDistance);
                if (distance < bestDistance) {
                    best = d;
                    bestDistance = distance;
                }
            }
            if (best != null) {
                out.add(name + " is set but not declared; did you mean " + best + "?");
            }
        }
        return out;
    }

    /**
     * The defaults of the selected profile (SPEC §4.4): the selector variable's value, else its default, else
     * {@code profiles.default}.
     */
    private static Map<String, Object> profileDefaults(Contract contract, Map<String, String> env) {
        if (contract.profiles == null || contract.profiles.selector == null) {
            return Map.of();
        }
        String selected = env.get(contract.profiles.selector);
        if (selected == null || selected.isEmpty()) {
            VarSpec selector = contract.vars.get(contract.profiles.selector);
            selected = selector != null && selector.defaultValue instanceof String d ? d
                    : contract.profiles.defaultProfile;
        }
        Map<String, Object> defaults = selected == null ? null : contract.profiles.defaults.get(selected);
        return defaults == null ? Map.of() : defaults;
    }

    private static Object var(VarSpec v, Map<String, String> env, Map<String, Object> profile,
            Overlays.Value overlay, List<Violation> out, List<String> warnings) {
        boolean indexed = (v.type == VarType.LIST || v.type == VarType.KEY_SET) && "indexed".equals(v.encoding);
        String raw = null;
        Map<String, String> items = null;
        if (indexed) {
            items = indexedItems(env, v.name);
        } else {
            raw = env.get(v.name);
        }
        boolean present = indexed ? items != null : raw != null && (!raw.isEmpty() || v.type == VarType.STRING);
        if (present && overlay != null) {
            // SPEC §4.7: the environment wins; the platform rejects this before deploy.
            warnings.add(v.name + " is set both in the environment and in overlay " + overlay.overlay()
                    + "; the environment wins");
        }
        if (!present && overlay != null) {
            return fromOverlay(v, overlay, out, warnings);
        }
        if (!present) {
            if (profile.containsKey(v.name)) {
                return fromContract(v, profile.get(v.name));
            }
            if (v.required) {
                out.add(new Violation(Code.MISSING_REQUIRED, v.name, "is required but not set"));
                return null;
            }
            return fromContract(v, v.defaultValue);
        }
        // SPEC §11.2: a secret still holding vault:..., op://... or ref+... means its injector did not run.
        Violation reference = InjectorReference.check(v, raw);
        if (reference == null && items != null) {
            for (String item : items.values()) {
                reference = reference != null ? reference : InjectorReference.check(v, item);
            }
        }
        if (reference != null) {
            out.add(reference);
            return null;
        }
        deprecationWarning(v, warnings);
        String shown = v.secret || raw == null ? "" : " (got " + quote(raw) + ")";
        Object value;
        try {
            value = parse(v, raw, items == null ? null : inOrder(v.name, items));
        } catch (WireFormat.WireException e) {
            out.add(new Violation(e.code(), v.name, e.getMessage() + shown));
            return null;
        }
        return checked(v, value, raw, out);
    }

    /**
     * SPEC §11.2: a deprecated input that is set loads as usual, with a warning naming it and its message, never
     * its value.
     */
    private static void deprecationWarning(VarSpec v, List<String> warnings) {
        if (v.deprecated != null) {
            warnings.add(deprecationMessage(v.name, v.deprecated));
        }
    }

    /**
     * The warning for a deprecated input that is set: its name, its message and its replacement, never its value.
     *
     * @param input the variable or file input
     * @param d its deprecation
     * @return the warning
     */
    public static String deprecationMessage(String input, dev.docuconf.contract.Deprecation d) {
        return input + " is deprecated: " + d.message() + (d.replacedBy() == null ? "" : "; use " + d.replacedBy());
    }

    /** Checks a parsed value against its constraints and schema; the value, or {@code null} after a violation. */
    private static Object checked(VarSpec v, Object value, String wire, List<Violation> out) {
        List<Violation> found = new ArrayList<>(VarChecker.check(v, value, wire));
        if (!found.isEmpty()) {
            out.addAll(found);
            return null;
        }
        if (v.type == VarType.JSON && v.schema != null) {
            for (String problem : JsonSchema.validate(v.schema, value, v.secret)) {
                found.add(new Violation(Code.SCHEMA_MISMATCH, v.name, problem));
            }
        }
        if (!found.isEmpty()) {
            out.addAll(found);
            return null;
        }
        return value;
    }

    /**
     * A value from a config-file overlay (SPEC §4.7): its native value converted to the wire string it stands for,
     * then parsed in the variable's own encoding and checked exactly like an env value.
     */
    private static Object fromOverlay(VarSpec v, Overlays.Value overlay, List<Violation> out,
            List<String> warnings) {
        String where = " in overlay " + overlay.overlay();
        if (v.secret) {
            out.add(new Violation(Code.INVALID_TYPE, v.name, "is a secret, so it cannot come from" + where
                    + "; set it in the environment from a Secret"));
            return null;
        }
        Object nativeValue = overlay.value();
        deprecationWarning(v, warnings);
        try {
            if (v.type == VarType.JSON) {
                String compact = Json.write(nativeValue);
                return checked(v, Json.parse(compact), null, out);
            }
            if (v.type == VarType.LIST) {
                if (!(nativeValue instanceof List<?> l)) {
                    throw new WireFormat.WireException(Code.INVALID_TYPE, "is not a list" + where);
                }
                List<String> items = new ArrayList<>();
                for (Object item : l) {
                    items.add(Overlays.wire(item, where));
                }
                return checked(v, parse(v, null, items), null, out);
            }
            String raw = Overlays.wire(nativeValue, where);
            if (raw.isEmpty() && v.type != VarType.STRING) {
                return fromContract(v, v.defaultValue);
            }
            String shown = " (got " + quote(raw) + ")";
            try {
                return checked(v, parse(v, raw, null), raw, out);
            } catch (WireFormat.WireException e) {
                throw new WireFormat.WireException(e.code(), e.getMessage() + where + shown);
            }
        } catch (WireFormat.WireException e) {
            out.add(new Violation(e.code(), v.name, e.getMessage()));
            return null;
        }
    }

    /**
     * Parses a raw value into the form {@link VarChecker} takes, which is also the typed value.
     *
     * @param itemsInOrder for a list or key set, its items when they come one by one (the indexed encoding, or an
     *     overlay's list); else {@code null}
     */
    private static Object parse(VarSpec v, String raw, List<String> itemsInOrder) {
        return switch (v.type) {
            case STRING, URL, ENUM -> raw;
            case INT -> WireFormat.parseInt(raw);
            case FLOAT -> WireFormat.parseFloat(raw);
            case BOOL -> WireFormat.parseBool(raw);
            case DURATION -> WireFormat.parseDuration(raw, v.encoding);
            case LIST -> list(v, raw, itemsInOrder);
            case KEY_SET -> dev.docuconf.KeySet.of(keys(v, raw, itemsInOrder));
            case JSON -> {
                try {
                    yield Json.parse(raw);
                } catch (IllegalArgumentException e) {
                    throw new WireFormat.WireException(Code.INVALID_TYPE, "is not valid JSON");
                }
            }
        };
    }

    /** A key set's keys, split like a list of strings (SPEC §5): never trimmed, so an empty key stays. */
    private static List<String> keys(VarSpec v, String raw, List<String> itemsInOrder) {
        if (itemsInOrder != null) {
            return itemsInOrder;
        }
        if ("json".equals(v.encoding)) {
            List<String> out = new ArrayList<>();
            for (Object o : WireFormat.parseJsonList(raw, "string")) {
                out.add((String) o);
            }
            return out;
        }
        return WireFormat.splitCsv(raw, v.separator);
    }

    private static List<Object> list(VarSpec v, String raw, List<String> itemsInOrder) {
        String encoding = v.encoding == null ? "csv" : v.encoding;
        if (itemsInOrder == null && encoding.equals("json")) {
            return WireFormat.parseJsonList(raw, v.items);
        }
        List<String> items = itemsInOrder != null ? itemsInOrder : WireFormat.splitCsv(raw, v.separator);
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!"int".equals(v.items)) {
                out.add(items.get(i));
                continue;
            }
            try {
                out.add(WireFormat.parseInt(items.get(i)));
            } catch (WireFormat.WireException e) {
                throw new WireFormat.WireException(e.code(), "item " + i + " " + e.getMessage()
                        + (v.secret ? "" : " (got " + quote(items.get(i)) + ")"));
            }
        }
        return out;
    }

    /**
     * The items of an indexed list, in order (SPEC §5): every {@code NAME__<n>} whose {@code <n>} is a decimal
     * index with no leading zero; other suffixes ({@code NAME__HOST}) are not items. {@code null} when no item is
     * set.
     */
    private static Map<String, String> indexedItems(Map<String, String> env, String name) {
        String prefix = name + "__";
        Map<String, String> items = null;
        for (Map.Entry<String, String> e : env.entrySet()) {
            String key = e.getKey();
            if (key.startsWith(prefix) && WireFormat.isIndex(key.substring(prefix.length())) && e.getValue() != null) {
                if (items == null) {
                    items = new HashMap<>();
                }
                items.put(key.substring(prefix.length()), e.getValue());
            }
        }
        return items;
    }

    /** The items numbered 0, 1, ...; a gap is {@code invalid_type}, as hosts that stop at it read fewer items. */
    private static List<String> inOrder(String name, Map<String, String> items) {
        List<String> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(Integer.toString(i));
            if (item == null) {
                throw new WireFormat.WireException(Code.INVALID_TYPE, WireFormat.gapMessage(name + "__", items.keySet()));
            }
            out.add(item);
        }
        return out;
    }

    /** A default from the contract, in the typed form. */
    private static Object fromContract(VarSpec v, Object value) {
        if (value == null) {
            return null;
        }
        return switch (v.type) {
            case INT -> ((Number) value).longValue();
            case FLOAT -> value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
            case DURATION -> GoDuration.parse((String) value);
            case LIST -> {
                List<Object> items = new ArrayList<>();
                for (Object o : (List<?>) value) {
                    items.add("int".equals(v.items) ? (Object) ((Number) o).longValue() : o);
                }
                yield Collections.unmodifiableList(items);
            }
            default -> value;
        };
    }

    private static Object file(FileSpec f, Function<String, String> env, Instant now, List<Violation> out,
            List<String> warnings) {
        Path path = FileChecker.resolve(f, env);
        FileChecker.Outcome o = FileChecker.check(f, path, now, env);
        out.addAll(o.violations());
        if (o.present() && f.deprecated != null) {
            warnings.add(deprecationMessage(f.name, f.deprecated));
        }
        if (!o.violations().isEmpty() || o.value() == null) {
            return null;
        }
        if (f.type != FileType.CONFIG) {
            return o.value();
        }
        Object doc;
        try {
            doc = Documents.parse(format(f, path), (byte[]) o.value());
        } catch (Documents.Malformed e) {
            out.add(new Violation(Code.FILE_MALFORMED, f.name, path + " " + e.getMessage()));
            return null;
        }
        if (doc == null) {
            out.add(new Violation(Code.FILE_MALFORMED, f.name, path + " is empty"));
            return null;
        }
        if (f.schema != null) {
            List<String> problems = JsonSchema.validate(f.schema, doc, f.secret);
            for (String p : problems) {
                out.add(new Violation(Code.SCHEMA_MISMATCH, f.name, p));
            }
            if (!problems.isEmpty()) {
                return null;
            }
        }
        return doc;
    }

    /** A config file's format: as declared, else from the extension. */
    private static String format(FileSpec f, Path path) {
        if (f.format != null) {
            return f.format;
        }
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml") ? "yaml" : name.endsWith(".toml") ? "toml" : "json";
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder();
        Json.quote(b, s);
        return b.toString();
    }
}
