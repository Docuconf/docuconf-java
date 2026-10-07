package dev.docuconf.processor;

import dev.docuconf.EnumCase;
import dev.docuconf.contract.Bindings;
import dev.docuconf.contract.Contract;
import dev.docuconf.contract.ContractBundle;
import dev.docuconf.contract.ContractJson;
import dev.docuconf.contract.CueWriter;
import dev.docuconf.contract.DeclarationValidator;
import dev.docuconf.contract.Deprecation;
import dev.docuconf.contract.FileSpec;
import dev.docuconf.contract.FileType;
import dev.docuconf.contract.GoDuration;
import dev.docuconf.contract.Names;
import dev.docuconf.contract.OverlaySpec;
import dev.docuconf.contract.Profiles;
import dev.docuconf.contract.VarSpec;
import dev.docuconf.contract.VarType;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Exports the docuconf contract at compile time, like {@code spring-boot-configuration-processor} exports
 * configuration metadata.
 *
 * <p>Every {@code @Docuconf @ConfigurationProperties} class in the compilation becomes part of one contract,
 * written to {@code META-INF/docuconf/contract.cue} (and {@code contract.json}, which the Spring Boot integration
 * reads at startup). Declaration errors (a missing description, a default that violates its own constraint, a
 * non-RE2 pattern, a secret with a default) fail the build.
 *
 * <p>Options: {@code -Adocuconf.name=} (service name), {@code -Adocuconf.appVersion=}, and
 * {@code -Adocuconf.resources=} (the directory holding {@code application*.yml}, when it is not the class output
 * directory, as with Gradle).
 */
public final class DocuconfProcessor extends AbstractProcessor {

    static final String DOCUCONF = "dev.docuconf.Docuconf";
    static final String CONFIGURATION_PROPERTIES = "org.springframework.boot.context.properties.ConfigurationProperties";
    private static final String D = "dev.docuconf.";
    private static final String SPRING_BIND = "org.springframework.boot.context.properties.bind.";
    private static final List<String> FILE_ANNOTATIONS = List.of(D + "ConfigFile", D + "TlsFile", D + "CaBundleFile",
            D + "KeystoreFile", D + "TextFile", D + "BinaryFile");
    private static final Set<String> STRING_LIKE = Set.of("java.lang.String", "java.lang.CharSequence",
            "java.nio.file.Path", "java.io.File", "java.util.Locale", "java.nio.charset.Charset", "java.time.ZoneId",
            "java.util.TimeZone", "java.net.InetAddress", "java.util.UUID", "java.lang.Character", "java.lang.Class",
            "org.springframework.core.io.Resource", "org.springframework.util.MimeType",
            "org.springframework.http.MediaType", "org.springframework.util.unit.DataSize");

    private Elements elements;
    private Types types;
    private Initializers initializers;
    private boolean warnedNoTrees;

    private final List<String> classNames = new ArrayList<>();
    private final Map<String, Element> elementsByInput = new LinkedHashMap<>();
    private final Map<String, String> services = new LinkedHashMap<>();
    private final Contract contract = new Contract();
    private final Bindings bindings = new Bindings();
    /** Variables waiting for application*.yml values, which are read once at the end. */
    private final List<PendingVar> pending = new ArrayList<>();
    private final List<PendingPassword> passwords = new ArrayList<>();
    /** Root classes bound through a constructor (records, @ConstructorBinding), which cannot be rebound in place. */
    private final Map<String, TypeElement> constructorBound = new LinkedHashMap<>();
    private final Map<String, Element> overlayElements = new LinkedHashMap<>();
    /** {@code @Docuconf(enumCase, envNames)} of each root class, by binary name. */
    private final Map<String, String[]> settings = new LinkedHashMap<>();
    private boolean failed;
    /** Diagnostics already printed, so that analysing again in a later round does not repeat them. */
    private final Set<String> reported = new java.util.HashSet<>();
    /** Whether classes were collected since the contract was last analysed. */
    private boolean dirty;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        elements = env.getElementUtils();
        types = env.getTypeUtils();
        initializers = Initializers.create(env);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(DOCUCONF);
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of("docuconf.name", "docuconf.appVersion", "docuconf.resources");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        TypeElement marker = elements.getTypeElement(DOCUCONF);
        if (marker != null) {
            for (Element e : round.getElementsAnnotatedWith(marker)) {
                if (e instanceof TypeElement te) {
                    collect(te);
                    dirty = true;
                }
            }
        }
        // Analyse in the round the classes were found in: javac only attaches a source position (file and line)
        // to a diagnostic while the element's tree is live, which it no longer is in the final round.
        if (dirty && !classNames.isEmpty()) {
            dirty = false;
            analyse();
        }
        if (round.processingOver() && !classNames.isEmpty() && !failed) {
            try {
                write(ContractJson.CUE_LOCATION, CueWriter.write(contract));
                write(ContractJson.LOCATION, ContractJson.write(new ContractBundle(contract, bindings)));
            } catch (IOException e) {
                error(null, "docuconf: could not write the contract: " + e.getMessage());
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Collecting declarations

    private void collect(TypeElement te) {
        AnnotationMirror cp = annotation(te, CONFIGURATION_PROPERTIES);
        if (cp == null) {
            error(te, "@Docuconf classes must also be @ConfigurationProperties");
            return;
        }
        String prefix = Mirrors.string(elements, cp, "prefix");
        if (prefix == null || prefix.isEmpty()) {
            prefix = Mirrors.string(elements, cp, "value");
        }
        if (prefix == null || prefix.isEmpty()) {
            error(te, "docuconf needs a @ConfigurationProperties prefix, such as \"billing\"");
            return;
        }
        if (!prefix.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")) {
            error(te, "@ConfigurationProperties prefix \"" + prefix + "\" must be lower-case kebab-case");
            return;
        }
        String service = Mirrors.string(elements, annotation(te, DOCUCONF), "service");
        if (service != null && !service.isEmpty()) {
            services.put(te.getQualifiedName().toString(), service);
        }
        String className = elements.getBinaryName(te).toString();
        classNames.add(className);
        AnnotationMirror docuconf = annotation(te, DOCUCONF);
        settings.put(className, new String[] {Mirrors.string(elements, docuconf, "enumCase"),
                Mirrors.string(elements, docuconf, "envNames")});
        bindings.classes.add(new Bindings.ClassBinding(className, prefix));
        if (te.getKind() == ElementKind.RECORD || bindConstructor(te) != null) {
            constructorBound.put(className, te);
        }
        overlays(te);
        walk(className, te, prefix, "", groupOf(te, null), 0);
    }

    /** Reads {@code @ConfigOverlay}, single or repeated (SPEC §4.7). */
    private void overlays(TypeElement te) {
        List<AnnotationMirror> found = new ArrayList<>();
        AnnotationMirror single = annotation(te, D + "ConfigOverlay");
        if (single != null) {
            found.add(single);
        }
        AnnotationMirror container = annotation(te, D + "ConfigOverlays");
        if (container != null && Mirrors.rawValue(container, "value") instanceof List<?> l) {
            for (Object o : l) {
                found.add((AnnotationMirror) ((javax.lang.model.element.AnnotationValue) o).getValue());
            }
        }
        for (AnnotationMirror m : found) {
            String name = Mirrors.string(elements, m, "name");
            String path = Mirrors.string(elements, m, "value");
            OverlaySpec o = new OverlaySpec(name, path);
            String description = Mirrors.string(elements, m, "description");
            o.description = description == null || description.isEmpty() ? null : description;
            o.reload = Mirrors.string(elements, m, "reload").toLowerCase(Locale.ROOT);
            // Spring reads application.yml-style files with its own YAML loader (YamlPropertySourceLoader), and
            // binds keys separated by dots in relaxed form: orders.checkout-timeout.
            o.format = "yaml";
            o.keySeparator = ".";
            String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".yml") && !lower.endsWith(".yaml")) {
                error(te, "@ConfigOverlay(name = \"" + name + "\"): path " + path + " must end in .yml or .yaml;"
                        + " Spring overlays are read with its YAML loader");
            }
            OverlaySpec other = contract.overlays.get(name);
            if (other != null) {
                if (!other.path.equals(o.path) || !other.reload.equals(o.reload)
                        || !java.util.Objects.equals(other.description, o.description)) {
                    error(te, "@ConfigOverlay(name = \"" + name + "\") is declared twice, differently");
                }
                continue;
            }
            contract.overlays.put(name, o);
            overlayElements.put(name, te);
        }
    }

    /** A property as Spring's binder sees it. */
    private record Prop(String javaName, String boundName, TypeMirror type, Element element,
            Map<String, AnnotationMirror> annotations, String doc, Object initializer, List<String> defaultValue,
            Map<String, AnnotationMirror> itemAnnotations) {
    }

    /**
     * Container-element constraints on a list's items ({@code List<@Min(0) Integer>}), from every place the
     * property's type is written: field, getter, setter, record component or constructor parameter.
     */
    private Map<String, AnnotationMirror> itemAnnotations(TypeMirror... types) {
        List<Object> sources = new ArrayList<>();
        for (TypeMirror t : types) {
            TypeMirror item = itemType(t);
            if (item != null) {
                sources.add(item);
            }
        }
        return Mirrors.collect(sources);
    }

    /** The item type of an array or a one-argument collection, or {@code null}. */
    private static TypeMirror itemType(TypeMirror t) {
        if (t == null) {
            return null;
        }
        if (t.getKind() == TypeKind.ARRAY) {
            return ((ArrayType) t).getComponentType();
        }
        if (t.getKind() == TypeKind.DECLARED && ((DeclaredType) t).getTypeArguments().size() == 1) {
            return ((DeclaredType) t).getTypeArguments().get(0);
        }
        return null;
    }

    private void walk(String root, TypeElement te, String keyPrefix, String javaPrefix, String group, int depth) {
        if (depth > 8) {
            error(te, "configuration classes nest more than 8 levels deep (a cycle?)");
            return;
        }
        for (Prop p : springProperties(te)) {
            Map<String, AnnotationMirror> a = p.annotations();
            if (a.containsKey(D + "External")) {
                continue;
            }
            String configKey = keyPrefix + "." + Names.dashed(p.boundName());
            String javaPath = javaPrefix.isEmpty() ? p.javaName() : javaPrefix + "." + p.javaName();
            String propGroup = groupOf(null, a);
            if (propGroup == null) {
                propGroup = group;
            }
            String fileAnnotation = FILE_ANNOTATIONS.stream().filter(a::containsKey).findFirst().orElse(null);
            if (fileAnnotation != null) {
                file(root, p, fileAnnotation, configKey, javaPath, keyPrefix, propGroup);
                continue;
            }
            Kind kind = classify(p.type(), a);
            if (!placement(p, kind, envName(root, configKey))) {
                continue;
            }
            switch (kind.what()) {
                case VAR -> var(root, p, kind, configKey, javaPath, propGroup);
                case NESTED -> {
                    TypeElement nested = (TypeElement) types.asElement(p.type());
                    walk(root, nested, configKey, javaPath, groupOf(nested, null) != null
                            ? groupOf(nested, null) : propGroup, depth + 1);
                }
                case SKIP -> warn(p.element(), kind.reason() + "; " + configKey
                        + " is left out of the contract and stays file-only (the platform cannot set it)");
                default -> {
                }
            }
        }
    }

    private List<Prop> springProperties(TypeElement te) {
        List<Prop> out = new ArrayList<>();
        if (te.getKind() == ElementKind.RECORD) {
            ExecutableElement canonical = canonicalConstructor(te);
            int i = 0;
            for (RecordComponentElement rc : te.getRecordComponents()) {
                String name = rc.getSimpleName().toString();
                VariableElement param = canonical == null ? null : canonical.getParameters().get(i);
                i++;
                List<Object> sources = new ArrayList<>(List.of(rc, rc.asType()));
                if (rc.getAccessor() != null) {
                    sources.add(rc.getAccessor());
                }
                VariableElement field = SchemaGenerator.field(te, name);
                if (field != null) {
                    sources.add(field);
                }
                if (param != null) {
                    sources.add(param);
                }
                Map<String, AnnotationMirror> a = Mirrors.collect(sources);
                out.add(new Prop(name, boundName(a, name), rc.asType(), rc, a, Docs.param(elements, te, name),
                        Initializers.ABSENT, defaultValue(a), itemAnnotations(rc.asType(),
                                rc.getAccessor() == null ? null : rc.getAccessor().getReturnType(),
                                field == null ? null : field.asType(), param == null ? null : param.asType())));
            }
            return out;
        }
        ExecutableElement ctor = bindConstructor(te);
        if (ctor != null) {
            for (VariableElement param : ctor.getParameters()) {
                String name = param.getSimpleName().toString();
                VariableElement field = SchemaGenerator.field(te, name);
                ExecutableElement getter = SchemaGenerator.method(te, name, true);
                Map<String, AnnotationMirror> a = Mirrors.collect(listOf(param, param.asType(), field, getter));
                String doc = Docs.of(elements, field);
                if (doc == null) {
                    doc = Docs.param(elements, ctor, name);
                }
                out.add(new Prop(name, boundName(a, name), param.asType(), param, a, doc, Initializers.ABSENT,
                        defaultValue(a), itemAnnotations(param.asType(), field == null ? null : field.asType(),
                                getter == null ? null : getter.getReturnType())));
            }
            return out;
        }
        List<TypeElement> chain = new ArrayList<>();
        for (TypeElement t = te; t != null && !t.getQualifiedName().contentEquals("java.lang.Object");) {
            chain.add(0, t);
            TypeMirror sup = t.getSuperclass();
            t = sup.getKind() == TypeKind.DECLARED ? (TypeElement) ((DeclaredType) sup).asElement() : null;
        }
        for (TypeElement t : chain) {
            for (VariableElement f : ElementFilter.fieldsIn(t.getEnclosedElements())) {
                if (f.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                String name = f.getSimpleName().toString();
                ExecutableElement getter = SchemaGenerator.method(t, name, true);
                ExecutableElement setter = SchemaGenerator.method(t, name, false);
                boolean settable = setter != null || SchemaGenerator.hasLombokSetter(t, f);
                boolean mutableViaGetter = getter != null && !isScalar(f.asType());
                if (!settable && !mutableViaGetter) {
                    continue;
                }
                List<Object> sources = listOf(f, f.asType(), getter, setter);
                if (setter != null) {
                    sources.add(setter.getParameters().get(0));
                }
                Map<String, AnnotationMirror> a = Mirrors.collect(sources);
                String doc = Docs.of(elements, f);
                if (doc == null) {
                    doc = Docs.of(elements, getter);
                }
                Object init = Initializers.ABSENT;
                if (initializers != null) {
                    init = initializers.of(f);
                } else if (!warnedNoTrees) {
                    warnedNoTrees = true;
                    warn(f, "this compiler does not expose the javac tree API, so field initializers are not"
                            + " exported as defaults; set defaults in application.yml or use records with"
                            + " @DefaultValue");
                    init = Initializers.UNKNOWN;
                }
                out.add(new Prop(name, boundName(a, name), f.asType(), f, a, doc, init, null,
                        itemAnnotations(f.asType(), getter == null ? null : getter.getReturnType(),
                                setter == null ? null : setter.getParameters().get(0).asType())));
            }
        }
        return out;
    }

    private static List<Object> listOf(Object... items) {
        List<Object> l = new ArrayList<>();
        for (Object o : items) {
            if (o != null) {
                l.add(o);
            }
        }
        return l;
    }

    private ExecutableElement canonicalConstructor(TypeElement te) {
        List<? extends RecordComponentElement> comps = te.getRecordComponents();
        for (ExecutableElement c : ElementFilter.constructorsIn(te.getEnclosedElements())) {
            if (c.getParameters().size() != comps.size()) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < comps.size(); i++) {
                same &= types.isSameType(types.erasure(c.getParameters().get(i).asType()),
                        types.erasure(comps.get(i).asType()));
            }
            if (same) {
                return c;
            }
        }
        return null;
    }

    /** The constructor Spring binds with, or {@code null} for a JavaBean. */
    private ExecutableElement bindConstructor(TypeElement te) {
        List<ExecutableElement> ctors = ElementFilter.constructorsIn(te.getEnclosedElements());
        for (ExecutableElement c : ctors) {
            if (annotation(c, SPRING_BIND + "ConstructorBinding") != null) {
                return c;
            }
        }
        if (ctors.size() == 1 && !ctors.get(0).getParameters().isEmpty()
                && annotation(ctors.get(0), "org.springframework.beans.factory.annotation.Autowired") == null) {
            return ctors.get(0);
        }
        return null;
    }

    private String boundName(Map<String, AnnotationMirror> a, String javaName) {
        AnnotationMirror name = a.get(SPRING_BIND + "Name");
        return name == null ? javaName : Mirrors.string(elements, name, "value");
    }

    private List<String> defaultValue(Map<String, AnnotationMirror> a) {
        AnnotationMirror dv = a.get(SPRING_BIND + "DefaultValue");
        return dv == null ? null : Mirrors.strings(elements, dv, "value");
    }

    private String groupOf(TypeElement te, Map<String, AnnotationMirror> a) {
        AnnotationMirror g = te != null ? annotation(te, D + "Group") : a.get(D + "Group");
        return g == null ? null : Mirrors.string(elements, g, "value");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Types

    private enum What { VAR, NESTED, SKIP }

    /** How a Java type maps to the contract. */
    private record Kind(What what, VarType type, String items, String elementType, List<String> values,
            String reason) {
        static Kind var(VarType t) {
            return new Kind(What.VAR, t, null, null, null, null);
        }

        static Kind skip(String reason) {
            return new Kind(What.SKIP, null, null, null, null, reason);
        }
    }

    private Kind classify(TypeMirror t, Map<String, AnnotationMirror> a) {
        if (a.containsKey(D + "Json")) {
            return Kind.var(VarType.JSON);
        }
        boolean urlSchemes = a.containsKey(D + "UrlSchemes");
        switch (t.getKind()) {
            case INT, LONG, SHORT, BYTE:
                return Kind.var(VarType.INT);
            case FLOAT, DOUBLE:
                return Kind.var(VarType.FLOAT);
            case BOOLEAN:
                return Kind.var(VarType.BOOL);
            case CHAR:
                return Kind.var(VarType.STRING);
            case ARRAY:
                return list(((ArrayType) t).getComponentType());
            case DECLARED:
                break;
            default:
                return Kind.skip("type " + t + " is not supported");
        }
        DeclaredType dt = (DeclaredType) t;
        TypeElement te = (TypeElement) dt.asElement();
        String name = te.getQualifiedName().toString();
        switch (name) {
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.math.BigInteger":
                return Kind.var(VarType.INT);
            case "java.lang.Double", "java.lang.Float", "java.math.BigDecimal":
                return Kind.var(VarType.FLOAT);
            case "java.lang.Boolean":
                return Kind.var(VarType.BOOL);
            case "java.time.Duration":
                return Kind.var(VarType.DURATION);
            case "java.net.URI", "java.net.URL":
                return Kind.var(VarType.URL);
            case "java.lang.String":
                return Kind.var(urlSchemes ? VarType.URL : VarType.STRING);
            default:
                break;
        }
        if (STRING_LIKE.contains(name)) {
            return Kind.var(VarType.STRING);
        }
        if (te.getKind() == ElementKind.ENUM) {
            return new Kind(What.VAR, VarType.ENUM, null, null, enumConstants(te), null);
        }
        if (isA(t, "java.util.Map")) {
            return Kind.skip("maps cannot be expressed in v1alpha1 types");
        }
        if (isA(t, "java.util.Collection")) {
            if (dt.getTypeArguments().size() != 1) {
                return Kind.skip("raw collections are not supported");
            }
            return list(dt.getTypeArguments().get(0));
        }
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")) {
            return Kind.skip("type " + name + " is not supported");
        }
        if (te.getKind() == ElementKind.CLASS || te.getKind() == ElementKind.RECORD) {
            return new Kind(What.NESTED, null, null, null, null, null);
        }
        return Kind.skip("type " + name + " is not supported");
    }

    private Kind list(TypeMirror element) {
        String elementType = typeName(element);
        switch (element.getKind()) {
            case INT, LONG, SHORT, BYTE:
                return new Kind(What.VAR, VarType.LIST, "int", elementType, null, null);
            case DECLARED:
                break;
            default:
                return Kind.skip("lists of " + element + " are not supported");
        }
        TypeElement te = (TypeElement) types.asElement(element);
        String n = te.getQualifiedName().toString();
        if (Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte").contains(n)) {
            return new Kind(What.VAR, VarType.LIST, "int", typeName(element), null, null);
        }
        if (n.equals("java.lang.String") || te.getKind() == ElementKind.ENUM || STRING_LIKE.contains(n)
                || n.equals("java.net.URI")) {
            return new Kind(What.VAR, VarType.LIST, "string", typeName(element), null, null);
        }
        return Kind.skip("lists of " + n + " (objects) cannot be expressed in v1alpha1 types");
    }

    /** The name {@code Class.forName} takes: binary names for classes, {@code int} for primitives. */
    private String typeName(TypeMirror t) {
        TypeMirror e = types.erasure(t);
        if (e.getKind() == TypeKind.DECLARED) {
            return elements.getBinaryName((TypeElement) types.asElement(e)).toString();
        }
        if (e.getKind() == TypeKind.ARRAY) {
            return typeName(((ArrayType) e).getComponentType()) + "[]";
        }
        return e.getKind().isPrimitive() ? e.getKind().name().toLowerCase(Locale.ROOT) : e.toString();
    }

    private static final String BV = "jakarta.validation.constraints.";
    private static final String HV = "org.hibernate.validator.constraints.";
    private static final List<String> NUMERIC = List.of(BV + "Min", BV + "Max", BV + "DecimalMin", BV + "DecimalMax",
            BV + "Positive", BV + "PositiveOrZero", BV + "Negative", BV + "NegativeOrZero", BV + "Digits",
            HV + "Range");
    private static final List<String> TEXT = List.of(BV + "Pattern", BV + "Email", BV + "NotBlank", HV + "URL",
            HV + "Length");
    private static final List<String> SIZED = List.of(BV + "Size", BV + "NotEmpty");
    private static final List<String> DURATION = List.of(HV + "time.DurationMin", HV + "time.DurationMax",
            "org.springframework.boot.convert.DurationUnit");
    private static final List<String> VARS_ONLY = List.of(D + "Secret", D + "UrlSchemes", D + "Json", D + "Examples",
            "org.springframework.boot.convert.Delimiter");

    /**
     * SPEC section 11.2 item 2: an annotation that does not apply to the property's type would be dropped from the
     * contract (or fail only at startup), so it is a build error that names the property and the types it fits.
     *
     * @return whether the property can be exported
     */
    private boolean placement(Prop p, Kind kind, String envName) {
        Map<String, AnnotationMirror> a = p.annotations();
        TypeMirror t = p.type();
        String type = simpleType(t);
        List<String> problems = new ArrayList<>();
        if (kind.what() == What.NESTED) {
            for (String name : VARS_ONLY) {
                if (a.containsKey(name)) {
                    problems.add("@" + simple(name) + " applies to a single value, not to the nested class " + type
                            + "; put it on the properties inside " + type);
                }
            }
        } else if (kind.what() == What.VAR) {
            boolean json = kind.type() == VarType.JSON;
            boolean text = isA(t, "java.lang.CharSequence");
            boolean numeric = t.getKind().isPrimitive() ? t.getKind() != TypeKind.BOOLEAN && t.getKind() != TypeKind.CHAR
                    : isA(t, "java.lang.Number");
            boolean sized = text || t.getKind() == TypeKind.ARRAY || isA(t, "java.util.Collection")
                    || isA(t, "java.util.Map");
            boolean duration = isA(t, "java.time.Duration");
            if (a.containsKey(D + "UrlSchemes") && !json && !text && !isA(t, "java.net.URI") && !isA(t, "java.net.URL")) {
                problems.add("@UrlSchemes applies to String, URI or URL, not " + type);
            }
            for (String name : NUMERIC) {
                if (a.containsKey(name) && !numeric && !json) {
                    problems.add("@" + simple(name) + " applies to numbers, not " + type);
                }
            }
            for (String name : TEXT) {
                if (a.containsKey(name) && !text && !json) {
                    problems.add("@" + simple(name) + " applies to String, not " + type);
                }
            }
            for (String name : SIZED) {
                if (a.containsKey(name) && !sized) {
                    problems.add("@" + simple(name) + " applies to String, a collection, a map or an array, not "
                            + type);
                }
            }
            for (String name : DURATION) {
                if (a.containsKey(name) && !duration && !json) {
                    problems.add("@" + simple(name) + " applies to Duration, not " + type);
                }
            }
            if (a.containsKey("org.springframework.boot.convert.Delimiter") && kind.type() != VarType.LIST) {
                problems.add("@Delimiter applies to a list, set or array, not " + type);
            }
        }
        for (String problem : problems) {
            error(p.element(), envName + ": " + problem);
        }
        return problems.isEmpty();
    }

    private static String simple(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private String simpleType(TypeMirror t) {
        TypeMirror e = types.erasure(t);
        if (e.getKind().isPrimitive()) {
            return e.getKind().name().toLowerCase(Locale.ROOT);
        }
        if (e.getKind() == TypeKind.ARRAY) {
            return simpleType(((ArrayType) e).getComponentType()) + "[]";
        }
        Element el = types.asElement(e);
        return el == null ? e.toString() : el.getSimpleName().toString();
    }

    private boolean isScalar(TypeMirror t) {
        Kind k = classify(t, Map.of());
        return k.what() == What.VAR;
    }

    private boolean isA(TypeMirror t, String qualified) {
        TypeElement target = elements.getTypeElement(qualified);
        return target != null && types.isAssignable(types.erasure(t), types.erasure(target.asType()));
    }

    private static List<String> enumConstants(TypeElement te) {
        List<String> values = new ArrayList<>();
        for (Element e : te.getEnclosedElements()) {
            if (e.getKind() == ElementKind.ENUM_CONSTANT) {
                values.add(e.getSimpleName().toString());
            }
        }
        return values;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Variables

    private record PendingVar(VarSpec spec, Prop prop, Kind kind, String durationUnit) {
    }

    private void var(String root, Prop p, Kind kind, String configKey, String javaPath, String group) {
        Map<String, AnnotationMirror> a = p.annotations();
        String envName = envName(root, configKey);
        if (contract.vars.containsKey(envName)) {
            String other = contract.vars.get(envName).configKey;
            error(p.element(), envName + " is declared twice" + (other == null || other.equals(configKey) ? ""
                    : ": " + other + " and " + configKey + " both map to it; rename one, or use"
                    + " @Docuconf(envNames = EnvNames.COMPACT)"));
            return;
        }
        VarSpec v = new VarSpec(envName, kind.type(), description(a, p.doc()));
        v.configKey = configKey;
        v.secret = a.containsKey(D + "Secret");
        if (v.secret) {
            secretToString(p, envName);
        }
        v.group = group;
        AnnotationMirror ex = a.get(D + "Examples");
        if (ex != null) {
            v.examples = Mirrors.strings(elements, ex, "value");
        }
        v.deprecated = deprecation(root, a);
        Constraints c = Constraints.read(elements, a);
        for (String err : c.errors) {
            error(p.element(), envName + ": " + err);
        }
        for (String w : c.warnings) {
            warn(p.element(), envName + ": " + w);
        }
        String unit = null;
        switch (kind.type()) {
            case STRING -> {
                v.minLength = c.sizeMin;
                if ((c.notBlank || c.notEmpty) && (v.minLength == null || v.minLength < 1)) {
                    v.minLength = 1;
                }
                v.maxLength = c.sizeMax;
                v.pattern = c.pattern != null ? c.pattern : c.notBlank ? "\\S" : null;
                if (typeName(p.type()).equals("org.springframework.util.unit.DataSize")) {
                    v.pattern = "^[0-9]+(B|KB|MB|GB|TB)?$";
                }
            }
            case INT -> {
                long[] range = intRange(p.type());
                v.min = intLower(c, range);
                v.max = intUpper(c, range);
            }
            case FLOAT -> {
                // Exclusive bounds (@Positive, @DecimalMin(inclusive = false)) have no contract form; they are
                // still checked at startup by Bean Validation.
                if (c.min != null && !c.minExclusive) {
                    v.min = c.min;
                }
                if (c.max != null && !c.maxExclusive) {
                    v.max = c.max;
                }
            }
            case DURATION -> {
                // Spring's simple format takes one unit ("90s"), so canonical Go durations such as "1m30s" would
                // not parse; Spring does parse ISO-8601 ("PT1M30S").
                v.encoding = "iso8601";
                v.min = c.durationMin == null ? null : GoDuration.format(c.durationMin);
                v.max = c.durationMax == null ? null : GoDuration.format(c.durationMax);
                AnnotationMirror du = a.get("org.springframework.boot.convert.DurationUnit");
                unit = du == null ? null : Mirrors.string(elements, du, "value");
            }
            case URL -> {
                AnnotationMirror s = a.get(D + "UrlSchemes");
                if (s != null) {
                    v.schemes = Mirrors.strings(elements, s, "value");
                }
            }
            case ENUM -> {
                AnnotationMirror ev = a.get(D + "EnumValues");
                EnumCase style = EnumCase.valueOf(ev != null ? Mirrors.string(elements, ev, "value")
                        : settings.get(root)[0]);
                v.values = kind.values().stream().map(style::apply).toList();
            }
            case LIST -> {
                v.items = kind.items();
                v.encoding = "csv";
                AnnotationMirror delim = a.get("org.springframework.boot.convert.Delimiter");
                v.separator = delim == null ? "," : Mirrors.string(elements, delim, "value");
                if (v.separator.isEmpty() || v.separator.equals("-")) {
                    error(p.element(), envName + ": @Delimiter(\"" + v.separator + "\") cannot be rendered");
                }
                v.minItems = c.sizeMin;
                if (c.notEmpty && (v.minItems == null || v.minItems < 1)) {
                    v.minItems = 1;
                }
                v.maxItems = c.sizeMax;
                if ("int".equals(v.items)) {
                    Constraints item = Constraints.read(elements, p.itemAnnotations());
                    for (String err : item.errors) {
                        error(p.element(), envName + " items: " + err);
                    }
                    long[] range = intRange(itemType(p.type()));
                    v.itemMin = intLower(item, range);
                    v.itemMax = intUpper(item, range);
                }
            }
            case JSON -> {
                SchemaGenerator g = new SchemaGenerator(elements, types);
                v.schema = sized(g.schema(p.type()), c);
                for (String err : g.errors()) {
                    error(p.element(), envName + ": " + err);
                }
            }
            default -> {
            }
        }
        v.required = c.requiresValue();
        contract.vars.put(envName, v);
        elementsByInput.put(envName, p.element());
        bindings.vars.put(envName, new Bindings.PropertyBinding(root, javaPath, configKey,
                typeName(p.type()), kind.elementType(), unit));
        pending.add(new PendingVar(v, p, kind, unit));
    }

    /** Carries a top-level {@code @Size}/{@code @NotEmpty} on a {@code @Json} property into its JSON Schema. */
    private static Map<String, Object> sized(Map<String, Object> schema, Constraints c) {
        Integer min = c.sizeMin;
        if (c.notEmpty && (min == null || min < 1)) {
            min = 1;
        }
        if (schema == null || (min == null && c.sizeMax == null)) {
            return schema;
        }
        Object type = schema.get("type");
        String lo = "array".equals(type) ? "minItems" : "object".equals(type) ? "minProperties"
                : "string".equals(type) ? "minLength" : null;
        if (lo == null) {
            return schema;
        }
        Map<String, Object> out = new LinkedHashMap<>(schema);
        if (min != null) {
            out.put(lo, min);
        }
        if (c.sizeMax != null) {
            out.put(lo.replace("min", "max"), c.sizeMax);
        }
        return out;
    }

    /**
     * The range a Java integer type holds when it is narrower than the contract's 64 bits, so the platform never
     * accepts a value the app cannot hold (SPEC §5); {@code null} for {@code long} and {@code BigInteger}.
     */
    private long[] intRange(TypeMirror t) {
        if (t == null) {
            return null;
        }
        TypeKind k = t.getKind();
        if (k == TypeKind.DECLARED) {
            k = switch (((TypeElement) types.asElement(t)).getQualifiedName().toString()) {
                case "java.lang.Integer" -> TypeKind.INT;
                case "java.lang.Short" -> TypeKind.SHORT;
                case "java.lang.Byte" -> TypeKind.BYTE;
                default -> TypeKind.LONG;
            };
        }
        return switch (k) {
            case INT -> new long[] {Integer.MIN_VALUE, Integer.MAX_VALUE};
            case SHORT -> new long[] {Short.MIN_VALUE, Short.MAX_VALUE};
            case BYTE -> new long[] {Byte.MIN_VALUE, Byte.MAX_VALUE};
            default -> null;
        };
    }

    /** The integer lower bound from the constraints, raised to what the type holds. */
    private static Long intLower(Constraints c, long[] range) {
        Long min = null;
        if (c.min != null) {
            BigDecimal m = c.minExclusive ? c.min.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE)
                    : c.min.setScale(0, RoundingMode.CEILING);
            min = m.max(BigDecimal.valueOf(Long.MIN_VALUE)).longValue();
        }
        if (range != null && (min == null || min < range[0])) {
            min = range[0];
        }
        return min;
    }

    /** The integer upper bound from the constraints, lowered to what the type holds. */
    private static Long intUpper(Constraints c, long[] range) {
        Long max = null;
        if (c.max != null) {
            BigDecimal m = c.maxExclusive ? c.max.setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE)
                    : c.max.setScale(0, RoundingMode.FLOOR);
            max = m.min(BigDecimal.valueOf(Long.MAX_VALUE)).longValue();
        }
        if (range != null && (max == null || max > range[1])) {
            max = range[1];
        }
        return max;
    }

    private String description(Map<String, AnnotationMirror> a, String doc) {
        AnnotationMirror d = a.get(D + "Description");
        return d != null ? Mirrors.string(elements, d, "value") : doc;
    }

    private final Set<String> toStringChecked = new java.util.HashSet<>();

    /**
     * Secrets must never print by accident: a record's generated {@code toString()} and Lombok's print every
     * property, so a class holding a {@code @Secret} must print it some other way.
     */
    private void secretToString(Prop p, String input) {
        Element owner = p.element().getEnclosingElement();
        if (owner instanceof ExecutableElement ctor) {
            owner = ctor.getEnclosingElement();
        }
        if (!(owner instanceof TypeElement te)) {
            return;
        }
        String fix = "; override it: `@Override public String toString() { return Redacted.toString(this); }`"
                + " (dev.docuconf.Redacted prints secrets as [redacted])";
        boolean ownToString = ElementFilter.methodsIn(te.getEnclosedElements()).stream()
                .anyMatch(m -> m.getSimpleName().contentEquals("toString") && m.getParameters().isEmpty()
                        && elements.getOrigin(m) == Elements.Origin.EXPLICIT
                        && (initializers == null || initializers.inSource(m)));
        if (te.getKind() == ElementKind.RECORD && !ownToString) {
            if (!toStringChecked.add(te.getQualifiedName().toString())) {
                return;
            }
            error(te, input + ": " + te.getSimpleName() + " is a record, and its generated toString() prints @Secret "
                    + p.javaName() + fix);
            return;
        }
        boolean lombok = annotation(te, "lombok.Data") != null || annotation(te, "lombok.Value") != null
                || annotation(te, "lombok.ToString") != null;
        if (lombok && !ownToString && annotation(p.element(), "lombok.ToString.Exclude") == null) {
            error(te, input + ": Lombok's toString() for " + te.getSimpleName() + " prints @Secret " + p.javaName()
                    + "; mark the field @ToString.Exclude" + fix.replace("; override it:", ", or override it:"));
        }
    }

    /** The environment variable for a property, in the naming its root class chose. */
    private String envName(String root, String configKey) {
        String[] s = settings.get(root);
        return s != null && "UNDERSCORED".equals(s[1]) ? Names.underscoredEnvName(configKey)
                : Names.envName(configKey);
    }

    private Deprecation deprecation(String root, Map<String, AnnotationMirror> a) {
        AnnotationMirror dcp = a.get("org.springframework.boot.context.properties.DeprecatedConfigurationProperty");
        if (dcp != null) {
            String reason = Mirrors.string(elements, dcp, "reason");
            String replacement = Mirrors.string(elements, dcp, "replacement");
            String replacedBy = replacement == null || replacement.isEmpty() ? null : envName(root, replacement);
            return new Deprecation(reason == null || reason.isEmpty() ? "Deprecated" : reason, replacedBy);
        }
        if (a.containsKey("java.lang.Deprecated")) {
            return new Deprecation("Deprecated", null);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Files

    private record PendingPassword(FileSpec spec, String root, String configKey, Element element) {
    }

    private void file(String root, Prop p, String annotationName, String configKey, String javaPath,
            String keyPrefix, String group) {
        Map<String, AnnotationMirror> a = p.annotations();
        AnnotationMirror m = a.get(annotationName);
        String path = Mirrors.string(elements, m, "value");
        String explicitName = Mirrors.string(elements, m, "name");
        String name = explicitName == null || explicitName.isEmpty() ? Names.kebab(p.javaName()) : explicitName;
        String typeName = typeName(p.type());
        FileType type;
        String expected;
        switch (annotationName.substring(D.length())) {
            case "TlsFile" -> {
                type = FileType.TLS;
                expected = "dev.docuconf.TlsKeyPair";
            }
            case "CaBundleFile" -> {
                type = FileType.CA_BUNDLE;
                expected = "dev.docuconf.CaBundle";
            }
            case "KeystoreFile" -> {
                type = FileType.KEYSTORE;
                expected = "dev.docuconf.Keystore";
            }
            case "TextFile" -> {
                type = FileType.TEXT;
                expected = "java.lang.String";
            }
            case "BinaryFile" -> {
                type = FileType.BINARY;
                expected = "java.nio.file.Path";
            }
            default -> {
                type = FileType.CONFIG;
                expected = null;
            }
        }
        if (expected != null && !typeName.equals(expected)) {
            error(p.element(), "@" + annotationName.substring(D.length()) + " needs a property of type " + expected
                    + ", not " + typeName);
            return;
        }
        if (type == FileType.CONFIG && (p.type().getKind() != TypeKind.DECLARED || classify(p.type(), Map.of())
                .what() != What.NESTED)) {
            error(p.element(), "@ConfigFile needs a property whose type is a class or record (got " + typeName + ")");
            return;
        }
        if (contract.files.containsKey(name)) {
            error(p.element(), "file input " + name + " is declared twice; set name = \"...\"");
            return;
        }
        FileSpec f = new FileSpec(name, type, description(a, p.doc()), path);
        Constraints c = Constraints.read(elements, a);
        f.required = c.requiresValue();
        f.secret = f.secret || a.containsKey(D + "Secret");
        if (a.containsKey(D + "Secret") && (type == FileType.TEXT || type == FileType.CONFIG)) {
            secretToString(p, name);
        }
        f.group = group;
        f.deprecated = deprecation(root, a);
        String pathEnv = Mirrors.string(elements, m, "pathEnv");
        f.pathEnv = pathEnv == null || pathEnv.isEmpty() ? null : pathEnv;
        f.reload = Mirrors.string(elements, m, "reload").toLowerCase(Locale.ROOT);
        long maxSize = Mirrors.number(elements, m, "maxSize");
        f.maxSize = maxSize > 0 ? maxSize : null;
        switch (type) {
            case CONFIG -> {
                String format = Mirrors.string(elements, m, "format").toLowerCase(Locale.ROOT);
                if (format.equals("auto")) {
                    String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
                    format = lower.endsWith(".json") ? "json" : lower.endsWith(".toml") ? "toml"
                            : lower.endsWith(".yaml") || lower.endsWith(".yml") ? "yaml" : null;
                    if (format == null) {
                        error(p.element(), name + ": cannot tell the format of " + path
                                + " from its extension; set format = JSON, YAML or TOML");
                        return;
                    }
                }
                f.format = format;
                SchemaGenerator g = new SchemaGenerator(elements, types);
                f.schema = g.schema(p.type());
                for (String err : g.errors()) {
                    error(p.element(), name + ": " + err);
                }
            }
            case TLS -> {
                List<String> dns = Mirrors.strings(elements, m, "dnsNames");
                f.dnsNames = dns.isEmpty() ? null : dns;
                List<String> algs = new ArrayList<>();
                for (String alg : Mirrors.strings(elements, m, "keyAlgorithms")) {
                    algs.add(alg.equals("ED25519") ? "Ed25519" : alg);
                }
                f.keyAlgorithms = algs.isEmpty() ? null : algs;
                String minRemaining = Mirrors.string(elements, m, "minRemaining");
                if (minRemaining != null && !minRemaining.isEmpty()) {
                    f.minRemaining = GoDuration.isValid(minRemaining) ? GoDuration.canonical(minRemaining)
                            : minRemaining;
                }
                f.requireCA = Mirrors.bool(elements, m, "requireCA");
            }
            case CA_BUNDLE -> f.minCertificates = (int) Mirrors.number(elements, m, "minCertificates");
            case KEYSTORE -> {
                f.format = Mirrors.string(elements, m, "format").toLowerCase(Locale.ROOT);
                String pw = Mirrors.string(elements, m, "passwordProperty");
                if (pw != null && !pw.isEmpty()) {
                    passwords.add(new PendingPassword(f, root, keyPrefix + "." + Names.dashed(pw), p.element()));
                }
            }
            case TEXT -> {
                String pattern = Mirrors.string(elements, m, "pattern");
                f.pattern = pattern == null || pattern.isEmpty() ? null : pattern;
                long min = Mirrors.number(elements, m, "minLength");
                f.minLength = min > 0 ? (int) min : null;
                long max = Mirrors.number(elements, m, "maxLength");
                f.maxLength = max >= 0 ? (int) max : null;
            }
            default -> {
            }
        }
        contract.files.put(name, f);
        elementsByInput.put(name, p.element());
        bindings.files.put(name, new Bindings.PropertyBinding(root, javaPath, configKey, typeName, null, null));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Finishing: defaults, profiles, validation, output

    private void analyse() {
        Path resources = resourcesDir();
        SpringFiles files;
        try {
            files = SpringFiles.read(resources, w -> warn(null, "docuconf: " + w));
        } catch (IOException e) {
            error(null, "docuconf: could not read application*.yml from " + resources + ": " + e.getMessage());
            return;
        }
        bindings.springFiles = files.hashes;
        contract.name = serviceName(files);
        contract.appVersion = processingEnv.getOptions().get("docuconf.appVersion");
        contract.sdkVersion = sdkVersion();

        for (PendingVar pv : pending) {
            defaults(pv, files);
        }
        for (PendingPassword pp : passwords) {
            pp.spec().passwordVar = envName(pp.root(), pp.configKey());
        }
        profiles(files);
        watchableOverlays();

        DeclarationValidator.Result result = DeclarationValidator.validate(contract);
        for (String w : result.warnings()) {
            warn(elementFor(w), w);
        }
        for (String e : result.errors()) {
            error(elementFor(e), e);
        }
        featureFlags(result.warnings());
    }

    private void defaults(PendingVar pv, SpringFiles files) {
        VarSpec v = pv.spec();
        Prop p = pv.prop();
        Element el = p.element();
        Constraints c = Constraints.read(elements, p.annotations());
        Object fromCode = null;
        boolean unknown = false;
        boolean implicitZero = false;
        if (p.defaultValue() != null) {
            List<String> dv = p.defaultValue();
            fromCode = v.type == VarType.LIST ? dv : dv.isEmpty() ? null : dv.get(0);
        } else if (p.initializer() == Initializers.UNKNOWN) {
            unknown = true;
        } else if (p.initializer() != Initializers.ABSENT) {
            fromCode = p.initializer();
        } else if (p.type().getKind().isPrimitive()) {
            // Spring leaves an unset primitive at its zero value.
            fromCode = p.type().getKind() == TypeKind.BOOLEAN ? Boolean.FALSE
                    : p.type().getKind() == TypeKind.CHAR ? null : 0;
            implicitZero = fromCode != null && !Boolean.FALSE.equals(fromCode);
        }
        // An empty initializer on a property that must not be empty only avoids null: it is not a default.
        if (c.requiresValue() && (("".equals(fromCode)) || (fromCode instanceof List<?> l && l.isEmpty()))) {
            fromCode = null;
        }
        SpringFiles.Value fromFile = lookup(files.base, v.configKey);
        if (fromFile != null && isPlaceholder(fromFile.value())) {
            warn(el, v.name + ": " + fromFile.source() + " sets " + v.configKey + " from a ${...} placeholder;"
                    + " the platform should set " + v.name + ", which takes precedence");
            fromFile = null;
        }
        Object raw = fromFile != null ? fromFile.value() : fromCode;
        String where = fromFile != null ? fromFile.source() : "the code";
        if (raw == null) {
            if (unknown) {
                warn(el, v.name + ": the initializer is not a constant docuconf can read, so the contract has no"
                        + " default and the variable is optional; use a literal, or set the default in"
                        + " application.yml");
                v.required = false;
            }
            return;
        }
        if (v.secret) {
            error(el, v.name + ": a secret cannot have a default (found one in " + where + ")");
            return;
        }
        if (fromFile == null && implicitZero && breaksBounds(v)) {
            // The user wrote no default: Spring's zero for an unset primitive is what breaks the constraint.
            String java = p.type().getKind().name().toLowerCase(Locale.ROOT) + " " + p.javaName();
            boolean record = el.getKind() == ElementKind.RECORD_COMPONENT || el.getKind() == ElementKind.PARAMETER;
            error(el, v.name + ": `" + java + "` has no " + (record ? "@DefaultValue" : "initializer or default")
                    + ", so Spring binds 0 when " + v.name + " is unset, which breaks " + bounds(v) + ". Add "
                    + (record ? "@DefaultValue(\"" + suggestion(v) + "\")" : "an initializer such as `= "
                    + suggestion(v) + "`") + ", or make it `@NotNull " + boxed(p.type()) + "` to require it.");
            return;
        }
        try {
            v.defaultValue = Values.toContract(v, raw, pv.durationUnit());
            v.required = false;
        } catch (IllegalArgumentException e) {
            error(el, v.name + ": the default in " + where + " is not a valid " + v.type.id() + ": " + e.getMessage());
        }
    }

    /** Whether zero falls outside the variable's min and max. */
    private static boolean breaksBounds(VarSpec v) {
        BigDecimal min = v.min == null ? null : new BigDecimal(v.min.toString());
        BigDecimal max = v.max == null ? null : new BigDecimal(v.max.toString());
        return (min != null && min.signum() > 0) || (max != null && max.signum() < 0);
    }

    private static String bounds(VarSpec v) {
        BigDecimal min = v.min == null ? null : new BigDecimal(v.min.toString());
        return min != null && min.signum() > 0 ? "its minimum " + v.min : "its maximum " + v.max;
    }

    private static String suggestion(VarSpec v) {
        BigDecimal min = v.min == null ? null : new BigDecimal(v.min.toString());
        return min != null && min.signum() > 0 ? v.min.toString() : v.max.toString();
    }

    private String boxed(TypeMirror t) {
        return t.getKind().isPrimitive() ? types.boxedClass((javax.lang.model.type.PrimitiveType) t).getSimpleName()
                .toString() : t.toString();
    }

    /**
     * SPEC section 10: names that look like feature flags warn. Spring names carry the properties prefix
     * ({@code ORDERS_ENABLEBETA}), so the property's own name ({@code enable-beta}) is what is checked.
     */
    private void featureFlags(List<String> alreadyWarned) {
        for (VarSpec v : contract.vars.values()) {
            if (v.configKey == null || alreadyWarned.stream().anyMatch(w -> w.startsWith(v.name + ":"))) {
                continue;
            }
            String leaf = v.configKey.substring(v.configKey.lastIndexOf('.') + 1);
            if (leaf.matches("(ff|feature|feature-flag|enable|enabled)-.+")) {
                warn(elementsByInput.get(v.name), v.name + ": " + leaf + " looks like a feature flag; flags that"
                        + " change without a rollout belong in a flag service (SPEC section 10)");
            }
        }
    }

    private void profiles(SpringFiles files) {
        Profiles profiles = new Profiles();
        for (Map.Entry<String, Map<String, SpringFiles.Value>> e : files.profiles.entrySet()) {
            Map<String, Object> values = new TreeMap<>();
            for (PendingVar pv : pending) {
                VarSpec v = pv.spec();
                SpringFiles.Value value = lookup(e.getValue(), v.configKey);
                if (value == null || isPlaceholder(value.value())) {
                    continue;
                }
                if (v.secret) {
                    error(pv.prop().element(), v.name + ": a secret cannot have a value in " + value.source()
                            + "; it would ship inside the image");
                    continue;
                }
                try {
                    values.put(v.name, Values.toContract(v, value.value(), pv.durationUnit()));
                } catch (IllegalArgumentException ex) {
                    error(pv.prop().element(), v.name + ": the value in " + value.source() + " is not a valid "
                            + v.type.id() + ": " + ex.getMessage());
                }
            }
            if (!values.isEmpty()) {
                profiles.defaults.put(e.getKey(), values);
            }
        }
        if (profiles.defaults.isEmpty()) {
            return;
        }
        String selector = "SPRING_PROFILES_ACTIVE";
        SpringFiles.Value active = files.baseValue("spring.profiles.active");
        SpringFiles.Value dflt = files.baseValue("spring.profiles.default");
        profiles.selector = selector;
        profiles.defaultProfile = active != null ? active.value().toString()
                : dflt != null ? dflt.value().toString() : "default";
        if (!contract.vars.containsKey(selector)) {
            VarSpec s = new VarSpec(selector, VarType.STRING,
                    "Active Spring profile; selects the application-{profile}.yml baked into the image");
            // v1alpha1 profiles select one file, so one profile name: SPRING_PROFILES_ACTIVE=prod,eu would apply
            // two files the contract cannot describe.
            s.pattern = "^[^,]+$";
            s.configKey = "spring.profiles.active";
            // SPEC §4.4: the added selector defaults to profiles.default, the profile in effect when it is unset.
            s.defaultValue = profiles.defaultProfile;
            contract.vars.put(selector, s);
        }
        contract.profiles = profiles;
    }

    /**
     * SPEC §11.2 item 8: only claim {@code watch} where it is kept. docuconf-spring rebinds JavaBeans in place when
     * a watched overlay changes; a class bound through its constructor is immutable, so any variable it holds that
     * an overlay could carry (every non-secret one) would silently keep its old value.
     */
    private void watchableOverlays() {
        for (OverlaySpec o : contract.overlays.values()) {
            if (!"watch".equals(o.reload)) {
                continue;
            }
            Set<String> reported = new TreeSet<>();
            bindings.vars.forEach((name, b) -> {
                VarSpec v = contract.vars.get(name);
                TypeElement te = constructorBound.get(b.className());
                if (te != null && v != null && !v.secret && reported.add(b.className())) {
                    error(overlayElements.get(o.name), "@ConfigOverlay(name = \"" + o.name + "\", reload = WATCH):"
                            + " " + te.getQualifiedName() + " is bound through its constructor (a record or"
                            + " @ConstructorBinding), so its values cannot be rebound when the overlay changes;"
                            + " make it a JavaBean with setters, or use Reload.RESTART");
                }
            });
        }
    }

    private static SpringFiles.Value lookup(Map<String, SpringFiles.Value> values, String configKey) {
        String key = Names.canonical(configKey);
        SpringFiles.Value v = values.get(key);
        if (v != null) {
            return v;
        }
        // properties-style lists: key[0], key[1], ...
        List<Object> items = new ArrayList<>();
        String source = null;
        for (int i = 0;; i++) {
            SpringFiles.Value item = values.get(key + "[" + i + "]");
            if (item == null) {
                break;
            }
            items.add(item.value());
            source = item.source();
        }
        return items.isEmpty() ? null : new SpringFiles.Value(items, source);
    }

    private static boolean isPlaceholder(Object value) {
        return value instanceof String s && s.contains("${");
    }

    private String serviceName(SpringFiles files) {
        Set<String> names = new TreeSet<>(services.values());
        if (names.size() > 1) {
            error(null, "docuconf: @Docuconf classes name different services: " + names);
        }
        if (!names.isEmpty()) {
            return names.iterator().next();
        }
        SpringFiles.Value app = files.baseValue("spring.application.name");
        if (app != null && !isPlaceholder(app.value())) {
            return app.value().toString();
        }
        String option = processingEnv.getOptions().get("docuconf.name");
        if (option != null && !option.isEmpty()) {
            return option;
        }
        error(null, "docuconf: no service name; set @Docuconf(service = \"...\"), spring.application.name in"
                + " application.yml, or -Adocuconf.name=...");
        return "unnamed";
    }

    private String sdkVersion() {
        try (InputStream in = DocuconfProcessor.class.getResourceAsStream("/META-INF/docuconf/processor.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                return p.getProperty("version", "0.0.0");
            }
        } catch (IOException e) {
            // Fall through.
        }
        return "0.0.0";
    }

    /** The directory holding application*.yml: an option, the class output, or a Gradle-style sibling. */
    private Path resourcesDir() {
        String option = processingEnv.getOptions().get("docuconf.resources");
        if (option != null && !option.isEmpty()) {
            return Path.of(option);
        }
        Path classes;
        try {
            FileObject probe = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "",
                    "application.yml");
            classes = Path.of(probe.toUri()).getParent();
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | SecurityException e) {
            return null;
        }
        if (hasApplicationFiles(classes)) {
            return classes;
        }
        // Gradle: build/classes/java/main -> build/resources/main, then src/main/resources.
        Path sourceSet = classes.getFileName();
        Path build = classes.getParent() == null || classes.getParent().getParent() == null ? null
                : classes.getParent().getParent().getParent();
        if (build != null && sourceSet != null) {
            // The sources first: Gradle does not order compileJava after processResources, so
            // build/resources/main may still hold the previous build's copy.
            Path src = build.getParent() == null ? null
                    : build.getParent().resolve("src").resolve(sourceSet.toString()).resolve("resources");
            if (src != null && hasApplicationFiles(src)) {
                return src;
            }
            Path gradle = build.resolve("resources").resolve(sourceSet.toString());
            if (hasApplicationFiles(gradle)) {
                return gradle;
            }
        }
        return classes;
    }

    private static boolean hasApplicationFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> p.getFileName().toString().matches("application(-[^.]+)?\\.(yml|yaml|properties)"));
        } catch (IOException e) {
            return false;
        }
    }

    private void write(String location, String content) throws IOException {
        FileObject out = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", location);
        try (Writer w = out.openWriter()) {
            w.write(content);
        }
    }

    private Element elementFor(String message) {
        int colon = message.indexOf(':');
        return colon < 0 ? null : elementsByInput.get(message.substring(0, colon));
    }

    private static AnnotationMirror annotation(Element e, String name) {
        if (e == null) {
            return null;
        }
        for (AnnotationMirror m : e.getAnnotationMirrors()) {
            if (((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().contentEquals(name)) {
                return m;
            }
        }
        return null;
    }

    private void error(Element e, String message) {
        failed = true;
        print(Diagnostic.Kind.ERROR, e, message);
    }

    private void warn(Element e, String message) {
        print(Diagnostic.Kind.WARNING, e, message);
    }

    /**
     * Prints a diagnostic once. Without an element of its own it is attached to the first {@code @Docuconf} class,
     * so every docuconf diagnostic has a file and line an IDE can jump to.
     */
    private void print(Diagnostic.Kind kind, Element e, String message) {
        Element at = e != null ? positioned(e) : firstClass();
        if (reported.add(kind + "|" + message)) {
            processingEnv.getMessager().printMessage(kind, message, at);
        }
    }

    /**
     * JDK 17's javac cannot find the source position of a record component, so a diagnostic on one has no file or
     * line; the component's private field, which javac declares at the same place, has them on every JDK.
     */
    private static Element positioned(Element e) {
        if (e.getKind() == ElementKind.RECORD_COMPONENT && e.getEnclosingElement() != null) {
            for (VariableElement f : ElementFilter.fieldsIn(e.getEnclosingElement().getEnclosedElements())) {
                if (f.getSimpleName().contentEquals(e.getSimpleName())) {
                    return f;
                }
            }
        }
        return e;
    }

    private Element firstClass() {
        return classNames.isEmpty() ? null : elements.getTypeElement(classNames.get(0).replace('$', '.'));
    }
}
