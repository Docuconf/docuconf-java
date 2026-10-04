package dev.docuconf.spring;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.docuconf.check.Code;
import dev.docuconf.check.Violation;
import dev.docuconf.contract.FileSpec;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Reads a config file input with Jackson into the app's type, then validates it with Bean Validation. A file that
 * does not parse is {@code file_malformed}; one that parses but does not fit the type is {@code schema_mismatch}.
 */
final class ConfigFileReader {

    private ConfigFileReader() {
    }

    static ObjectMapper mapper(String format) {
        ObjectMapper m = switch (format) {
            case "yaml" -> new YAMLMapper();
            case "toml" -> new TomlMapper();
            default -> new ObjectMapper();
        };
        m.findAndRegisterModules();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        m.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        return m;
    }

    /** Returns the bound object, or {@code null} after adding violations. */
    static Object read(FileSpec f, Path path, byte[] bytes, Class<?> type, Validator validator,
            List<Violation> out) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            bytes = Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        ObjectMapper mapper = mapper(f.format);
        JsonNode tree;
        try {
            tree = mapper.readTree(bytes);
        } catch (JsonProcessingException e) {
            out.add(new Violation(Code.FILE_MALFORMED, f.name, path + " is not valid " + f.format.toUpperCase()
                    + where(e.getLocation()) + (f.secret ? "" : ": " + e.getOriginalMessage())));
            return null;
        } catch (IOException e) {
            out.add(new Violation(Code.FILE_UNREADABLE, f.name, path + " could not be read: " + e.getMessage()));
            return null;
        }
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            out.add(new Violation(Code.FILE_MALFORMED, f.name, path + " is empty"));
            return null;
        }
        Object value;
        try {
            value = mapper.treeToValue(tree, type);
        } catch (JsonMappingException e) {
            String at = e.getPathReference() == null ? "" : jsonPath(e);
            out.add(new Violation(Code.SCHEMA_MISMATCH, f.name, path + ": " + (at.isEmpty() ? "" : at + ": ")
                    + (f.secret ? "does not fit " + type.getSimpleName() : e.getOriginalMessage())));
            return null;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            out.add(new Violation(Code.SCHEMA_MISMATCH, f.name, path + ": does not fit " + type.getSimpleName()));
            return null;
        }
        if (validator != null) {
            List<GraphValidator.Problem> problems = GraphValidator.validate(validator, value);
            for (GraphValidator.Problem p : problems) {
                String message = f.secret ? "fails @" + p.constraint() : p.message();
                out.add(new Violation(Code.SCHEMA_MISMATCH, f.name, path + ": " + p.path() + ": " + message));
            }
            if (!problems.isEmpty()) {
                return null;
            }
        }
        return value;
    }

    private static String jsonPath(JsonMappingException e) {
        StringBuilder b = new StringBuilder();
        for (JsonMappingException.Reference r : e.getPath()) {
            if (r.getFieldName() != null) {
                if (b.length() > 0) {
                    b.append('.');
                }
                b.append(r.getFieldName());
            } else if (r.getIndex() >= 0) {
                b.append('[').append(r.getIndex()).append(']');
            }
        }
        return b.toString();
    }

    private static String where(JsonLocation l) {
        return l == null || l.getLineNr() < 0 ? "" : " (line " + l.getLineNr() + ", column " + l.getColumnNr() + ")";
    }
}
