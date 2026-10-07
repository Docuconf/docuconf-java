package dev.docuconf.spring;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validation;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.util.Locale;

/**
 * Bean Validation messages for secret values. The constraint's message is interpolated with the value replaced, and
 * if the value still shows up (a custom validator that wrote it into its own message), only the constraint is named.
 */
final class SecretMessages {

    private static final String REDACTED = "[redacted]";

    private SecretMessages() {
    }

    static String redacted(ConstraintViolation<?> cv) {
        String annotation = cv.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        Object value = cv.getInvalidValue();
        String secret = value == null ? null : String.valueOf(value);
        String message;
        try {
            MessageInterpolator interpolator = Validation.byDefaultProvider().configure()
                    .getDefaultMessageInterpolator();
            message = interpolator.interpolate(cv.getMessageTemplate(), new Context(cv.getConstraintDescriptor()),
                    Locale.getDefault());
        } catch (RuntimeException | LinkageError e) {
            return "fails @" + annotation;
        }
        if (message == null || message.isBlank() || (secret != null && !secret.isEmpty() && message.contains(secret))) {
            return "fails @" + annotation;
        }
        return message;
    }

    private record Context(ConstraintDescriptor<?> descriptor) implements MessageInterpolator.Context {

        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return descriptor;
        }

        @Override
        public Object getValidatedValue() {
            return REDACTED;
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new jakarta.validation.ValidationException("not supported");
        }
    }
}
