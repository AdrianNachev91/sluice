package photos.sluice.adapter.cli;

/**
 * Whether a typed argument is written in the digits 0 to 9.
 *
 * <p>{@code Character.isDigit} accepts every decimal digit Unicode defines, and
 * {@code Integer.parseInt} decodes them. So a value in another script parses to a number whose own
 * spelling is a different string. A folder tag built from that number does not read back as the
 * value it came from.
 */
final class AsciiDigits {

    /**
     * Prevents instantiation of this static utility class.
     */
    private AsciiDigits() {
    }

    /**
     * Whether every character is one of 0 to 9.
     *
     * <p>An empty value is not, so a caller checking this needs no separate guard for one.
     *
     * @param text {@link String} the value as it was typed
     * @return boolean true when it holds at least one character and all of them are digits
     */
    static boolean only(final String text) {
        return !text.isEmpty() && text.chars().allMatch(character -> character >= '0' && character <= '9');
    }
}
