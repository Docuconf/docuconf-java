package dev.docuconf.check;

import java.nio.file.Path;
import java.util.Map;

/** The README's contract-first snippets, compiled so they stay correct (ReadmeSnippetsTest finds them here). */
final class ReadmeContractFirst {

    private ReadmeContractFirst() {
    }

    static Map<String, Object> boot() {
        Map<String, Object> config = ContractFirst.bootOrExit(Path.of("contract.json"));
        return config;
    }

    static boolean test(String json) {
        ContractFirst.Result r = ContractFirst.load(json, Map.of("PORT", "8080"));
        return r.ok();
    }
}
