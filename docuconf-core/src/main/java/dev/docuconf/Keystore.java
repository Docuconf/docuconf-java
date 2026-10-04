package dev.docuconf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;

/** A mounted PKCS#12 or JKS keystore. */
public final class Keystore {

    private final Path path;
    private final String type;

    /**
     * Creates a handle for a file.
     *
     * @param path the keystore
     * @param type {@code PKCS12} or {@code JKS}
     */
    public Keystore(Path path, String type) {
        this.path = Objects.requireNonNull(path, "path");
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * The keystore's location.
     *
     * @return the path
     */
    public Path path() {
        return path;
    }

    /**
     * The keystore type, for {@link KeyStore#getInstance(String)}.
     *
     * @return {@code PKCS12} or {@code JKS}
     */
    public String type() {
        return type;
    }

    /**
     * Opens the keystore.
     *
     * @param password the password, or {@code null}
     * @return the loaded keystore
     * @throws IOException if it cannot be read, or the password is wrong
     * @throws GeneralSecurityException if it is malformed
     */
    public KeyStore load(char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password);
        }
        return store;
    }

    @Override
    public String toString() {
        return "Keystore[" + path + "]";
    }
}
