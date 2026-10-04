package dev.docuconf.spring;

import dev.docuconf.check.Code;
import dev.docuconf.check.FileChecker;
import dev.docuconf.check.VarChecker;
import dev.docuconf.check.Violation;
import dev.docuconf.contract.Bindings;
import dev.docuconf.contract.ContractBundle;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.contract.FileSpec;
import dev.docuconf.contract.FileType;
import dev.docuconf.contract.Json;
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

    /** The property source holding file input markers. */
    public static final String PROPERTY_SOURCE = "docuconfFiles";

    private final ConfigurableEnvironment environment;
    private final List<ContractBundle> bundles;
    private final ClassLoader classLoader;
    private final Clock clock;
    private final DocuconfFiles files;
    private final Validator validator;
    private final Binder binder;
    private final List<String> warnings = new ArrayList<>();

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
        List<ConversionService> services = List.of(conversion);
        this.binder = new Binder(ConfigurationPropertySources.get(environment),
                new PropertySourcesPlaceholdersResolver(environment), services, null, null, null);
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
        hideEmptyValues();
        List<Violation> out = new ArrayList<>();
        Set<String> failed = new HashSet<>();
        for (ContractBundle bundle : bundles) {
            for (VarSpec v : bundle.contract().vars.values()) {
                Bindings.PropertyBinding b = bundle.bindings().vars.get(v.name);
                List<Violation> found = checkVar(v, b);
                if (!found.isEmpty()) {
                    failed.add(v.name);
                    out.addAll(found);
                }
            }
        }
        Map<String, Object> markers = new LinkedHashMap<>();
        for (ContractBundle bundle : bundles) {
            for (FileSpec f : bundle.contract().files.values()) {
                Bindings.PropertyBinding b = bundle.bindings().files.get(f.name);
                List<Violation> found = new ArrayList<>();
                Path path = FileChecker.resolve(f, this::lookup);
                Object value = loadFile(f, b, path, found);
                files.put(f.name, path, value);
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
                out.addAll(beanValidation(bundle, failed));
            }
        }
        return out;
    }

    /**
     * SPEC §5: an empty value means unset for every type but string. Spring would bind "" as null, which fails
     * for primitives and skips {@code @DefaultValue}, so empty variables of other types are hidden from it.
     */
    private void hideEmptyValues() {
        PropertySource<?> env = environment.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (!(env instanceof SystemEnvironmentPropertySource system)) {
            return;
        }
        Set<String> hidden = new HashSet<>();
        for (ContractBundle bundle : bundles) {
            for (VarSpec v : bundle.contract().vars.values()) {
                if (v.type != VarType.STRING && "".equals(system.getSource().get(v.name))) {
                    hidden.add(v.name);
                }
            }
        }
        if (hidden.isEmpty()) {
            return;
        }
        Map<String, Object> filtered = new LinkedHashMap<>(system.getSource());
        filtered.keySet().removeAll(hidden);
        environment.getPropertySources().replace(system.getName(),
                new SystemEnvironmentPropertySource(system.getName(), filtered));
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

    private List<Violation> checkVar(VarSpec v, Bindings.PropertyBinding b) {
        List<Violation> out = new ArrayList<>();
        String key = b != null ? b.configKey() : v.configKey;
        if (key == null) {
            key = v.name;
        }
        String raw = rawValue(key);
        boolean empty = raw != null && raw.isEmpty();
        if (empty && v.type != VarType.STRING) {
            raw = null; // SPEC §5: empty means unset for every type but string.
        }
        Object typed;
        if (empty && v.type != VarType.STRING) {
            typed = null;
        } else if (v.type == VarType.JSON) {
            typed = raw == null ? null : json(v, b, raw, out);
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
                out.add(new Violation(v.type == VarType.ENUM ? Code.NOT_IN_ENUM : Code.INVALID_TYPE, v.name,
                        "is not a valid " + describe(v)
                        + (v.secret || raw == null ? "" : " (got " + quote(raw) + ")")));
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
        if (v.deprecated != null) {
            warnings.add(v.name + " is deprecated: " + v.deprecated.message()
                    + (v.deprecated.replacedBy() == null ? "" : "; use " + v.deprecated.replacedBy()));
        }
        Object value;
        try {
            value = checkForm(v, typed);
        } catch (IllegalArgumentException e) {
            out.add(new Violation(Code.INVALID_TYPE, v.name, e.getMessage()
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
            Object value = ConfigFileReader.mapper("json").readValue(raw, type);
            if (validator != null) {
                for (GraphValidator.Problem p : GraphValidator.validate(validator, value)) {
                    out.add(new Violation(Code.SCHEMA_MISMATCH, v.name, p.path() + ": "
                            + (v.secret ? "fails @" + p.constraint() : p.message())));
                }
            }
            return value;
        } catch (IOException e) {
            out.add(new Violation(Code.INVALID_TYPE, v.name, "is not JSON that fits " + type.getSimpleName()));
            return null;
        }
    }

    private String rawValue(String key) {
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
        if (v.type == VarType.LIST && v.separator != null && !v.separator.equals(",")) {
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
                return typed instanceof Enum<?> e ? e.name() : typed.toString();
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
            default:
                return typed.toString();
        }
    }

    private static Long integer(Object o) {
        if (o instanceof BigInteger b) {
            if (b.bitLength() >= 64) {
                throw new IllegalArgumentException("is outside the 64-bit integer range");
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
            case DURATION -> "duration (ISO-8601 such as PT1M30S, or 90s)";
            case LIST -> "list of " + v.items + "s";
            case ENUM -> "value; use one of " + String.join(", ", v.values);
            default -> v.type.id();
        };
    }

    // -------------------------------------------------------------------------------------------------------------
    // Bean Validation of the bound classes

    private List<Violation> beanValidation(ContractBundle bundle, Set<String> failed) {
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
        String message = secret ? "fails @" + annotation : cv.getMessage();
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
                String raw = rawValue(b.configKey());
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
