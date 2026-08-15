package photos.sluice.adapter.ui;

import photos.sluice.application.startup.StartupFailure;
import photos.sluice.application.startup.StartupFailure.RejectedSetting;
import photos.sluice.application.startup.StartupFailure.Unclassified;
import photos.sluice.application.startup.StartupFailure.UnparsableConfigFile;
import photos.sluice.application.startup.StartupFailure.WorkingRootBusy;

/**
 * Turns a classified startup failure into the two strings the failure window shows.
 *
 * <p>A presenter rather than a view: deciding what to show is a decision, and a decision is
 * something a test can hold to account.
 */
public class StartupFailurePresenter {

    private static final String HEADLINE = "Sluice could not start.";
    private static final String BUSY = "Another Sluice process is already running: close it and try again.";
    private static final String REJECTED_SETTING = "Sluice could not use the setting ";
    private static final String UNPARSABLE_CONFIG = "Sluice could not read your settings file.";
    private static final String GENERIC = "Sluice hit a problem it has no explanation for.";

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
     * What went wrong. Each failure says as much as this app actually knows about it, and the one
     * it knows nothing about says that instead.
     *
     * <p>Switched over every case rather than defaulted, so a failure added later cannot take the
     * last sentence with nobody deciding it should.
     *
     * @return {@link String} the detail line
     */
    public String detail() {
        return switch (this.failure) {
            case final WorkingRootBusy _ -> BUSY;
            case final RejectedSetting rejected -> REJECTED_SETTING + rejected.property() + ".";
            case final UnparsableConfigFile _ -> UNPARSABLE_CONFIG;
            case final Unclassified _ -> GENERIC;
        };
    }
}
