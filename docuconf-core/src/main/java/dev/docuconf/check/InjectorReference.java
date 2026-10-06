package dev.docuconf.check;

import dev.docuconf.contract.VarSpec;
import java.util.List;

/**
 * Recognises a secret that still holds an injector's reference instead of the value it stands for (SPEC §4.5.1,
 * §11.2): {@code vault:secret/data/db#url} for Bank-Vaults, {@code op://vault/item/field} for 1Password's
 * {@code op run}, {@code ref+vault://...} for vals. docuconf never resolves references; seeing one at startup means
 * the injector that should have replaced it did not run.
 */
public final class InjectorReference {

    /** The reference prefixes, as the spec lists them. */
    public static final List<String> SCHEMES = List.of("vault:", "op://", "ref+");

    private InjectorReference() {
    }

    /**
     * The reference scheme a value starts with.
     *
     * @param value a raw value, possibly {@code null}
     * @return such as {@code vault:}, or {@code null} when it is not a reference
     */
    public static String scheme(String value) {
        if (value == null) {
            return null;
        }
        for (String s : SCHEMES) {
            if (value.startsWith(s)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Checks a secret variable's raw value. Non-secret variables are not checked: their constraints catch a stray
     * reference, and the value may legitimately look like one.
     *
     * @param v the variable
     * @param raw its raw value, possibly {@code null}
     * @return an {@code invalid_type} violation naming the scheme but never the value, or {@code null}
     */
    public static Violation check(VarSpec v, String raw) {
        String scheme = v.secret ? scheme(raw) : null;
        if (scheme == null) {
            return null;
        }
        return new Violation(Code.INVALID_TYPE, v.name, "holds an unresolved " + scheme
                + " reference; the injector that should resolve it did not run");
    }
}
