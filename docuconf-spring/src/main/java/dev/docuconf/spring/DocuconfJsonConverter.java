package dev.docuconf.spring;

import dev.docuconf.Json;
import java.util.Set;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;

/**
 * Binds a {@link Json @Json} property from one variable holding a JSON document, with Jackson. Spring's binder
 * cannot turn a string into an object by itself.
 */
public final class DocuconfJsonConverter implements ConditionalGenericConverter {

    private volatile JsonMapper mapper;

    /** Creates the converter. */
    public DocuconfJsonConverter() {
    }

    @Override
    public Set<ConvertiblePair> getConvertibleTypes() {
        return Set.of(new ConvertiblePair(String.class, Object.class));
    }

    @Override
    public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
        return targetType.hasAnnotation(Json.class);
    }

    @Override
    public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
        if (source == null || source.toString().isEmpty()) {
            return null;
        }
        if (mapper == null) {
            mapper = JsonMapper.forFormat("json");
        }
        try {
            return mapper.read(source.toString(), targetType.getResolvableType().getType());
        } catch (JsonMapper.Malformed | JsonMapper.Mismatch e) {
            // The startup check has already reported this with a redacted message; keep values out of this one.
            throw new ConversionFailedException(sourceType, targetType, null,
                    new IllegalArgumentException("not JSON that fits " + targetType.getType().getSimpleName()));
        }
    }
}
