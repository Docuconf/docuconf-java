package dev.docuconf.spring;

import dev.docuconf.KeySet;
import dev.docuconf.check.WireFormat;
import java.util.Set;
import org.springframework.boot.convert.Delimiter;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.GenericConverter;

/**
 * Binds a {@link KeySet} from one variable holding its keys, split on {@code ,} (or the property's
 * {@link Delimiter @Delimiter}) exactly as SPEC §5 splits a list: never trimmed, so an empty key stays and the
 * startup check reports it. Spring's own list conversion would trim each key and drop empty ones.
 */
public final class DocuconfKeySetConverter implements GenericConverter {

    /** Creates the converter. */
    public DocuconfKeySetConverter() {
    }

    @Override
    public Set<ConvertiblePair> getConvertibleTypes() {
        return Set.of(new ConvertiblePair(String.class, KeySet.class));
    }

    @Override
    public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
        if (source == null || source.toString().isEmpty()) {
            return null;
        }
        Delimiter delimiter = targetType.getAnnotation(Delimiter.class);
        return KeySet.of(WireFormat.splitCsv(source.toString(), delimiter == null ? "," : delimiter.value()));
    }
}
