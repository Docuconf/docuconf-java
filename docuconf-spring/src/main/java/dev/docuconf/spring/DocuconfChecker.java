package dev.docuconf.spring;

import dev.docuconf.check.Code;
import dev.docuconf.check.FileChecker;
import dev.docuconf.check.InjectorReference;
import dev.docuconf.check.VarChecker;
import dev.docuconf.check.Violation;
import dev.docuconf.check.WireFormat;
import dev.docuconf.contract.Bindings;
import dev.docuconf.contract.ContractBundle;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.contract.FileSpec;
import dev.docuconf.contract.FileType;
import dev.docuconf.contract.Json;
import dev.docuconf.contract.Names;
import dev.docuconf.contract.VarSpec;
import dev.docuconf.contract.VarType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.math.BigInteger;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.convert.Delimiter;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.util.ClassUtils;

/**
 * Checks the environment and file inputs against the contracts the annotation processor exported
 * ({@code META-INF/docuconf/contract.json}), before any {@code @ConfigurationProperties} bean is bound.
 *
 * <p>Values are read through Spring's own {@link Binder}, so relaxed names, {@code application*.yml}, profiles,
 * placeholders and Spring's conversions apply exactly as they will when the beans are bound. On top of that come
 * the contract's constraints (SPEC §4.3, §5), the file checks (SPEC §11.2 item 7) and any other Bean Validation
 * constraints on the classes. Every problem is reported with its stable code; secret values never appear.
 */
public final class DocuconfChecker {

    private static final Set<VarType> TRIM_SENSITIVE = Set.of(VarType.INT, VarType.FLOAT, VarType.BOOL,
            VarType.DURATION);

    /** The property source holding file input markers. */
    public static final String PROPERTY_SOURCE = "docuconfFiles";

    private final ConfigurableEnvironment environment;
    private final List<ContractBundle> bundles;
    private final ClassLoader classLoader;
    private final Clock clock;
    private final DocuconfFiles files;
    private final Validator validator;
    private final List<ConversionService> conversion;
    private final Binder binder;
    private final List<String> warnings = new ArrayList<>();
    /** Values outside SPEC §5's exact grammar, found by {@link #normalizeEnvironment()}, by variable. */
    private final Map<String, Violation> strictProblems = new LinkedHashMap<>();

    /**
     * Creates a checker.
     *
     * @param environment the application environment
     * @param bundles the contracts to check
     * @param classLoader loads the app's classes
     * @param clock the time, for certificate validity
     */
    public DocuconfChecker(ConfigurableEnvironment environment, List<ContractBundle> bundles, ClassLoader classLoader,
            Clock clock) {
        this.environment = environment;
        this.bundles = bundles;
        this.classLoader = classLoader;
        this.clock = clock;
        this.files = new DocuconfFiles();
        this.validator = validator();
        ApplicationConversionService conversion = new ApplicationConversionService();
        conversion.addConverter(new DocuconfFileConverter(files));
        conversion.addConverter(new DocuconfJsonConverter());
        conversion.addConverter(new DocuconfKeySetConverter());
        this.conversion = List.of(conversion);
        this.binder = binder(environment);
    }

    private Binder binder(ConfigurableEnvironment env) {
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env),
                conversion, null, null, null);
    }

    /** The contracts being checked. */
    List<ContractBundle> bundles() {
        return bundles;
    }

    /** A binder over the live environment, with docuconf's converters. */
    Binder binder() {
        return binder;
    }

    /**
     * Reads every {@code META-INF/docuconf/contract.json} on the class path.
     *
     * @param classLoader the class loader
     * @return the contracts, possibly none
     * @throws IOException if one cannot be read
     */
    public static List<ContractBundle> load(ClassLoader classLoader) throws IOException {
        List<ContractBundle> out = new ArrayList<>();
        Enumeration<URL> urls = classLoader.getResources(ContractJson.LOCATION);
        Set<String> seen = new HashSet<>();
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (!seen.add(url.toString())) {
                continue;
            }
            try (InputStream in = url.openStream()) {
                out.add(ContractJson.read(in));
            }
        }
        return out;
    }

    /**
     * The file registry the checker filled.
     *
     * @return the registry
     */
    public DocuconfFiles files() {
        return files;
    }

    /**
     * Hints found while checking, such as deprecated variables that are set.
     *
     * @return the warnings
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Runs every check. Afterwards the environment holds a property source that hands the loaded file inputs to
     * Spring's binder.
     *
     * @return every violation, empty when the configuration is valid
     */
    public List<Violation> check() {
        typoHints();
        strictProblems.clear();
        normalizeEnvironment();
        List<Violation> out = new ArrayList<>(overlayProblems());
        Set<String> failed = new HashSet<>();
        out.addAll(checkVars(binder, failed));
        Map<String, Object> markers = new LinkedHashMap<>();
        for (ContractBundle bundle : bundles) {
            for (FileSpec f : bundle.contract().files.values()) {
                Bindings.PropertyBinding b = bundle.bindings().files.get(f.name);
                List<Violation> found = new ArrayList<>();
                Path path = FileChecker.resolve(f, this::lookup);
                Object value = loadFile(f, b, path, found);
                files.put(f.name, path, value, f.reload);
                if (f.deprecated != null && (f.type == FileType.TLS ? Files.isDirectory(path) : Files.exists(path))) {
                    // SPEC §11.2: a deprecated input that is set warns, naming it and its message.
                    warnings.add(dev.docuconf.check.ContractFirst.deprecationMessage(f.name, f.deprecated));
                }
                if (!found.isEmpty()) {
                    failed.add(f.name);
                    out.addAll(found);
                } else if (value != null && b != null) {
                    markers.put(b.configKey(), new DocuconfFileConverter.FileInput(f.name));
                }
            }
        }
        environment.getPropertySources().remove(PROPERTY_SOURCE);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, markers));
        if (validator != null) {
            for (ContractBundle bundle : bundles) {
                out.addAll(beanValidation(bundle, failed, binder));
            }
        }
        return out;
    }

    private List<Violation> checkVars(Binder binder, Set<String> failed) {
        List<Violation> out = new ArrayList<>();
        for (ContractBundle bundle : bundles) {
            for (VarSpec v : bundle.contract().vars.values()) {
                Bindings.PropertyBinding b = bundle.bindings().vars.get(v.name);
                List<Violation> found = checkVar(v, b, binder);
                if (!found.isEmpty()) {
                    failed.add(v.name);
                    String setAs = setAs(v, b);
                    for (Violation f : found) {
                        out.add(setAs == null ? f : new Violation(f.code(), f.input(), f.message()
                                + " (set as " + setAs + ")"));
                    }
                }
            }
        }
        return out;
    }

    /**
     * The variable that actually supplied a contract variable's value, when it is another name Spring binds to the
     * same property ({@code ORDERS_WORKER_COUNT} for {@code ORDERS_WORKERCOUNT}); otherwise {@code null}.
     */
    private String setAs(VarSpec v, Bindings.PropertyBinding b) {
        Map<String, Object> env = systemEnvironment();
        String key = b != null ? b.configKey() : v.configKey;
        if (env.containsKey(v.name) || key == null) {
            return null;
        }
        for (String alternative : List.of(Names.envName(key), Names.underscoredEnvName(key))) {
            if (!alternative.equals(v.name) && env.containsKey(alternative)) {
                return alternative;
            }
        }
        return null;
    }

    private Map<String, Object> systemEnvironment() {
        PropertySource<?> env = environment.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return env instanceof SystemEnvironmentPropertySource system ? system.getSource() : Map.of();
    }

    /**
     * Rule: a variable that is set, starts like the service's variables and is within two edits of a declared one
     * is most likely a typo. It is a warning, never a violation, and the value is never printed.
     */
    private void typoHints() {
        Map<String, Object> env = systemEnvironment();
        Set<String> accepted = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        Map<String, String> declared = new LinkedHashMap<>();
        for (ContractBundle bundle : bundles) {
            for (Bindings.ClassBinding cb : bundle.bindings().classes) {
                prefixes.add(Names.envName(cb.prefix()) + "_");
                prefixes.add(Names.underscoredEnvName(cb.prefix()) + "_");
            }
            for (VarSpec v : bundle.contract().vars.values()) {
                declared.put(v.name, v.name);
                accepted.add(v.name);
                if (v.configKey != null) {
                    accepted.add(Names.envName(v.configKey));
                    accepted.add(Names.underscoredEnvName(v.configKey));
                    declared.putIfAbsent(Names.envName(v.configKey), v.name);
                    declared.putIfAbsent(Names.underscoredEnvName(v.configKey), v.name);
                }
            }
            for (FileSpec f : bundle.contract().files.values()) {
                if (f.pathEnv != null) {
                    accepted.add(f.pathEnv);
                    declared.putIfAbsent(f.pathEnv, f.pathEnv);
                }
            }
        }
        for (String name : new java.util.TreeSet<>(env.keySet())) {
            if (accepted.contains(name) || prefixes.stream().noneMatch(name::startsWith)
                    || accepted.stream().anyMatch(a -> name.startsWith(a + "_"))) {
                continue;
            }
            String best = null;
            int bestDistance = 3;
            for (Map.Entry<String, String> d : declared.entrySet()) {
                int distance = Names.editDistance(name, d.getKey(), bestDistance);
                if (distance < bestDistance) {
                    best = d.getValue();
                    bestDistance = distance;
                }
            }
            if (best != null) {
                warnings.add(name + " is set but not declared; did you mean " + best + "?");
            }
        }
    }

    /**
     * Checks the variables again against a candidate environment, such as the live one with a changed overlay,
     * without touching the file inputs (which keep their current values).
     *
     * @param candidate the environment to check
     * @return every violation, empty when the candidate is valid
     */
    List<Violation> recheckVars(ConfigurableEnvironment candidate) {
        Binder b = binder(candidate);
        Set<String> failed = new HashSet<>();
        List<Violation> out = new ArrayList<>(checkVars(b, failed));
        if (validator != null) {
            for (ContractBundle bundle : bundles) {
                out.addAll(beanValidation(bundle, failed, b));
            }
        }
        return out;
    }

    /** Overlays that could not be read, and values an overlay must not carry (SPEC §4.7). */
    private List<Violation> overlayProblems() {
        List<Violation> out = new ArrayList<>();
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (!(ps instanceof DocuconfOverlaySource overlay)) {
                continue;
            }
            if (overlay.error() != null) {
                out.add(new Violation(Code.FILE_MALFORMED, overlay.spec().name,
                        "overlay " + overlay.path() + " " + overlay.error()));
            }
            for (ContractBundle bundle : bundles) {
                for (VarSpec v : bundle.contract().vars.values()) {
                    if (v.secret && v.configKey != null && overlay.containsProperty(v.configKey)) {
                        warnings.add(v.name + " is secret, but overlay " + overlay.spec().name + " sets "
                                + v.configKey + "; overlays are ConfigMaps, so supply it from a Secret");
                    }
                }
            }
            if (overlay.containsProperty("spring.profiles.active")) {
                warnings.add("overlay " + overlay.spec().name + " sets spring.profiles.active, which Spring reads"
                        + " before overlays are loaded; set SPRING_PROFILES_ACTIVE in the environment instead");
            }
        }
        return out;
    }

    /**
     * Prepares the environment variables for Spring's binder, so it binds what SPEC §5 reads:
     *
     * <ul>
     *   <li>an empty value means unset for every type but string. Spring would bind "" as null, which fails for
     *       primitives and skips {@code @DefaultValue}, so empty variables of other types are hidden from it;</li>
     *   <li>a value outside SPEC §5's exact grammar for its type is recorded as {@code invalid_type} (or
     *       {@code out_of_range}), where Spring is more lenient: {@code yes}, {@code on} or {@code 1} for a bool,
     *       {@code 0x10} or {@code #10} for an int, the simple format {@code 30s} for an {@code iso8601} duration,
     *       a list item with spaces around it, which Spring would trim, or an empty one, which it would drop;</li>
     *   <li>a valid value Spring would read differently is replaced by its canonical form: {@code 010} is the int
     *       10, never octal 8, and {@code TRUE} the bool true.</li>
     * </ul>
     */
    private void normalizeEnvironment() {
        PropertySource<?> env = environment.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (!(env instanceof SystemEnvironmentPropertySource system)) {
            return;
        }
        Map<String, Object> source = system.getSource();
        Map<String, Object> changed = new LinkedHashMap<>();
        Set<String> hidden = new HashSet<>();
        for (ContractBundle bundle : bundles) {
            for (VarSpec v : bundle.contract().vars.values()) {
                for (String name : envNames(v, bundle.bindings().vars.get(v.name))) {
                    if (!(source.get(name) instanceof String raw)) {
                        continue;
                    }
                    if (raw.isEmpty()) {
                        if (v.type != VarType.STRING) {
                            hidden.add(name);
                        }
                        continue;
                    }
                    try {
                        String canonical = canonical(v, raw);
                        if (canonical != null && !canonical.equals(raw)) {
                            changed.put(name, canonical);
                        }
                    } catch (WireFormat.WireException e) {
                        strictProblems.putIfAbsent(v.name, new Violation(e.code(), v.name, e.getMessage()
                                + (v.secret ? "" : " (got " + quote(raw) + ")")
                                + (name.equals(v.name) ? "" : " (set as " + name + ")")));
                    }
                }
            }
        }
        if (hidden.isEmpty() && changed.isEmpty()) {
            return;
        }
        Map<String, Object> filtered = new LinkedHashMap<>(source);
        filtered.keySet().removeAll(hidden);
        filtered.putAll(changed);
        environment.getPropertySources().replace(system.getName(),
                new SystemEnvironmentPropertySource(system.getName(), filtered));
    }

    /** The environment variable names Spring binds a contract variable from. */
    private static Set<String> envNames(VarSpec v, Bindings.PropertyBinding b) {
        Set<String> names = new java.util.LinkedHashSet<>();
        names.add(v.name);
        String key = b != null ? b.configKey() : v.configKey;
        if (key != null) {
            names.add(Names.envName(key));
            names.add(Names.underscoredEnvName(key));
        }
        return names;
    }

    /**
     * A raw environment value in the form Spring's binder reads as SPEC §5 does, or {@code null} to leave it.
     *
     * @throws WireFormat.WireException when the value is not in the type's exact grammar
     */
    private static String canonical(VarSpec v, String raw) {
        if (TRIM_SENSITIVE.contains(v.type) && !raw.equals(raw.strip())) {
            // Values are never trimmed. Spring would trim " 8080"; the platform and contract-first mode reject it.
            throw new WireFormat.WireException(Code.INVALID_TYPE, "is not a valid " + describe(v)
                    + ": it has leading or trailing whitespace");
        }
        try {
            return canonicalForm(v, raw);
        } catch (WireFormat.WireException e) {
            throw v.type == VarType.LIST ? e : new WireFormat.WireException(e.code(), "is not a valid "
                    + describe(v));
        }
    }

    private static String canonicalForm(VarSpec v, String raw) {
        switch (v.type) {
            case INT -> {
                if (!WireFormat.isInteger(raw)) {
                    throw new WireFormat.WireException(Code.INVALID_TYPE, "is not an integer");
                }
                // Out of the 64-bit range is left to the binder, which reports out_of_range for the Java type.
                return new java.math.BigInteger(raw.startsWith("+") ? raw.substring(1) : raw).toString();
            }
            case FLOAT -> {
                WireFormat.parseFloat(raw);
                return null;
            }
            case BOOL -> {
                return Boolean.toString(WireFormat.parseBool(raw));
            }
            case DURATION -> {
                return WireFormat.parseDuration(raw, v.encoding).toString();
            }
            case LIST -> {
                List<String> items = WireFormat.splitCsv(raw, v.separator);
                List<String> out = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    String item = items.get(i);
                    if ("int".equals(v.items)) {
                        if (!WireFormat.isInteger(item)) {
                            throw new WireFormat.WireException(Code.INVALID_TYPE, "item " + i + " is not an integer");
                        }
                        out.add(new java.math.BigInteger(item.startsWith("+") ? item.substring(1) : item).toString());
                    } else if (item.isEmpty() || !item.equals(item.strip())) {
                        // Spring trims list items and drops empty ones; SPEC section 5 never trims.
                        throw new WireFormat.WireException(Code.INVALID_TYPE, "item " + i + (item.isEmpty()
                                ? " is empty, and Spring would drop it" : " has leading or trailing whitespace, which"
                                + " Spring would trim") + "; list items are never trimmed (SPEC section 5)");
                    } else {
                        out.add(item);
                    }
                }
                return String.join(v.separator == null ? "," : v.separator, out);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Checks one file input again, for reloading.
     *
     * @param input the input name
     * @param violations receives problems
     * @return the new value, or {@code null}
     */
    Object recheck(String input, List<Violation> violations) {
        for (ContractBundle bundle : bundles) {
            FileSpec f = bundle.contract().files.get(input);
            if (f != null) {
                Path path = FileChecker.resolve(f, this::lookup);
                return loadFile(f, bundle.bindings().files.get(input), path, violations);
            }
        }
        return null;
    }

    /** File inputs declared with {@code reload: watch}. */
    List<FileSpec> watchedFiles() {
        List<FileSpec> out = new ArrayList<>();
        for (ContractBundle bundle : bundles) {
            for (FileSpec f : bundle.contract().files.values()) {
                if ("watch".equals(f.reload)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private Object loadFile(FileSpec f, Bindings.PropertyBinding b, Path path, List<Violation> out) {
        FileChecker.Outcome o = FileChecker.check(f, path, clock.instant(), this::lookup);
        out.addAll(o.violations());
        if (!o.violations().isEmpty() || o.value() == null) {
            return null;
        }
        if (f.type == FileType.CONFIG) {
            Class<?> type = b == null ? Object.class : load(b.javaType());
            return ConfigFileReader.read(f, path, (byte[]) o.value(), type, validator, out);
        }
        return o.value();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Variables

    private List<Violation> checkVar(VarSpec v, Bindings.PropertyBinding b, Binder binder) {
        List<Violation> out = new ArrayList<>();
        String key = b != null ? b.configKey() : v.configKey;
        if (key == null) {
            key = v.name;
        }
        String raw = rawValue(key, binder);
        // SPEC §11.2: a secret still holding vault:..., op://... or ref+... means its injector did not run.
        Violation reference = InjectorReference.check(v, raw);
        if (reference != null) {
            out.add(reference);
            return out;
        }
        Violation gap = v.type == VarType.LIST ? indexedGap(v) : null;
        if (gap != null) {
            out.add(gap);
            return out;
        }
        Violation strict = strictProblems.get(v.name);
        if (strict != null) {
            out.add(strict);
            return out;
        }
        boolean empty = raw != null && raw.isEmpty();
        if (empty && v.type != VarType.STRING) {
            raw = null; // SPEC §5: empty means unset for every type but string.
        }
        // Enum values bind as Spring binds them, in any case ("warn" for WARN), wherever they come from: the
        // platform checks the contract's spelling (@Docuconf(enumCase)), the app stays as lenient as Spring.
        if (raw != null && !raw.isEmpty() && !raw.equals(raw.strip()) && TRIM_SENSITIVE.contains(v.type)) {
            // SPEC section 5: values are never trimmed. Spring would trim " 8080"; the platform and contract-first
            // mode reject it, so one contract gives one verdict.
            out.add(new Violation(Code.INVALID_TYPE, v.name, "is not a valid " + describe(v)
                    + ": it has leading or trailing whitespace" + (v.secret ? "" : " (got " + quote(raw) + ")")));
            return out;
        }
        Object typed;
        if (empty && v.type != VarType.STRING) {
            typed = null;
        } else if (v.type == VarType.JSON) {
            typed = raw == null ? nestedJson(v, b, key, binder, out) : json(v, b, raw, out);
            if (!out.isEmpty()) {
                return out;
            }
        } else {
            Capture capture = new Capture();
            BindResult<?> result = null;
            try {
                result = binder.bind(key, bindable(v, b), capture);
            } catch (RuntimeException e) {
                capture.error = e;
            }
            if (capture.error != null) {
                if (capture.error instanceof IllegalStateException internal && internal.getMessage() != null
                        && internal.getMessage().startsWith("docuconf:")) {
                    throw internal;
                }
                for (Throwable t = capture.error; t != null; t = t.getCause()) {
                    if (t instanceof DocuconfSetupException setup) {
                        throw setup;
                    }
                }
                String got = v.secret || raw == null ? "" : " (got " + quote(raw) + ")";
                if (outOfRange(v, raw)) {
                    // SPEC §5: an integer the type cannot hold is out_of_range, not invalid_type.
                    out.add(new Violation(Code.OUT_OF_RANGE, v.name, "is outside the range of "
                            + javaType(b) + got));
                } else {
                    out.add(new Violation(v.type == VarType.ENUM ? Code.NOT_IN_ENUM : Code.INVALID_TYPE, v.name,
                            "is not a valid " + describe(v) + got));
                }
                return out;
            }
            typed = result != null && result.isBound() ? result.get() : null;
            if (typed instanceof Collection<?> c && c.isEmpty() && "".equals(raw)) {
                typed = null;
            }
        }
        if (typed == null) {
            if (v.required) {
                out.add(new Violation(Code.MISSING_REQUIRED, v.name, "is required (" + key + ")"));
            }
            return out;
        }
        if (v.secret && raw != null && raw.endsWith("\n")) {
            // EDGE_CASES.md: a Secret created with --from-file usually carries the file's trailing newline.
            warnings.add(v.name + " ends in a newline, which is part of the value (a Secret created with"
                    + " kubectl --from-file?)");
        }
        if (v.deprecated != null) {
            warnings.add(dev.docuconf.check.ContractFirst.deprecationMessage(v.name, v.deprecated));
        }
        Object value;
        try {
            value = checkForm(v, typed);
        } catch (IllegalArgumentException e) {
            Code code = e instanceof WireFormat.WireException w ? w.code() : Code.INVALID_TYPE;
            out.add(new Violation(code, v.name, e.getMessage()
                    + (v.secret || raw == null ? "" : " (got " + quote(raw) + ")")));
            return out;
        }
        if (v.type != VarType.JSON) {
            out.addAll(VarChecker.check(v, value));
        }
        return out;
    }

    private Object json(VarSpec v, Bindings.PropertyBinding b, String raw, List<Violation> out) {
        Class<?> type = b == null ? Object.class : load(b.javaType());
        try {
            Object value = JsonMapper.forFormat("json").read(raw, type);
            // maxLength bounds the value as received, whitespace included (SPEC §4.3).
            Violation tooLong = VarChecker.jsonMaxLength(v, raw);
            if (tooLong != null) {
                out.add(tooLong);
                return null;
            }
            if (validator != null) {
                for (GraphValidator.Problem p : GraphValidator.validate(validator, value)) {
                    out.add(new Violation(Code.SCHEMA_MISMATCH, v.name, p.path() + ": "
                            + (v.secret ? "fails @" + p.constraint() : p.message())));
                }
            }
            return value;
        } catch (JsonMapper.Malformed | JsonMapper.Mismatch e) {
            out.add(new Violation(Code.INVALID_TYPE, v.name, "is not JSON that fits " + type.getSimpleName()));
            return null;
        }
    }

    /** A json variable given as nested keys, as an overlay or application.yml writes it, rather than one string. */
    private Object nestedJson(VarSpec v, Bindings.PropertyBinding b, String key, Binder binder, List<Violation> out) {
        if (b == null) {
            return null;
        }
        Class<?> type = load(b.javaType());
        Capture capture = new Capture();
        Object value;
        try {
            BindResult<?> r = binder.bind(key, Bindable.of(type), capture);
            value = r.isBound() ? r.get() : null;
        } catch (RuntimeException e) {
            capture.error = e;
            value = null;
        }
        if (capture.error != null) {
            out.add(new Violation(Code.INVALID_TYPE, v.name, "is not an object that fits " + type.getSimpleName()));
            return null;
        }
        if (value != null && v.maxLength != null) {
            // Nested keys have no wire string: measure the compact JSON of the bound value (SPEC §4.3).
            String compact = JsonMapper.forFormat("json").writeCompact(value);
            Violation tooLong = compact == null ? null : VarChecker.jsonMaxLength(v, compact);
            if (tooLong != null) {
                out.add(tooLong);
                return null;
            }
        }
        if (value != null && validator != null) {
            for (GraphValidator.Problem p : GraphValidator.validate(validator, value)) {
                out.add(new Violation(Code.SCHEMA_MISMATCH, v.name, p.path() + ": "
                        + (v.secret ? "fails @" + p.constraint() : p.message())));
            }
        }
        return value;
    }

    private String rawValue(String key, Binder binder) {
        try {
            BindResult<String> r = binder.bind(key, Bindable.of(String.class), new Capture());
            return r.isBound() ? r.get() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Bindable<?> bindable(VarSpec v, Bindings.PropertyBinding b) {
        List<Annotation> annotations = new ArrayList<>();
        ResolvableType type;
        if (b == null) {
            type = ResolvableType.forClass(String.class);
        } else {
            Class<?> raw = ClassUtils.resolvePrimitiveIfNecessary(load(b.javaType()));
            if (b.elementType() != null && Collection.class.isAssignableFrom(raw)) {
                Class<?> concrete = Set.class.isAssignableFrom(raw) ? Set.class : List.class;
                type = ResolvableType.forClassWithGenerics(concrete,
                        ClassUtils.resolvePrimitiveIfNecessary(load(b.elementType())));
            } else {
                type = ResolvableType.forClass(raw);
            }
            if (b.durationUnit() != null) {
                annotations.add(AnnotationUtils.synthesizeAnnotation(
                        Map.of("value", ChronoUnit.valueOf(b.durationUnit())), DurationUnit.class, null));
            }
        }
        if ((v.type == VarType.LIST || v.type == VarType.KEY_SET) && v.separator != null
                && !v.separator.equals(",")) {
            annotations.add(AnnotationUtils.synthesizeAnnotation(Map.of("value", v.separator), Delimiter.class, null));
        }
        return Bindable.of(type).withAnnotations(annotations.toArray(new Annotation[0]));
    }

    /** Converts a bound Java value to the form {@link VarChecker} takes. */
    private static Object checkForm(VarSpec v, Object typed) {
        switch (v.type) {
            case INT:
                return integer(typed);
            case FLOAT:
                return typed;
            case BOOL:
                return typed;
            case DURATION:
                return (Duration) typed;
            case ENUM:
                return contractSpelling(v, typed instanceof Enum<?> e ? e.name() : typed.toString());
            case LIST: {
                List<Object> items = new ArrayList<>();
                Iterable<?> it = typed instanceof Object[] arr ? List.of(arr) : (Iterable<?>) typed;
                for (Object o : it) {
                    items.add("int".equals(v.items) ? integer(o) : o instanceof Enum<?> e ? e.name() : String.valueOf(o));
                }
                return items;
            }
            case JSON:
                return typed;
            case KEY_SET:
                return typed; // VarChecker takes the KeySet itself; its toString() never prints the keys
            default:
                return typed.toString();
        }
    }

    /** The contract's spelling of an enum constant ({@code warn} for {@code WARN} under EnumCase.LOWER). */
    private static String contractSpelling(VarSpec v, String constant) {
        if (v.values == null || v.values.contains(constant)) {
            return constant;
        }
        String wanted = dev.docuconf.contract.Names.canonical(constant);
        for (String value : v.values) {
            if (dev.docuconf.contract.Names.canonical(value).equals(wanted)) {
                return value;
            }
        }
        return constant;
    }

    /**
     * Whether a value that did not bind is an integer (or a list of integers) too large for the Java type, rather
     * than text that is not an integer at all.
     */
    private static boolean outOfRange(VarSpec v, String raw) {
        if (raw == null) {
            return false;
        }
        if (v.type == VarType.INT) {
            return WireFormat.isInteger(raw);
        }
        if (v.type != VarType.LIST || !"int".equals(v.items)) {
            return false;
        }
        boolean any = false;
        for (String item : WireFormat.splitCsv(raw, v.separator)) {
            String t = item.trim(); // Spring trims csv items.
            if (!t.isEmpty() && !WireFormat.isInteger(t)) {
                return false;
            }
            any |= !t.isEmpty();
        }
        return any;
    }

    /**
     * SPEC §5: list items given one per variable must be numbered from 0 with no gap. Spring's relaxed binding reads
     * {@code NAME_0}, {@code NAME__0} and {@code NAME_0_} as items of the list; when the plain {@code NAME} is not
     * set it binds them, and on a gap or a leading zero it fails with a message that does not say why. This names
     * the problem first.
     */
    private Violation indexedGap(VarSpec v) {
        PropertySource<?> env = environment.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (!(env instanceof EnumerablePropertySource<?> system)) {
            return null;
        }
        Object plain = system.getProperty(v.name);
        if (plain != null && !plain.toString().isEmpty()) {
            return null; // Spring reads the comma-separated form and ignores the items.
        }
        Pattern item = Pattern.compile(Pattern.quote(v.name) + "(_{1,2})([0-9]+)_?", Pattern.CASE_INSENSITIVE);
        String prefix = null;
        Set<String> indices = new HashSet<>();
        for (String name : system.getPropertyNames()) {
            Matcher m = item.matcher(name);
            if (!m.matches()) {
                continue;
            }
            String index = m.group(2);
            if (!WireFormat.isIndex(index)) {
                return new Violation(Code.INVALID_TYPE, v.name, "has an item " + name
                        + " whose index has a leading zero; number the items 0, 1, 2, ...");
            }
            prefix = prefix == null ? v.name + m.group(1) : prefix;
            indices.add(index);
        }
        for (int i = 0; i < indices.size(); i++) {
            if (!indices.contains(Integer.toString(i))) {
                return new Violation(Code.INVALID_TYPE, v.name, WireFormat.gapMessage(prefix, indices));
            }
        }
        return null;
    }

    private static String javaType(Bindings.PropertyBinding b) {
        if (b == null) {
            return "a 64-bit integer";
        }
        String t = b.elementType() != null ? b.elementType() : b.javaType();
        return t.substring(t.lastIndexOf('.') + 1);
    }

    private static Long integer(Object o) {
        if (o instanceof BigInteger b) {
            if (b.bitLength() >= 64) {
                throw new WireFormat.WireException(Code.OUT_OF_RANGE, "is outside the 64-bit integer range");
            }
            return b.longValue();
        }
        return ((Number) o).longValue();
    }

    private static String describe(VarSpec v) {
        return switch (v.type) {
            case INT -> "integer";
            case FLOAT -> "number";
            case BOOL -> "boolean (true or false)";
            case DURATION -> "duration; expected an ISO 8601 duration like PT30S (Spring also reads one unit, as in"
                    + " 30s)";
            case LIST -> "list of " + v.items + "s";
            case KEY_SET -> "key set";
            case ENUM -> "value; use one of " + String.join(", ", v.values);
            default -> v.type.id();
        };
    }

    // -------------------------------------------------------------------------------------------------------------
    // Bean Validation of the bound classes

    private List<Violation> beanValidation(ContractBundle bundle, Set<String> failed, Binder binder) {
        List<Violation> out = new ArrayList<>();
        for (Bindings.ClassBinding cb : bundle.bindings().classes) {
            Map<String, String> inputsByPath = new LinkedHashMap<>();
            Map<String, Boolean> secretByInput = new LinkedHashMap<>();
            Set<String> fileInputs = new HashSet<>();
            bundle.bindings().vars.forEach((name, b) -> {
                if (b.className().equals(cb.className())) {
                    inputsByPath.put(b.javaPath(), name);
                    secretByInput.put(name, bundle.contract().vars.get(name).secret);
                }
            });
            bundle.bindings().files.forEach((name, b) -> {
                if (b.className().equals(cb.className())) {
                    inputsByPath.put(b.javaPath(), name);
                    fileInputs.add(name);
                }
            });
            Object instance;
            try {
                Class<?> type = load(cb.className());
                instance = binder.bindOrCreate(cb.prefix(), Bindable.of(type), new Capture());
            } catch (RuntimeException | LinkageError e) {
                // Conversion problems were reported per variable; anything else surfaces when Spring binds.
                continue;
            }
            List<ConstraintViolation<Object>> found = new ArrayList<>(validator.validate(instance));
            found.sort(Comparator.comparing(cv -> cv.getPropertyPath().toString()));
            for (ConstraintViolation<Object> cv : found) {
                String path = cv.getPropertyPath().toString();
                String input = inputFor(path, inputsByPath);
                // Unmapped properties are not in the contract; Spring validates them when it binds.
                if (input == null || failed.contains(input) || fileInputs.contains(input)) {
                    continue;
                }
                out.add(fromConstraint(input, cv, Boolean.TRUE.equals(secretByInput.get(input))));
                failed.add(input);
            }
        }
        return out;
    }

    private static String inputFor(String path, Map<String, String> inputsByPath) {
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, String> e : inputsByPath.entrySet()) {
            String p = e.getKey();
            boolean match = path.equals(p) || path.startsWith(p + ".") || path.startsWith(p + "[");
            if (match && p.length() > bestLength) {
                best = e.getValue();
                bestLength = p.length();
            }
        }
        return best;
    }

    private static Violation fromConstraint(String input, ConstraintViolation<Object> cv, boolean secret) {
        String annotation = cv.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        Object value = cv.getInvalidValue();
        Code code = switch (annotation) {
            case "NotNull", "NotBlank", "NotEmpty" -> value == null ? Code.MISSING_REQUIRED
                    : value instanceof Collection ? Code.TOO_FEW_ITEMS : Code.OUT_OF_RANGE;
            case "Size", "Length" -> value instanceof Collection<?> c
                    ? (sizeMin(cv) > c.size() ? Code.TOO_FEW_ITEMS : Code.TOO_MANY_ITEMS) : Code.OUT_OF_RANGE;
            case "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
                    "Range", "DurationMin", "DurationMax" -> Code.OUT_OF_RANGE;
            case "Pattern" -> Code.PATTERN_MISMATCH;
            default -> Code.INVALID_TYPE;
        };
        String message = secret ? SecretMessages.redacted(cv) : cv.getMessage();
        return new Violation(code, input, message + " (@" + annotation + ")");
    }

    private static long sizeMin(ConstraintViolation<Object> cv) {
        Object min = cv.getConstraintDescriptor().getAttributes().get("min");
        return min instanceof Number n ? n.longValue() : 0;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Looks a variable up: through its configuration key when it is a contract variable, else as is. */
    private String lookup(String name) {
        for (ContractBundle bundle : bundles) {
            Bindings.PropertyBinding b = bundle.bindings().vars.get(name);
            if (b != null) {
                String raw = rawValue(b.configKey(), binder);
                if (raw != null) {
                    return raw;
                }
            }
        }
        return environment.getProperty(name);
    }

    private Class<?> load(String name) {
        try {
            return ClassUtils.forName(name, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("docuconf: cannot load " + name + " named in the contract", e);
        }
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder();
        Json.quote(b, s);
        return b.toString();
    }

    private static Validator validator() {
        try {
            return Validation.buildDefaultValidatorFactory().getValidator();
        } catch (RuntimeException | LinkageError e) {
            return null; // No Bean Validation provider: the contract checks still run.
        }
    }

    /** Records the first binding failure instead of throwing, so every input gets checked. */
    private static final class Capture implements BindHandler {
        Exception error;

        @Override
        public Object onFailure(ConfigurationPropertyName name, Bindable<?> target, BindContext context,
                Exception error) {
            if (this.error == null) {
                this.error = error;
            }
            return null;
        }
    }
}
