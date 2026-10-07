package dev.docuconf.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Payload;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Email;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Messages of failed constraints on secrets never contain the value, whoever wrote the validator. */
class SecretMessagesTest {

    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = LeakyValidator.class)
    @interface Leaky {
        String message() default "is not a token: ${validatedValue}";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** A user-written validator that puts the value into its own message. */
    public static class LeakyValidator implements ConstraintValidator<Leaky, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value.startsWith("tok_")) {
                return true;
            }
            if (value.startsWith("x")) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("bad token " + value).addConstraintViolation();
            }
            return false;
        }
    }

    static class Holder {
        @Leaky
        String token;
        @Email
        String email;

        Holder(String token, String email) {
            this.token = token;
            this.email = email;
        }
    }

    private static String message(Holder h, String property) {
        Set<ConstraintViolation<Holder>> found = Validation.buildDefaultValidatorFactory().getValidator().validate(h);
        return SecretMessages.redacted(found.stream()
                .filter(cv -> cv.getPropertyPath().toString().equals(property)).findFirst().orElseThrow());
    }

    @Test
    void theValueIsRedactedFromTheConstraintsMessage() {
        assertEquals("is not a token: [redacted]", message(new Holder("hunter2", null), "token"));
    }

    @Test
    void aMessageThatStillHoldsTheValueOnlyNamesTheConstraint() {
        assertEquals("fails @Leaky", message(new Holder("xhunter2", null), "token"));
    }

    @Test
    void builtInMessagesStayReadable() {
        assertEquals("must be a well-formed email address", message(new Holder("tok_1", "hunter2"), "email"));
    }
}
