package dev.docuconf;

/**
 * How property names become environment variable names in the contract. Spring Boot binds both forms.
 */
public enum EnvNames {

    /**
     * Spring's canonical form: dots become underscores and dashes are dropped. {@code orders.allowed-origins}
     * is {@code ORDERS_ALLOWEDORIGINS}.
     */
    COMPACT,

    /**
     * Dashes become underscores too, which reads better: {@code orders.allowed-origins} is
     * {@code ORDERS_ALLOWED_ORIGINS}. Spring binds this form as well; the build fails if two properties of the
     * service would get the same name ({@code orders.allowed-origins} and {@code orders.allowed.origins}).
     */
    UNDERSCORED
}
