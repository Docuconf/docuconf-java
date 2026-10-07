package dev.docuconf.spring;

import java.lang.reflect.Type;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.MapperBuilder;

/** {@link JsonMapper} on Jackson 3 ({@code tools.jackson}), which Spring Boot 4 uses. */
final class Jackson3Mapper implements JsonMapper {

    private final ObjectMapper mapper;

    Jackson3Mapper(String format) {
        MapperBuilder<?, ?> b = switch (format) {
            case "yaml" -> tools.jackson.dataformat.yaml.YAMLMapper.builder();
            case "toml" -> tools.jackson.dataformat.toml.TomlMapper.builder();
            default -> tools.jackson.databind.json.JsonMapper.builder();
        };
        this.mapper = b.findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                // Jackson 3 fails on a missing primitive by default; Jackson 2 and Spring's binder leave it at zero,
                // and Bean Validation then reports the constraint it breaks.
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }

    @Override
    public Object readTree(byte[] content) throws Malformed {
        try {
            JsonNode tree = mapper.readTree(content);
            return tree == null || tree.isMissingNode() || tree.isNull() ? null : tree;
        } catch (JacksonException e) {
            TokenStreamLocation l = e.getLocation();
            throw new Malformed(e.getOriginalMessage(), l == null ? -1 : l.getLineNr(), l == null ? -1 : l.getColumnNr());
        }
    }

    @Override
    public String writeCompact(Object value) {
        try {
            return mapper.rebuild()
                    .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
                    .build()
                    .writeValueAsString(value);
        } catch (JacksonException e) {
            return null;
        }
    }

    @Override
    public Object convert(Object tree, Type type) throws Mismatch {
        try {
            return mapper.treeToValue((JsonNode) tree, mapper.constructType(type));
        } catch (DatabindException e) {
            StringBuilder path = new StringBuilder();
            for (JacksonException.Reference r : e.getPath()) {
                if (r.getPropertyName() != null) {
                    path.append(path.length() > 0 ? "." : "").append(r.getPropertyName());
                } else if (r.getIndex() >= 0) {
                    path.append('[').append(r.getIndex()).append(']');
                }
            }
            throw new Mismatch(e.getOriginalMessage(), path.toString());
        } catch (JacksonException | IllegalArgumentException e) {
            throw new Mismatch(null, "");
        }
    }
}
