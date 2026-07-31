package photos.sluice.domain.model;

/**
 * Zero-pads a non-negative integer into a fixed-width ASCII decimal string. A bare
 * {@code "%0Nd".formatted(...)} draws its digits and zero-padding character from the JVM's
 * default {@code FORMAT} locale instead. Every path segment, id, and prep-dir tag built from a
 * year, month, or sequence number routes through here instead. A single shared site covers every
 * caller, rather than a convention each new one has to separately remember.
 */
public final class Numerals {

    /**
     * Prevents instantiation of this static utility class.
     */
    private Numerals() {
    }

    /**
     * Formats value as a decimal string of exactly digits width, ASCII zero-padded on the left.
     * Builds the padding manually rather than through a {@link java.util.Formatter} conversion, so
     * there is no locale to accidentally reintroduce at a future call site.
     *
     * @param value int the non-negative value to format
     * @param digits int the minimum width to pad to
     * @return {@link String} the zero-padded ASCII decimal string
     */
    public static String padded(final int value, final int digits) {
        final String raw = String.valueOf(value);
        return raw.length() >= digits ? raw : "0".repeat(digits - raw.length()) + raw;
    }
}
