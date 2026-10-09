package dev.docuconf.contract;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks that a pattern means the same in Java and RE2 (SPEC §4.3), and adapts it for matching in Java.
 *
 * <p>Contract patterns are RE2, matched by CUE before deploy and by {@link java.util.regex} at startup. Java
 * features RE2 lacks (lookaround, backreferences, possessive and atomic groups, class intersections, {@code \h},
 * {@code \R}, Unicode escapes and others) are rejected at compile time.
 */
public final class Re2 {

    private static final Set<String> POSIX_P = Set.of("Lower", "Upper", "ASCII", "Alpha", "Digit", "Alnum", "Punct",
            "Graph", "Print", "Blank", "Cntrl", "XDigit", "Space");

    private Re2() {
    }

    /**
     * Checks a pattern.
     *
     * @param pattern the pattern
     * @return {@code null} when it is valid in both dialects, otherwise why not
     */
    public static String check(String pattern) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "is not a valid regular expression: " + e.getDescription();
        }
        boolean inClass = false;
        int n = pattern.length();
        for (int i = 0; i < n; i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    return "ends with a backslash";
                }
                char e = pattern.charAt(i + 1);
                if (e == 'Q') {
                    int end = pattern.indexOf("\\E", i + 2);
                    i = end < 0 ? n : end + 1;
                    continue;
                }
                if (!inClass && e >= '1' && e <= '9') {
                    return "uses a backreference (\\" + e + "), which RE2 does not support";
                }
                switch (e) {
                    case 'k':
                        return "uses a named backreference (\\k), which RE2 does not support";
                    case 'h', 'H', 'R', 'X', 'G', 'Z', 'e', 'c', 'v', 'V', 'u', 'N':
                        return "uses \\" + e + ", which RE2 does not support or reads differently";
                    case 'p', 'P': {
                        if (i + 2 < n && pattern.charAt(i + 2) == '{') {
                            int close = pattern.indexOf('}', i + 3);
                            String name = close < 0 ? "" : pattern.substring(i + 3, close);
                            if (name.startsWith("Is") || name.startsWith("In") || name.startsWith("java")
                                    || name.contains("=") || POSIX_P.contains(name)) {
                                return "uses \\" + e + "{" + name + "}, a Java-only class; use [[:alpha:]]-style"
                                        + " classes or a Unicode category such as \\p{L}";
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }
                i++;
                continue;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                } else if (c == '&' && i + 1 < n && pattern.charAt(i + 1) == '&') {
                    return "uses a character class intersection (&&), which RE2 does not support";
                } else if (c == '[' && !(i + 1 < n && pattern.charAt(i + 1) == ':')) {
                    return "nests a character class, which RE2 reads as a literal '['";
                } else if (c == '[') {
                    int close = pattern.indexOf(":]", i + 2);
                    if (close > 0) {
                        i = close + 1;
                    }
                }
                continue;
            }
            switch (c) {
                case '[':
                    inClass = true;
                    if (i + 1 < n && pattern.charAt(i + 1) == '^') {
                        i++;
                    }
                    if (i + 1 < n && pattern.charAt(i + 1) == ']') {
                        i++;
                    }
                    break;
                case '(':
                    if (i + 1 < n && pattern.charAt(i + 1) == '?') {
                        String err = group(pattern, i + 2);
                        if (err != null) {
                            return err;
                        }
                    }
                    break;
                case '*', '+', '?', '}':
                    if (i + 1 < n && pattern.charAt(i + 1) == '+') {
                        return "uses a possessive quantifier (" + c + "+), which RE2 does not support";
                    }
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    private static String group(String p, int i) {
        if (i >= p.length()) {
            return null;
        }
        char c = p.charAt(i);
        if (c == '=' || c == '!') {
            return "uses lookahead, which RE2 does not support";
        }
        if (c == '<' && i + 1 < p.length() && (p.charAt(i + 1) == '=' || p.charAt(i + 1) == '!')) {
            return "uses lookbehind, which RE2 does not support";
        }
        if (c == '>') {
            return "uses an atomic group, which RE2 does not support";
        }
        if (c == '<' || c == ':' || c == 'P') {
            return null;
        }
        for (int k = i; k < p.length(); k++) {
            char f = p.charAt(k);
            if (f == ')' || f == ':') {
                return null;
            }
            if ("ims-".indexOf(f) < 0) {
                return "uses the inline flag (?" + f + "), which RE2 does not support or reads differently";
            }
        }
        return null;
    }

    /**
     * Compiles a contract pattern for Java matching with {@link java.util.regex.Matcher#find()} so it agrees with
     * RE2: {@code .}, {@code ^} and {@code $} only treat {@code \n} as a line end, and {@code $} outside multi-line
     * mode only matches at the very end of the input (Java's also matches before a final newline).
     *
     * @param pattern a pattern accepted by {@link #check(String)}
     * @return the compiled pattern
     */
    public static Pattern compile(String pattern) {
        return Pattern.compile(strictDollar(pattern), Pattern.UNIX_LINES);
    }

    static String strictDollar(String p) {
        if (p.contains("(?m") || p.matches(".*\\(\\?[is]*m.*")) {
            return p;
        }
        StringBuilder b = new StringBuilder();
        boolean inClass = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '\\' && i + 1 < p.length()) {
                if (p.charAt(i + 1) == 'Q') {
                    int end = p.indexOf("\\E", i + 2);
                    int stop = end < 0 ? p.length() : end + 2;
                    b.append(p, i, stop);
                    i = stop - 1;
                    continue;
                }
                b.append(c).append(p.charAt(i + 1));
                i++;
                continue;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                }
                b.append(c);
                continue;
            }
            if (c == '[') {
                inClass = true;
                b.append(c);
                if (i + 1 < p.length() && p.charAt(i + 1) == '^') {
                    b.append('^');
                    i++;
                }
                if (i + 1 < p.length() && p.charAt(i + 1) == ']') {
                    b.append(']');
                    i++;
                }
                continue;
            }
            b.append(c == '$' ? "\\z" : String.valueOf(c));
        }
        return b.toString();
    }

    /**
     * Anchors a whole-value pattern (Bean Validation {@code @Pattern}) for the contract, where patterns match
     * anywhere: {@code ^(?:p)$}, with Java flags as an inline group. A pattern that already starts with {@code ^}
     * and ends with {@code $}, with no top-level {@code |}, matches the whole value either way and is kept as it
     * is: {@code ^[a-z]+$} stays {@code ^[a-z]+$}.
     *
     * @param pattern the {@code @Pattern} regexp
     * @param flags inline flags such as {@code i}, or empty
     * @return the anchored pattern
     */
    public static String anchor(String pattern, String flags) {
        if (flags.isEmpty() && anchoredAtBothEnds(pattern)) {
            return pattern;
        }
        return "^(?" + flags + ":" + pattern + ")$";
    }

    /** Whether a pattern is {@code ^...$} with no alternation outside groups. */
    private static boolean anchoredAtBothEnds(String p) {
        if (p.length() < 2 || p.charAt(0) != '^' || p.charAt(p.length() - 1) != '$') {
            return false;
        }
        int depth = 0;
        boolean inClass = false;
        for (int i = 1; i < p.length() - 1; i++) {
            char c = p.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= p.length() - 1) {
                    return false; // the final $ is escaped
                }
                continue;
            }
            if (inClass) {
                inClass = c != ']';
            } else if (c == '[') {
                inClass = true;
                if (i + 1 < p.length() && p.charAt(i + 1) == ']') {
                    i++;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '|' && depth == 0) {
                return false;
            }
        }
        return depth == 0 && !inClass;
    }
}
