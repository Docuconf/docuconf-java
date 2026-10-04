package dev.docuconf.processor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;

/** Descriptions from Javadoc. */
final class Docs {

    private static final Pattern INLINE = Pattern.compile("\\{@(?:code|literal|link|linkplain|value)\\s+([^}]*)}");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private Docs() {
    }

    /** The main description of an element's Javadoc, as one line, or {@code null}. */
    static String of(Elements elements, Element e) {
        if (e == null) {
            return null;
        }
        String doc = elements.getDocComment(e);
        if (doc == null) {
            return null;
        }
        StringBuilder main = new StringBuilder();
        for (String line : doc.split("\n")) {
            if (line.trim().startsWith("@")) {
                break;
            }
            main.append(line).append(' ');
        }
        return clean(main.toString());
    }

    /** The text of an {@code @param name} tag in an element's Javadoc, or {@code null}. */
    static String param(Elements elements, Element e, String name) {
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
                text.append(' ').append(t);
            }
        }
        return text == null ? null : clean(text.toString());
    }

    static String clean(String s) {
        Matcher m = INLINE.matcher(s);
        String out = m.replaceAll(r -> Matcher.quoteReplacement(r.group(1).trim()));
        out = TAG.matcher(out).replaceAll("");
        out = out.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        out = out.replaceAll("\\s+", " ").trim();
        return out.isEmpty() ? null : out;
    }
}
