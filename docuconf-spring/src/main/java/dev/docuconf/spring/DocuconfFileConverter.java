package dev.docuconf.spring;

import java.util.Set;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.GenericConverter;

/**
 * Hands checked file inputs to Spring's binder. docuconf puts a {@link FileInput} at each file property's key;
 * this converter, registered with {@code @ConfigurationPropertiesBinding}, turns it into the loaded value. The
 * value is not a string, so placeholder resolution and string conversions never touch file content, and it works
 * for JavaBeans and records alike.
 */
public final class DocuconfFileConverter implements GenericConverter {

    private final DocuconfFiles files;

    DocuconfFileConverter(DocuconfFiles files) {
        this.files = files;
    }

    @Override
    public Set<ConvertiblePair> getConvertibleTypes() {
        return Set.of(new ConvertiblePair(FileInput.class, Object.class));
    }

    @Override
    public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
        if (source instanceof FileInput input) {
            return files.get(input.name(), Object.class).orElse(null);
        }
        return null;
    }

    /**
     * The property value docuconf binds a file input from.
     *
     * @param name the input name
     */
    public record FileInput(String name) {
        @Override
        public String toString() {
            return "<docuconf file input " + name + ">";
        }
    }
}
