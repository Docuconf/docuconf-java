package dev.docuconf.contract;

/**
 * A contract with its Java bindings.
 *
 * @param contract the contract
 * @param bindings where each input lives in the app's classes
 */
public record ContractBundle(Contract contract, Bindings bindings) {
}
