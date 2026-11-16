package dev.docuconf.examples.shop;

import dev.docuconf.check.ContractFirst;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The shop example: one program with two roles, {@code web} and {@code worker}, chosen by {@code DOCUCONF_ROLE}. It
 * loads the environment against {@code contract.json} in contract-first mode, then prints one line describing what
 * the role would run, and exits. There is no server, database or queue.
 *
 * <p>With an invalid environment, {@link ContractFirst#bootOrExit} prints every problem, writes the termination log
 * and exits with status 1. Secret values are never printed.
 */
public final class ShopApplication {

    private ShopApplication() {
    }

    /**
     * Runs the example.
     *
     * @param args the contract's path, {@code contract.json} when none is given
     */
    public static void main(String[] args) {
        Path contract = Path.of(args.length > 0 ? args[0] : "contract.json");
        Map<String, Object> config = ContractFirst.bootOrExit(contract);
        System.out.println(describe(config));
    }

    /**
     * The line the app prints for a valid configuration, built from the typed values.
     *
     * @param config the typed values {@link ContractFirst} returns
     * @return for example {@code shop web: port 8080, origins https://shop.example}
     */
    public static String describe(Map<String, Object> config) {
        String role = (String) config.get("DOCUCONF_ROLE");
        return switch (role) {
            case "web" -> "shop web: port " + config.get("PORT") + ", origins " + csv(config.get("ALLOWED_ORIGINS"));
            case "worker" -> "shop worker: queues " + csv(config.get("QUEUES")) + ", webhooks "
                    + (Boolean.TRUE.equals(config.get("WEBHOOKS_ENABLED")) ? "on" : "off");
            default -> throw new IllegalStateException("unknown role " + role);
        };
    }

    private static String csv(Object list) {
        return ((List<?>) list).stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
