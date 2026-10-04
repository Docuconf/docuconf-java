package dev.docuconf.contract;

import dev.docuconf.check.VarChecker;
import dev.docuconf.check.Violation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a declaration before it is exported (SPEC §11.2 item 2): names, descriptions, defaults against their
 * own constraints, required without default, secrets without defaults, RE2-only patterns, file mount rules and
 * profile values. The same rules the CUE meta-schema enforces, reported with the input's name.
 */
public final class DeclarationValidator {

    private static final Set<String> RESERVED_DIRS = Set.of("/", "/app", "/bin", "/boot", "/dev", "/etc", "/etc/pki",
            "/etc/ssl", "/etc/ssl/certs", "/home", "/lib", "/lib64", "/opt", "/proc", "/root", "/run", "/sbin", "/srv",
            "/sys", "/tmp", "/usr", "/usr/lib", "/usr/local", "/usr/share", "/var", "/var/lib", "/var/run");

    private static final java.util.regex.Pattern ABS_PATH = java.util.regex.Pattern.compile("^/[A-Za-z0-9._/-]+$");

    /**
     * Problems found.
     *
     * @param errors problems that make the contract invalid, each prefixed with the input name
     * @param warnings hints, such as feature-flag-like names
     */
    public record Result(List<String> errors, List<String> warnings) {
    }

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private DeclarationValidator() {
    }

    /**
     * Validates a contract.
     *
     * @param c the contract
     * @return errors and warnings
     */
    public static Result validate(Contract c) {
        DeclarationValidator v = new DeclarationValidator();
        v.run(c);
        return new Result(v.errors, v.warnings);
    }

    private void run(Contract c) {
        if (c.name == null || !Names.SERVICE_NAME.matcher(c.name).matches()) {
            errors.add("service name \"" + c.name + "\" must be a DNS label ([a-z0-9-], at most 63 characters)");
        }
        for (VarSpec v : c.vars.values()) {
            var(v);
        }
        Map<String, String> mountDirs = new HashMap<>();
        Map<String, String> pathEnvs = new HashMap<>();
        for (FileSpec f : c.files.values()) {
            file(c, f, mountDirs, pathEnvs);
        }
        if (c.profiles != null) {
            profiles(c);
        }
    }

    private void var(VarSpec v) {
        String n = v.name;
        if (!Names.ENV_NAME.matcher(n).matches()) {
            errors.add(n + ": name must match ^[A-Z][A-Z0-9_]*$");
        }
        if (Names.FEATURE_FLAG.matcher(n).find()) {
            warnings.add(n + ": looks like a feature flag; flags that change without a rollout belong in a flag"
                    + " service (SPEC §10)");
        }
        description(n, v.description);
        if (v.required && v.defaultValue != null) {
            errors.add(n + ": a required variable cannot have a default");
        }
        if (v.secret && v.defaultValue != null) {
            errors.add(n + ": a secret cannot have a default (in code or in an application*.yml file)");
        }
        if (v.secret && v.examples != null && !v.examples.isEmpty()) {
            errors.add(n + ": a secret cannot have examples");
        }
        if (v.pattern != null) {
            String err = Re2.check(v.pattern);
            if (err != null) {
                errors.add(n + ": pattern " + v.pattern + " " + err);
            }
        }
        if (v.minLength != null && v.maxLength != null && v.minLength > v.maxLength) {
            errors.add(n + ": minLength is greater than maxLength");
        }
        if (v.minItems != null && v.maxItems != null && v.minItems > v.maxItems) {
            errors.add(n + ": minItems is greater than maxItems");
        }
        if (v.type == VarType.DURATION) {
            for (Object bound : new Object[] {v.min, v.max}) {
                if (bound != null && !GoDuration.isValid((String) bound)) {
                    errors.add(n + ": bound " + bound + " is not a Go duration");
                }
            }
            if ("go".equals(v.encoding)) {
                // Nothing else to check.
            } else if (v.defaultValue instanceof String d && GoDuration.isValid(d)
                    && GoDuration.parse(d).toNanos() % 1_000_000 != 0) {
                errors.add(n + ": default " + d + " is finer than the millisecond precision of the "
                        + v.encoding + " encoding");
            }
        }
        if ((v.type == VarType.INT || v.type == VarType.FLOAT) && v.min != null && v.max != null
                && new BigDecimal(v.min.toString()).compareTo(new BigDecimal(v.max.toString())) > 0) {
            errors.add(n + ": min is greater than max");
        }
        if (v.type == VarType.ENUM && (v.values == null || v.values.isEmpty())) {
            errors.add(n + ": an enum needs at least one value");
        }
        if (v.type == VarType.LIST && !"string".equals(v.items) && !"int".equals(v.items)) {
            errors.add(n + ": list items must be string or int");
        }
        if (v.defaultValue != null) {
            checkValue(v, v.defaultValue, "default");
        }
    }

    private void checkValue(VarSpec v, Object value, String what) {
        Object typed;
        try {
            typed = toCheckForm(v, value);
        } catch (RuntimeException e) {
            errors.add(v.name + ": " + what + " " + Json.write(value) + " is not a valid " + v.type.id() + ": "
                    + e.getMessage());
            return;
        }
        for (Violation x : VarChecker.check(v, typed)) {
            errors.add(v.name + ": " + what + " " + x.message() + " [" + x.code().id() + "]");
        }
    }

    /**
     * Converts contract data to the form {@link VarChecker} takes.
     *
     * @param v the variable
     * @param value contract data
     * @return the value to check
     */
    public static Object toCheckForm(VarSpec v, Object value) {
        return switch (v.type) {
            case STRING, URL, ENUM -> requireType(value, String.class);
            case INT -> requireType(value, Long.class);
            case FLOAT -> {
                if (!(value instanceof Number)) {
                    throw new IllegalArgumentException("not a number");
                }
                yield value;
            }
            case BOOL -> requireType(value, Boolean.class);
            case DURATION -> GoDuration.parse(requireType(value, String.class));
            case LIST -> {
                List<?> l = requireType(value, List.class);
                for (Object o : l) {
                    if ("int".equals(v.items) ? !(o instanceof Long) : !(o instanceof String)) {
                        throw new IllegalArgumentException("items must be " + v.items + "s");
                    }
                }
                yield l;
            }
            case JSON -> value;
        };
    }

    private static <T> T requireType(Object value, Class<T> type) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("expected a " + type.getSimpleName().toLowerCase(java.util.Locale.ROOT));
        }
        return type.cast(value);
    }

    private void description(String n, String d) {
        if (d == null || d.isBlank()) {
            errors.add(n + ": needs a description (Javadoc or @Description)");
        } else if (d.codePointCount(0, d.length()) < 5) {
            errors.add(n + ": description \"" + d + "\" is shorter than 5 characters");
        }
    }

    private void file(Contract c, FileSpec f, Map<String, String> mountDirs, Map<String, String> pathEnvs) {
        String n = f.name;
        if (!Names.INPUT_NAME.matcher(n).matches()) {
            errors.add(n + ": file input names must be DNS labels of at most 42 characters, starting with a letter");
        }
        description(n, f.description);
        if (f.path == null || !ABS_PATH.matcher(f.path).matches() || f.path.contains("//") || f.path.endsWith("/")
                || f.path.matches(".*(^|/)\\.\\.?(/|$).*")) {
            errors.add(n + ": path " + f.path + " must be absolute and normalised");
        } else {
            String dir = f.type == FileType.TLS ? f.path : parent(f.path);
            if (RESERVED_DIRS.contains(dir)) {
                errors.add(n + ": would be mounted at " + dir + ", which would hide what the image has there;"
                        + " use a dedicated directory");
            }
            String other = mountDirs.putIfAbsent(dir, n);
            if (other != null) {
                errors.add(n + ": shares the mount directory " + dir + " with " + other
                        + "; each input needs its own directory");
            }
        }
        if (f.pathEnv != null) {
            if (!Names.ENV_NAME.matcher(f.pathEnv).matches()) {
                errors.add(n + ": pathEnv " + f.pathEnv + " must match ^[A-Z][A-Z0-9_]*$");
            }
            if (c.vars.containsKey(f.pathEnv)) {
                errors.add(n + ": pathEnv " + f.pathEnv + " is also declared as a variable");
            }
            String other = pathEnvs.putIfAbsent(f.pathEnv, n);
            if (other != null) {
                errors.add(n + ": pathEnv " + f.pathEnv + " is also used by " + other);
            }
        }
        if (f.maxSize != null && f.maxSize <= 0) {
            errors.add(n + ": maxSize must be positive");
        }
        if (f.minRemaining != null && !GoDuration.isValid(f.minRemaining)) {
            errors.add(n + ": minRemaining " + f.minRemaining + " is not a Go duration such as 720h");
        }
        if (f.passwordVar != null) {
            VarSpec pw = c.vars.get(f.passwordVar);
            if (pw == null || !pw.secret) {
                errors.add(n + ": passwordVar " + f.passwordVar + " must name a declared secret variable");
            }
        }
        if (f.pattern != null) {
            String err = Re2.check(f.pattern);
            if (err != null) {
                errors.add(n + ": pattern " + f.pattern + " " + err);
            }
        }
        if (f.minCertificates != null && f.minCertificates < 1) {
            errors.add(n + ": minCertificates must be at least 1");
        }
    }

    private void profiles(Contract c) {
        Profiles p = c.profiles;
        if (!c.vars.containsKey(p.selector)) {
            errors.add("profiles: selector " + p.selector + " must be a declared variable");
        }
        for (Map.Entry<String, Map<String, Object>> e : p.defaults.entrySet()) {
            for (Map.Entry<String, Object> d : e.getValue().entrySet()) {
                VarSpec v = c.vars.get(d.getKey());
                if (v == null) {
                    errors.add("profiles." + e.getKey() + ": " + d.getKey() + " is not a declared variable");
                } else if (v.secret) {
                    errors.add(v.name + ": a secret cannot have a value in a profile file (application-"
                            + e.getKey() + ".yml); it would ship inside the image");
                } else {
                    checkValue(v, d.getValue(), "value in profile " + e.getKey());
                }
            }
        }
    }

    private static String parent(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }
}
