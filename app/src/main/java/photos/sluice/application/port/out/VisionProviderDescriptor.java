package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;
import photos.sluice.secrets.SecretId;

import java.util.Set;

/**
 * What a vision provider tells a surface that configures it. What to call it, which settings it
 * uses, which of those it cannot run without, the credential it authenticates with, and the models
 * it offers.
 *
 * <p>A sieve answers with one of these from {@link VisionSieve#describe()}.
 *
 * <p>A surface may draw a web address inside {@code setupGuide} as a link somebody can press. So
 * that sentence is a place this app takes a reader to, not only words it shows them. A provider
 * added to this repository is reviewed on that footing. {@code SmokeTest} records every address a
 * provider wired into the app offers, so one cannot be added or moved unnoticed. A module loaded
 * through the SPI is outside that record, and outside this repository's review.
 *
 * @param id {@link String} the provider id a configured setting names to select this provider
 * @param label {@link String} what a surface calls it, in the user's own terms
 * @param settingsUsed a {@link Set} of {@link ProviderSetting} every setting that applies to it
 * @param required a {@link Set} of {@link ProviderSetting} those it cannot run without, a subset of
 *     the above
 * @param credential the credential it authenticates with, or null when it takes none
 * @param models {@link ModelCatalog} the models it offers without being asked, or null when it runs
 *     no model of its own
 * @param defaultEndpoint {@link String} the address an empty endpoint field actually reaches, or
 *     null when either the provider takes no endpoint or it has none worth naming
 * @param setupGuide {@link String} one short sentence saying where somebody with no credential yet
 *     goes to get one. Null when this provider takes none, or has nowhere to send them
 */
public record VisionProviderDescriptor(String id, String label, Set<ProviderSetting> settingsUsed,
                                       Set<ProviderSetting> required, @Nullable SecretId credential,
                                       @Nullable ModelCatalog models, @Nullable String defaultEndpoint,
                                       @Nullable String setupGuide) {

    /**
     * Creates the descriptor, holding its own sets so a provider cannot hand one out and then
     * change what it said.
     *
     * <p>The refusals here are what a provider would otherwise get wrong quietly. Each
     * produces a working screen that does the wrong thing, which is why none of them is left to a
     * convention somebody reads.
     */
    public VisionProviderDescriptor {
        settingsUsed = Set.copyOf(settingsUsed);
        required = Set.copyOf(required);
        if (!settingsUsed.containsAll(required)) {
            throw new IllegalArgumentException(
                    "Provider '" + id + "' requires a setting it does not use: " + required);
        }
        // Otherwise a screen draws a credential block with nothing to store into, or stores into a
        // credential no control ever offered to fill.
        final boolean namesOne = credential != null;
        if (settingsUsed.contains(ProviderSetting.CREDENTIAL) != namesOne) {
            throw new IllegalArgumentException("Provider '" + id
                    + "' must name a credential exactly when it uses one");
        }
        // A credential is filed under its own provider name, and nothing else polices that name.
        // Two providers naming one credential share it, and saving either key overwrites the other.
        if (credential != null && !credential.name().equals(id)) {
            throw new IllegalArgumentException("Provider '" + id + "' names a credential belonging to '"
                    + credential.name() + "'");
        }
        // Otherwise a screen draws a model picker with nothing to offer, or holds a catalog behind
        // a control it never shows.
        final boolean offersSome = models != null;
        if (settingsUsed.contains(ProviderSetting.MODEL) != offersSome) {
            throw new IllegalArgumentException("Provider '" + id
                    + "' must offer a model catalog exactly when it uses a model setting");
        }
        // Otherwise a screen prompts an empty field with an address the provider never takes.
        if (defaultEndpoint != null && !settingsUsed.contains(ProviderSetting.ENDPOINT)) {
            throw new IllegalArgumentException("Provider '" + id
                    + "' names a default endpoint but does not use an endpoint setting");
        }
        // Otherwise a screen tells somebody where to get a credential this provider would never ask
        // them for.
        if (setupGuide != null && !settingsUsed.contains(ProviderSetting.CREDENTIAL)) {
            throw new IllegalArgumentException("Provider '" + id
                    + "' names a setup guide but does not use a credential");
        }
    }
}
