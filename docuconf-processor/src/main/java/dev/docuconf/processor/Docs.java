package dev.docuconf.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;

/**
 * Descriptions and details from Javadoc (SPEC section 14.7): the first sentence is the description, as Spring's
 * configuration metadata takes it, and the rest of the main description is the details, converted to CommonMark.
 */
final class Docs {

    /** An input's documentation: its description (plain text) and details (CommonMark, or {@code null}). */
    record Doc(String description, String details) {
    }

    private static final Pattern INLINE = Pattern.compile("\\{@(?:code|literal|link|linkplain|value)\\s+([^}]*)}");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private Docs() {
    }

    /** The main description of an element's Javadoc, as one line, or {@code null}. */
    static String of(Elements elements, Element e) {
        String main = main(elements, e);
        return main == null ? null : clean(main);
    }

    /** The main description of an element's Javadoc, as written (lines kept), or {@code null}. */
    static String main(Elements elements, Element e) {
        if (e == null) {
            return null;
        }
        String doc = elements.getDocComment(e);
        if (doc == null) {
            return null;
        }
        StringBuilder main = new StringBuilder();
        boolean pre = false;
        for (String line : doc.split("\n", -1)) {
            if (!pre && line.trim().startsWith("@")) {
                break;
            }
            pre = pre ? !line.contains("</pre>") : line.contains("<pre>") && !line.contains("</pre>");
            main.append(line).append('\n');
        }
        return main.toString().isBlank() ? null : main.toString();
    }

    /** The text of an {@code @param name} tag in an element's Javadoc, or {@code null}. */
    static String param(Elements elements, Element e, String name) {
        String text = rawParam(elements, e, name);
        return text == null ? null : clean(text);
    }

    /** The text of an {@code @param name} tag in an element's Javadoc, as written (lines kept), or {@code null}. */
    static String rawParam(Elements elements, Element e, String name) {
        if (e == null) {
            return null;
        }
        String doc = elements.getDocComment(e);
        if (doc == null) {
            return null;
        }
        StringBuilder text = null;
        for (String line : doc.split("\n")) {
            String t = line.trim();
            if (t.startsWith("@")) {
                if (text != null) {
                    break;
                }
                String[] parts = t.split("\\s+", 3);
                if (parts[0].equals("@param") && parts.length >= 2 && parts[1].equals(name)) {
                    text = new StringBuilder(parts.length == 3 ? parts[2] : "");
                }
            } else if (text != null) {
                text.append('\n').append(line);
            }
        }
        return text == null || text.toString().isBlank() ? null : text.toString();
    }

    static String clean(String s) {
        Matcher m = INLINE.matcher(s);
        String out = m.replaceAll(r -> Matcher.quoteReplacement(r.group(1).trim()));
        out = TAG.matcher(out).replaceAll("");
        out = out.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        out = out.replaceAll("\\s+", " ").trim();
        return out.isEmpty() ? null : out;
    }

    private static final Pattern BLOCK_START = Pattern.compile("(?i)<(p|ul|ol|pre|table|blockquote|h[1-6]|dl)\\b");

    /**
     * Splits Javadoc text (a main description or an {@code @param} text) into its first sentence, the description,
     * and the rest, the details. The first sentence ends, as for the javadoc tool, at the first period followed by
     * white space outside an inline tag, or at the first paragraph or block element.
     */
    static Doc split(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Doc(null, null);
        }
        int end = raw.length();
        int depth = 0;
        Matcher block = BLOCK_START.matcher(raw);
        int blockAt = block.find() ? block.start() : raw.length();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '{' && i + 1 < raw.length() && raw.charAt(i + 1) == '@') {
                depth++;
            } else if (ch == '}' && depth > 0) {
                depth--;
            } else if (depth == 0 && ch == '.' && (i + 1 == raw.length() || Character.isWhitespace(raw.charAt(i + 1)))) {
                end = i + 1;
                break;
            } else if (depth == 0 && ch == '\n' && raw.substring(i + 1).matches("(?s)[ \t]*\n.*")) {
                end = i;
                break;
            }
        }
        if (blockAt < end && !raw.substring(0, blockAt).isBlank()) {
            end = blockAt;
        }
        String description = clean(raw.substring(0, end));
        String details = markdown(raw.substring(end));
        return new Doc(description, details.isEmpty() ? null : details);
    }

    private static final Pattern INLINE_TAG = Pattern.compile("\\{@(\\w+)\\s*((?:[^{}]|\\{[^{}]*})*)}");
    private static final Pattern LINK = Pattern.compile("(?is)<a\\s+href\\s*=\\s*\"([^\"]*)\"[^>]*>(.*?)</a>");

    /**
     * Javadoc HTML as CommonMark: {@code <p>} starts a paragraph, {@code <ul>}/{@code <ol>} with {@code <li>} are
     * lists, {@code <pre>} is a fenced code block, {@code {@code x}}, {@code {@link X}}, {@code {@literal x}} and
     * {@code <code>} are code spans, {@code <a href>} a link, {@code <b>}/{@code <strong>} and {@code <i>}/{@code <em>}
     * emphasis. Other tags are dropped, keeping their text; entities are decoded.
     */
    static String markdown(String html) {
        List<String> blocks = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        Runnable flush = () -> {
            String t = inline(para.toString()).replaceAll("[ \\t]*\\n[ \\t]*", "\n").replaceAll("[ \\t]+", " ").trim()
                    .replaceAll("\\s*\\n\\s*", " ")
                    // <br> is a hard line break.
                    .replaceAll(" ?\u0001 ?", "\\\\\n");
            if (!t.isEmpty()) {
                blocks.add(t);
            }
            para.setLength(0);
        };
        Matcher m = Pattern.compile("(?is)<pre>(.*?)</pre>|<(ul|ol)>(.*?)</\\2>|</?p\\s*/?>|\\n[ \\t]*\\n").matcher(html);
        int at = 0;
        while (m.find()) {
            para.append(html, at, m.start());
            flush.run();
            if (m.group(1) != null) {
                blocks.add(pre(m.group(1)));
            } else if (m.group(2) != null) {
                blocks.add(list(m.group(3), m.group(2).equalsIgnoreCase("ol")));
            }
            at = m.end();
        }
        para.append(html.substring(at));
        flush.run();
        return String.join("\n\n", blocks).trim();
    }

    private static String list(String body, boolean numbered) {
        List<String> items = new ArrayList<>();
        int n = 1;
        for (String item : body.split("(?i)<li>")) {
            String text = item.replaceAll("(?i)</li>", "");
            String md = markdown(text).replace("\n", "\n   ");
            if (!md.isBlank()) {
                items.add((numbered ? (n++) + ". " : "- ") + md);
            }
        }
        return String.join("\n", items);
    }

    /** A {@code <pre>} block, usually {@code <pre>{@code ...}</pre>}, as a fenced code block. */
    private static String pre(String body) {
        String code = body;
        Matcher m = Pattern.compile("(?s)^\\s*\\{@code\\s?(.*)}\\s*$").matcher(code);
        if (m.matches()) {
            code = m.group(1);
        } else {
            code = entities(code.replaceAll("<[^>]+>", ""));
        }
        List<String> lines = new ArrayList<>(List.of(code.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(0).isBlank()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        int indent = lines.stream().filter(l -> !l.isBlank()).mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        StringBuilder out = new StringBuilder("```\n");
        for (String l : lines) {
            out.append(l.length() >= indent ? l.substring(indent).stripTrailing() : l.strip()).append('\n');
        }
        return out.append("```").toString();
    }

    private static String inline(String s) {
        Matcher m = INLINE_TAG.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String tag = m.group(1);
            String body = m.group(2).trim();
            String rep;
            switch (tag) {
                case "code", "literal" -> rep = code(body);
                case "link", "linkplain" -> {
                    String target = body.split("\\s+", 2)[0];
                    rep = code(target.startsWith("#") ? target.substring(1) : target.replace('#', '.'));
                }
                case "value" -> rep = body.isEmpty() ? "" : code(body.startsWith("#") ? body.substring(1) : body.replace('#', '.'));
                case "inheritDoc" -> rep = "";
                default -> rep = body;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        String t = LINK.matcher(out.toString()).replaceAll(r -> Matcher.quoteReplacement("[" + r.group(2).trim() + "](" + r.group(1) + ")"));
        t = t.replaceAll("(?is)<code>(.*?)</code>", "`$1`");
        t = t.replaceAll("(?is)<(b|strong)>(.*?)</\\1>", "**$2**");
        t = t.replaceAll("(?is)<(i|em)>(.*?)</\\1>", "*$2*");
        t = t.replaceAll("(?i)<br\\s*/?>", "\u0001");
        t = t.replaceAll("<[^>]+>", "");
        return entities(t);
    }

    private static String entities(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#64;", "@").replace("&amp;", "&");
    }

    private static String code(String s) {
        int longest = 0;
        int run = 0;
        for (char c : s.toCharArray()) {
            run = c == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1);
        return longest > 0 ? fence + " " + s + " " + fence : fence + s + fence;
    }
}
