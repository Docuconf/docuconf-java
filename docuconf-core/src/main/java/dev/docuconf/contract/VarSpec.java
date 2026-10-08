package dev.docuconf.contract;

import java.util.List;
import java.util.Map;

/**
 * One environment variable in a contract (SPEC §4.2). Fields that do not apply to the type are {@code null}.
 *
 * <p>Values are held as plain data: {@code String}, {@code Long} (int), {@code java.math.BigDecimal} (float),
 * {@code Boolean}, {@code List} and {@code Map}. Durations are Go-syntax strings.
 */
public final class VarSpec {
    /** The environment variable name. */
    public String name;
    /** The type. */
    public VarType type;
    /** The description, at least five characters. */
    public String description;
    /**
     * Longer documentation for generated docs, in CommonMark (SPEC section 4.2): the Javadoc after its first
     * sentence. Not blank, at most 4000 characters (Unicode code points); never read at runtime. {@code null} when
     * the input has none.
     */
    public String details;
    /** Whether the platform must supply it. */
    public boolean required;
    /** Whether it must come from a Kubernetes Secret. */
    public boolean secret;
    /** Docs group. */
    public String group;
    /** Example values. */
    public List<String> examples;
    /** The app's own configuration key, such as {@code billing.database-url}. */
    public String configKey;
    /** Deprecation, if any. */
    public Deprecation deprecated;
    /** The default value, or {@code null}. */
    public Object defaultValue;
    /** String: least length. */
    public Integer minLength;
    /**
     * String, url or json: greatest length, in characters (Unicode code points). For json, the length of the value
     * as received, or of its compact JSON when it has no wire form.
     */
    public Integer maxLength;
    /** String: RE2 pattern, matched anywhere. */
    public String pattern;
    /** Int, float or duration lower bound ({@code Long}, {@code BigDecimal} or Go duration string). */
    public Object min;
    /** Int, float or duration upper bound. */
    public Object max;
    /** Duration or list wire encoding. */
    public String encoding;
    /** URL: allowed schemes. */
    public List<String> schemes;
    /** Enum: allowed values. */
    public List<String> values;
    /** List: item type, {@code string} or {@code int}. */
    public String items;
    /** List with csv encoding: the separator. */
    public String separator;
    /** List: least number of items. */
    public Integer minItems;
    /** List: greatest number of items. */
    public Integer maxItems;
    /** Int list: least value of each item. */
    public Long itemMin;
    /** Int list: greatest value of each item. */
    public Long itemMax;
    /** String list: least length of each item, in characters (Unicode code points). */
    public Integer itemMinLength;
    /** String list: greatest length of each item, in characters (Unicode code points). */
    public Integer itemMaxLength;
    /** JSON: the JSON Schema of the value. */
    public Map<String, Object> schema;

    /** Creates an empty spec. */
    public VarSpec() {
    }

    /**
     * Creates a spec with a name, type and description.
     *
     * @param name the variable name
     * @param type the type
     * @param description the description
     */
    public VarSpec(String name, VarType type, String description) {
        this.name = name;
        this.type = type;
        this.description = description;
    }
}
