package photos.sluice.application.port.out;

import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.JunkCategory;
import photos.sluice.domain.cull.MontageConfig;

import java.util.List;
import java.util.stream.Stream;

/**
 * The effect boundary the application layer and vision adapters use to read the cull
 * configuration, so neither imports the config record that supplies it. The settings bean
 * implements this by exposing the values it already binds.
 */
public interface CullSettings {

    /**
     * Id of the vision provider to route a cull through, matched against what each
     * {@link VisionCuller} describes itself as.
     *
     * @return {@link String} the configured provider id
     */
    String provider();

    /**
     * The configured classification category cards. Every classification decision's category must
     * be one of the card names; ShardValidator checks that. Automated vision providers also render
     * each card's description into their culling prompt.
     *
     * @return a {@link List} of {@link CullCategory} the configured category cards
     */
    List<CullCategory> categories();

    /**
     * The configured cards a cull actually routes to, which is every card the user has not switched
     * off. Read at prep time, where the set is recorded into the run's own prep directory.
     *
     * <p>Prep time is the only place this is asked for. That is what makes the switch mean "leave
     * it out of runs from now on" rather than "retract it". The prompt and the validator both read
     * the recorded set rather than live configuration. So a card switched off after a run was
     * prepped stays valid for that run, and one switched on does not join it.
     *
     * <p>A repair reads {@link #categoriesForRepair()} instead, deliberately. It is reconstructing
     * a lost index for work already done, and filtering there would invalidate a decision written
     * under a card the user has since switched off.
     *
     * <p>Junk is appended last, so a reader of the prompt meets the configured cards first.
     *
     * @return a {@link List} of {@link CullCategory} the enabled cards then junk, in configured
     *     order
     */
    default List<CullCategory> activeCategories() {
        return withJunkLast(this.categories().stream().filter(CullCategory::enabled));
    }

    /**
     * Every card a decision in an existing run could have been written under.
     *
     * <p>Unfiltered, so a decision written under a card since switched off still validates. For
     * rebuilding a lost index over work already done.
     *
     * @return a {@link List} of {@link CullCategory} every configured card then junk
     */
    default List<CullCategory> categoriesForRepair() {
        return withJunkLast(this.categories().stream());
    }

    /**
     * Connection settings for the provider {@link #provider()} names. For a surface reporting on
     * what is configured right now.
     *
     * @return {@link CullProviderSettings} that provider's settings, every field null when nothing
     *         is configured for it
     */
    CullProviderSettings providerSettings();

    /**
     * Connection settings for one named provider, whichever one is in force.
     *
     * <p>What a culler reads about itself. A screen can offer to test a selection the user has not
     * saved yet. A provider asking for "the settings in force" would then read another provider's
     * endpoint.
     *
     * @param providerId {@link String} the provider whose settings to read
     * @return {@link CullProviderSettings} that provider's settings, every field null when nothing
     *         is configured for it
     */
    CullProviderSettings providerSettings(String providerId);

    /**
     * Tuning for the external-agent provider only. An implementation must return a non-null
     * instance, substituting a MANUAL-mode default for a missing configured block; see
     * ExternalAgentSettings' own doc for its own null-handling.
     *
     * @return {@link ExternalAgentSettings} the external-agent tuning settings
     */
    ExternalAgentSettings externalAgent();

    /**
     * The contact-sheet grid a cull renders: tile size, and how many tiles form a row. Asked for at
     * the moment it is needed, so a saved change reaches a cull without a restart.
     *
     * @return {@link MontageConfig} the montage grid configuration
     */
    MontageConfig montage();

    /**
     * The given cards, then junk, and junk exactly once.
     *
     * <p>Any card already carrying junk's name is dropped in favour of the supplied one, so the
     * promise holds whoever implements this port.
     *
     * @param cards a {@link Stream} of {@link CullCategory} the configured cards to take
     * @return a {@link List} of {@link CullCategory} those cards, then junk
     */
    private static List<CullCategory> withJunkLast(final Stream<CullCategory> cards) {
        return Stream.concat(cards.filter(card -> !JunkCategory.claims(card.name())),
                Stream.of(JunkCategory.card())).toList();
    }
}
