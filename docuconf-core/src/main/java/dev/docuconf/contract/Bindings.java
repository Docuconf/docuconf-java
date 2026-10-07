package dev.docuconf.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How contract inputs map onto the app's Java classes. Written next to the contract by the annotation processor
 * ({@code META-INF/docuconf/contract.json}) and read by the Spring Boot integration at startup. Not part of the
 * CUE contract.
 */
public final class Bindings {

    /**
     * A {@code @ConfigurationProperties} class.
     *
     * @param className the binary class name
     * @param prefix the properties prefix, such as {@code billing}
     */
    public record ClassBinding(String className, String prefix) {
    }

    /**
     * Where one input lives.
     *
     * @param className the {@code @ConfigurationProperties} class that declares it
     * @param javaPath the Java property path within that class, such as {@code db.poolSize}
     * @param configKey the Spring property name, such as {@code billing.db.pool-size}
     * @param javaType the erased Java type, such as {@code java.util.List}
     * @param elementType for collections, the erased element type; otherwise {@code null}
     * @param durationUnit for durations, the {@code @DurationUnit}; otherwise {@code null}
     */
    public record PropertyBinding(String className, String javaPath, String configKey, String javaType,
            String elementType, String durationUnit) {
    }

    /** The classes, in a stable order. */
    public final List<ClassBinding> classes = new ArrayList<>();
    /** Variables, by environment variable name. */
    public final Map<String, PropertyBinding> vars = new TreeMap<>();
    /** File inputs, by input name. */
    public final Map<String, PropertyBinding> files = new TreeMap<>();
    /**
     * The {@code application*.yml} files the contract was exported from, by name relative to the resources root,
     * with their SHA-256 ({@link SpringFileHashes}); {@code null} in contracts written before this was recorded.
     */
    public Map<String, String> springFiles;
}
