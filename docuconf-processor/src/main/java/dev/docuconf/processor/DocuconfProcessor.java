package dev.docuconf.processor;

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
    private boolean failed;

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
                }
            }
        }
        if (round.processingOver() && !classNames.isEmpty()) {
            try {
                finish();
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
        bindings.classes.add(new Bindings.ClassBinding(className, prefix));
        walk(className, te, prefix, "", groupOf(te, null), 0);
    }

    /** A property as Spring's binder sees it. */
    private record Prop(String javaName, String boundName, TypeMirror type, Element element,
            Map<String, AnnotationMirror> annotations, String doc, Object initializer, List<String> defaultValue) {
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
                        Initializers.ABSENT, defaultValue(a)));
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
                        defaultValue(a)));
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
                out.add(new Prop(name, boundName(a, name), f.asType(), f, a, doc, init, null));
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
        String envName = Names.envName(configKey);
        if (contract.vars.containsKey(envName)) {
            error(p.element(), envName + " is declared twice");
            return;
        }
        VarSpec v = new VarSpec(envName, kind.type(), description(a, p.doc()));
        v.configKey = configKey;
        v.secret = a.containsKey(D + "Secret");
        v.group = group;
        AnnotationMirror ex = a.get(D + "Examples");
        if (ex != null) {
            v.examples = Mirrors.strings(elements, ex, "value");
        }
        v.deprecated = deprecation(a);
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
                if (c.min != null) {
                    v.min = (c.minExclusive ? c.min.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE)
                            : c.min.setScale(0, RoundingMode.CEILING)).longValueExact();
                }
                if (c.max != null) {
                    v.max = (c.maxExclusive ? c.max.setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE)
                            : c.max.setScale(0, RoundingMode.FLOOR)).longValueExact();
                }
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
            case ENUM -> v.values = kind.values();
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
            }
            case JSON -> {
                SchemaGenerator g = new SchemaGenerator(elements, types);
                v.schema = g.schema(p.type());
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

    private String description(Map<String, AnnotationMirror> a, String doc) {
        AnnotationMirror d = a.get(D + "Description");
        return d != null ? Mirrors.string(elements, d, "value") : doc;
    }

    private Deprecation deprecation(Map<String, AnnotationMirror> a) {
        AnnotationMirror dcp = a.get("org.springframework.boot.context.properties.DeprecatedConfigurationProperty");
        if (dcp != null) {
            String reason = Mirrors.string(elements, dcp, "reason");
            String replacement = Mirrors.string(elements, dcp, "replacement");
            String replacedBy = replacement == null || replacement.isEmpty() ? null : Names.envName(replacement);
            return new Deprecation(reason == null || reason.isEmpty() ? "Deprecated" : reason, replacedBy);
        }
        if (a.containsKey("java.lang.Deprecated")) {
            return new Deprecation("Deprecated", null);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Files

    private record PendingPassword(FileSpec spec, String configKey, Element element) {
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
        f.group = group;
        f.deprecated = deprecation(a);
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
                    passwords.add(new PendingPassword(f, keyPrefix + "." + Names.dashed(pw), p.element()));
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

    private void finish() throws IOException {
        Path resources = resourcesDir();
        SpringFiles files = SpringFiles.read(resources, w -> warn(null, "docuconf: " + w));
        contract.name = serviceName(files);
        contract.appVersion = processingEnv.getOptions().get("docuconf.appVersion");
        contract.sdkVersion = sdkVersion();

        for (PendingVar pv : pending) {
            defaults(pv, files);
        }
        for (PendingPassword pp : passwords) {
            pp.spec().passwordVar = Names.envName(pp.configKey());
        }
        profiles(files);

        DeclarationValidator.Result result = DeclarationValidator.validate(contract);
        for (String w : result.warnings()) {
            warn(elementFor(w), w);
        }
        for (String e : result.errors()) {
            error(elementFor(e), e);
        }
        if (failed) {
            return;
        }
        write(ContractJson.CUE_LOCATION, CueWriter.write(contract));
        write(ContractJson.LOCATION, ContractJson.write(new ContractBundle(contract, bindings)));
    }

    private void defaults(PendingVar pv, SpringFiles files) {
        VarSpec v = pv.spec();
        Prop p = pv.prop();
        Element el = p.element();
        Constraints c = Constraints.read(elements, p.annotations());
        Object fromCode = null;
        boolean unknown = false;
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
        try {
            v.defaultValue = Values.toContract(v, raw, pv.durationUnit());
            v.required = false;
        } catch (IllegalArgumentException e) {
            error(el, v.name + ": the default in " + where + " is not a valid " + v.type.id() + ": " + e.getMessage());
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
            Path gradle = build.resolve("resources").resolve(sourceSet.toString());
            if (hasApplicationFiles(gradle)) {
                return gradle;
            }
            Path src = build.getParent() == null ? null
                    : build.getParent().resolve("src").resolve(sourceSet.toString()).resolve("resources");
            if (src != null && hasApplicationFiles(src)) {
                return src;
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
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, e);
    }

    private void warn(Element e, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, message, e);
    }
}
