package dev.docuconf.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.docuconf.Json;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;

/**
 * Binds a {@link Json @Json} property from one variable holding a JSON document, with Jackson. Spring's binder
 * cannot turn a string into an object by itself.
 */
public final class DocuconfJsonConverter implements ConditionalGenericConverter {

    private final ObjectMapper mapper = ConfigFileReader.mapper("json");

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
        try {
            return mapper.readValue(source.toString(), mapper.constructType(targetType.getResolvableType().getType()));
        } catch (IOException e) {
            // The startup check has already reported this with a redacted message; keep values out of this one.
            throw new ConversionFailedException(sourceType, targetType, null,
                    new IllegalArgumentException("not JSON that fits " + targetType.getType().getSimpleName()));
        }
    }
}
