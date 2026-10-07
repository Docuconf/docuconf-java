package dev.docuconf.spring;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Type;

/** {@link JsonMapper} on Jackson 2 ({@code com.fasterxml.jackson}). */
final class Jackson2Mapper implements JsonMapper {

    private final ObjectMapper mapper;

    Jackson2Mapper(String format) {
        ObjectMapper m = switch (format) {
            case "yaml" -> new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
            case "toml" -> new com.fasterxml.jackson.dataformat.toml.TomlMapper();
            default -> new ObjectMapper();
        };
        m.findAndRegisterModules();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        m.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        this.mapper = m;
    }

    @Override
    public Object readTree(byte[] content) throws Malformed {
        try {
            JsonNode tree = mapper.readTree(content);
            return tree == null || tree.isMissingNode() || tree.isNull() ? null : tree;
        } catch (JsonProcessingException e) {
            JsonLocation l = e.getLocation();
            throw new Malformed(e.getOriginalMessage(), l == null ? -1 : l.getLineNr(), l == null ? -1 : l.getColumnNr());
        } catch (IOException e) {
            throw new Malformed(e.getMessage(), -1, -1);
        }
    }

    @Override
    public Object convert(Object tree, Type type) throws Mismatch {
        try {
            return mapper.treeToValue((JsonNode) tree, mapper.constructType(type));
        } catch (JsonMappingException e) {
            StringBuilder path = new StringBuilder();
            for (JsonMappingException.Reference r : e.getPath()) {
                if (r.getFieldName() != null) {
                    path.append(path.length() > 0 ? "." : "").append(r.getFieldName());
                } else if (r.getIndex() >= 0) {
                    path.append('[').append(r.getIndex()).append(']');
                }
            }
            throw new Mismatch(e.getOriginalMessage(), path.toString());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new Mismatch(null, "");
        }
    }
}
