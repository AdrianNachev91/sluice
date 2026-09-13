package photos.sluice.application.port.out;

import photos.sluice.domain.sift.SiftCategory;
import photos.sluice.domain.sift.JunkCategory;
import photos.sluice.domain.sift.MontageConfig;

import java.util.List;
import java.util.stream.Stream;

/**
 * The effect boundary the application layer and vision adapters use to read the sift
 * configuration, so neither imports the config record that supplies it. The settings bean
 * implements this by exposing the values it already binds.
 */
public interface SiftSettings {

    /**
     * Id of the vision provider to route a sift through, matched against what each
     * {@link VisionSieve} describes itself as.
     *
     * @return {@link String} the configured provider id
     */
    String provider();

    /**
     * The configured classification category cards. Every classification decision's category must
     * be one of the card names. Automated vision providers also render each card's description into
     * their sifting prompt.
     *
     * @return a {@link List} of {@link SiftCategory} the configured category cards
     */
    List<SiftCategory> categories();

    /**
     * The configured cards a sift actually routes to, which is every card the user has not switched
     * off. Read at prep time, where the set is recorded into the run's own prep directory.
     *
     * <p>Asked for nowhere else, which is what makes the switch mean "leave it out of runs from now
     * on" rather than "retract it". A card switched off after a run was prepped stays valid for that
     * run, and one switched on does not join it.
     *
     * <p>A repair reads {@link #categoriesForRepair()} instead, deliberately.
     *
     * <p>Junk is appended last, so a reader of the prompt meets the configured cards first.
     *
     * @return a {@link List} of {@link SiftCategory} the enabled cards then junk, in configured
     *     order
     */
    default List<SiftCategory> activeCategories() {
        return withJunkLast(this.categories().stream().filter(SiftCategory::enabled));
    }

    /**
     * Every card a decision in an existing run could have been written under.
     *
     * <p>Unfiltered, so a decision written under a card since switched off still validates. For
     * rebuilding a lost index over work already done.
     *
     * @return a {@link List} of {@link SiftCategory} every configured card then junk
     */
    default List<SiftCategory> categoriesForRepair() {
        return withJunkLast(this.categories().stream());
    }

    /**
     * Connection settings for the provider {@link #provider()} names. For a surface reporting on
     * what is configured right now.
     *
     * @return {@link SiftProviderSettings} that provider's settings, every field null when nothing
     *         is configured for it
     */
    SiftProviderSettings providerSettings();

    /**
     * Connection settings for one named provider, whichever one is in force.
     *
     * <p>What a sieve reads about itself. A provider asking instead for "the settings in force"
     * would read another provider's endpoint whenever a screen is trying an unsaved selection.
     *
     * @param providerId {@link String} the provider whose settings to read
     * @return {@link SiftProviderSettings} that provider's settings, every field null when nothing
     *         is configured for it
     */
    SiftProviderSettings providerSettings(String providerId);

    /**
     * The contact-sheet grid a sift renders: tile size, and how many tiles form a row. Asked for at
     * the moment it is needed, so a saved change reaches a sift without a restart.
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
     * @param cards a {@link Stream} of {@link SiftCategory} the configured cards to take
     * @return a {@link List} of {@link SiftCategory} those cards, then junk
     */
    private static List<SiftCategory> withJunkLast(final Stream<SiftCategory> cards) {
        return Stream.concat(cards.filter(card -> !JunkCategory.isJunkName(card.name())),
                Stream.of(JunkCategory.category())).toList();
    }
}
