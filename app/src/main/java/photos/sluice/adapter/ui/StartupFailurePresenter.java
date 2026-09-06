package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.StartupFailureCard.BusyRoot;
import photos.sluice.adapter.ui.StartupFailureCard.Generic;
import photos.sluice.adapter.ui.StartupFailureCard.RejectedInFile;
import photos.sluice.adapter.ui.StartupFailureCard.Unparsable;
import photos.sluice.application.startup.StartupFailure;
import photos.sluice.application.startup.StartupFailure.ConfigPosition;
import photos.sluice.application.startup.StartupFailure.ConfigSpot;
import photos.sluice.application.startup.StartupFailure.RejectedSetting;
import photos.sluice.application.startup.StartupFailure.Unclassified;
import photos.sluice.application.startup.StartupFailure.UnparsableConfigFile;
import photos.sluice.application.startup.StartupFailure.UnusableSettings;
import photos.sluice.application.startup.StartupFailure.WorkingRootBusy;

import java.util.Locale;

/**
 * Turns a classified startup failure into the card the failure window draws.
 *
 * <p>A presenter rather than a view: deciding what to show is a decision, and a decision is
 * something a test can hold to account.
 */
public class StartupFailurePresenter {

    private static final String HEADLINE = "Sluice could not start.";
    private static final String BUSY = "Another Sluice process is already running: close it and try again.";
    private static final String REJECTED_SETTING = "Sluice could not use the setting ";
    private static final String UNPARSABLE_CONFIG = "Sluice could not read your settings file. It likely has a "
            + "typo or a formatting mistake, often a missing or extra bracket or quote mark. Starting fresh "
            + "renames the file aside rather than deleting it, and Sluice starts over "
            + "with nothing configured. You can still open the old file afterward in a text editor to copy "
            + "anything you typed by hand, like your category descriptions.";
    private static final String GENERIC = "Sluice hit a problem it has no explanation for. Report this as a "
            + "bug in Sluice.";

    private final StartupFailure failure;

    /**
     * Creates the presenter over the failure that stopped startup.
     *
     * @param failure {@link StartupFailure} what stopped the app starting
     */
    public StartupFailurePresenter(final StartupFailure failure) {
        this.failure = failure;
    }

    /**
     * The one line naming what happened.
     *
     * @return {@link String} the headline
     */
    public String headline() {
        return HEADLINE;
    }

    /**
     * The card the failure window draws, chosen so a failure added later cannot take the generic
     * card's copy with nobody deciding it should.
     *
     * <p>A rejected setting names the file only when the value is in the user's own. The app's own
     * bundled defaults are a text resource too. Sending a user to edit one of those would offer to
     * remove a key from a file that is not theirs.
     *
     * @return {@link StartupFailureCard} the card to draw
     */
    public StartupFailureCard card() {
        return switch (this.failure) {
            case final WorkingRootBusy _ -> new BusyRoot(BUSY);
            case final RejectedSetting rejected when rejected.spot() != null ->
                    rejectedInFile(rejected, rejected.spot());
            case final RejectedSetting rejected -> new Generic(rejectedElsewhere(rejected), rejected.trace());
            case final UnparsableConfigFile unparsable -> new Unparsable(UNPARSABLE_CONFIG,
                    unparsable.spot().file().toString(), positionText(unparsable.spot()), unparsable.problem(),
                    unparsable.trace());
            case final UnusableSettings unusable -> new Generic(unusable.problem(), unusable.trace());
            case final Unclassified unclassified -> new Generic(GENERIC, unclassified.trace());
        };
    }

    private static RejectedInFile rejectedInFile(final RejectedSetting rejected, final ConfigSpot spot) {
        return new RejectedInFile(rejectedInFileDetail(rejected), rejected.property(),
                spot.file().toString(), positionText(spot), rejected.trace());
    }

    /**
     * The detail line for a rejected setting in the user's own file. What to expect there, and
     * what removing it costs, worded for someone with no familiarity with settings files at all.
     *
     * @param rejected {@link RejectedSetting} the failure, with a spot in the user's own file
     * @return {@link String} the detail line
     */
    private static String rejectedInFileDetail(final RejectedSetting rejected) {
        return REJECTED_SETTING + rejected.property() + ". This usually means the value there has a "
                + "typo, or is not the kind of value this setting expects. Removing it resets just "
                + "this one setting to Sluice's own default. Everything else you have configured "
                + "stays as it is. If the value looks right to you, report this as a bug in Sluice.";
    }

    /**
     * The detail line for a rejected setting with no spot: a bundled default or an environment
     * variable, the two sources this app cannot tell apart once classified. Names the specific
     * variable that would override it, the one thing the user can act on either way.
     *
     * @param rejected {@link RejectedSetting} the failure, with no spot in the user's file
     * @return {@link String} the detail line
     */
    private static String rejectedElsewhere(final RejectedSetting rejected) {
        return REJECTED_SETTING + rejected.property() + ". It did not come from your settings file. Check for an "
                + "environment variable named " + envVarNameFor(rejected.property())
                + ", or report this as a bug in Sluice if you have not set one.";
    }

    /**
     * The environment variable name that would override this property, per Spring's own relaxed
     * binding: every {@code .} and {@code -} becomes {@code _}, upper-cased.
     *
     * @param property {@link String} the setting, dotted as config binding names it
     * @return {@link String} the environment variable name
     */
    private static String envVarNameFor(final String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static @Nullable String positionText(final ConfigSpot spot) {
        final ConfigPosition position = spot.position();
        return position == null ? null : "line %d, column %d".formatted(position.line(), position.column());
    }
}
