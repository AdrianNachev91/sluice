package photos.sluice.application.port.out;

/**
 * One setting a vision provider may or may not use. Named, so a provider can say which apply to it
 * without any configuration surface knowing that provider exists.
 *
 * <p>A control for a setting its provider does not use can do nothing at all. Offering one reads as
 * a promise the app does not keep.
 *
 * <p>Adding a constant here changes what the app can be configured with. Every surface drawing
 * these then owes a control for it. Adding a provider changes nothing here.
 */
public enum ProviderSetting {

    /** Which model reads the photos. */
    MODEL,

    /** A service address other than the provider's own. */
    ENDPOINT,

    /** How many times a failed connection is tried again. */
    RETRIES,

    /** Whether a waiting cull resumes on its own once every montage is ready. */
    WATCH_MODE,

    /** A stored credential the provider authenticates with. */
    CREDENTIAL
}
