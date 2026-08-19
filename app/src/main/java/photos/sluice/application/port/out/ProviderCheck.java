package photos.sluice.application.port.out;

/**
 * What came back from asking a vision provider whether it accepts the credential stored for it.
 *
 * <p>Sealed, so a surface rendering these is told by the compiler when a new answer arrives rather
 * than falling through to a sentence that does not fit.
 *
 * <p>The successful answer carries the provider's own models, because the question "does this key
 * work" and the question "what can this key run" are the same request. Asking twice would spend two
 * round trips to learn one thing.
 *
 * <p>Only some variants carry the service's own words. They are the ones whose sentence cannot
 * stand alone. A refusal and a transport failure each have many causes, and the service naming its
 * own is what makes the difference actionable. A rejected credential has one meaning, so quoting
 * boilerplate at the user would add noise rather than information.
 */
public sealed interface ProviderCheck {

    /**
     * The provider accepted the credential and answered with what it can run.
     *
     * @param models {@link ModelCatalog} the models this credential can actually use
     */
    record Accepted(ModelCatalog models) implements ProviderCheck {}

    /**
     * The provider accepted the credential, and this account can run none of the models Sluice
     * needs. Separate from {@link Accepted} because a catalog always holds at least one model, so
     * an empty one cannot be built to say this.
     */
    record NoUsableModels() implements ProviderCheck {}

    /**
     * Nothing is stored for this provider, so nothing was asked. Distinct from a provider that
     * authenticates with nothing at all, which is a settled state rather than one to fix.
     */
    record NoCredential() implements ProviderCheck {}

    /** The provider refused the credential itself. */
    record Rejected() implements ProviderCheck {}

    /**
     * The credential is recognised, and this account may not do this.
     *
     * @param detail {@link String} what the provider said, which is what separates a billing
     *     problem from a permissions one
     */
    record Refused(String detail) implements ProviderCheck {}

    /**
     * The provider did not answer the question. It could not be reached, or took too long, or asked
     * to be tried later, or replied with something this app could not read.
     *
     * @param detail {@link String} what failed, which is what separates a proxy or a name-resolution
     *     problem from a service that is simply busy
     */
    record Unreachable(String detail) implements ProviderCheck {}

    /** This provider authenticates with nothing, so there is no credential to check. */
    record NotApplicable() implements ProviderCheck {}
}
