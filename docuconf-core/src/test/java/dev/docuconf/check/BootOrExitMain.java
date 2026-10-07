package dev.docuconf.check;

import java.nio.file.Path;

/** Runs {@link ContractFirst#bootOrExit} in a child JVM for {@link ContractFirstExitTest}. */
public final class BootOrExitMain {

    private BootOrExitMain() {
    }

    public static void main(String[] args) {
        System.out.println("ORDERS_PORT=" + ContractFirst.bootOrExit(Path.of(args[0])).get("ORDERS_PORT"));
    }
}
