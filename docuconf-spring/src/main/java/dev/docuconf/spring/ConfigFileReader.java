package dev.docuconf.spring;

import dev.docuconf.check.Code;
import dev.docuconf.check.Violation;
import dev.docuconf.contract.FileSpec;
import jakarta.validation.Validator;
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

    /** Returns the bound object, or {@code null} after adding violations. */
    static Object read(FileSpec f, Path path, byte[] bytes, Class<?> type, Validator validator,
            List<Violation> out) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            bytes = Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        JsonMapper mapper = JsonMapper.forFormat(f.format);
        Object tree;
        try {
            tree = mapper.readTree(bytes);
        } catch (JsonMapper.Malformed e) {
            out.add(new Violation(Code.FILE_MALFORMED, f.name, path + " is not valid " + f.format.toUpperCase()
                    + (e.line < 0 ? "" : " (line " + e.line + ", column " + e.column + ")")
                    + (f.secret || e.getMessage() == null ? "" : ": " + e.getMessage())));
            return null;
        }
        if (tree == null) {
            out.add(new Violation(Code.FILE_MALFORMED, f.name, path + " is empty"));
            return null;
        }
        Object value;
        try {
            value = mapper.convert(tree, type);
        } catch (JsonMapper.Mismatch e) {
            String at = e.path == null || e.path.isEmpty() ? "" : e.path + ": ";
            out.add(new Violation(Code.SCHEMA_MISMATCH, f.name, path + ": " + at
                    + (f.secret || e.getMessage() == null ? "does not fit " + type.getSimpleName() : e.getMessage())));
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
}
