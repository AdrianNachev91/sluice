package photos.sluice.domain.paths;

import java.util.regex.Pattern;

/**
 * The names Windows resolves to a device rather than to a file or a folder. Anything this app turns
 * into a path segment is checked against them on every platform, not only on Windows. Windows
 * resolves one whatever extension follows it, so a folder called {@code con} would name the console
 * instead. Refusing them everywhere means a name cannot work on the machine it was written on and
 * break on someone else's.
 *
 * <p>This is Microsoft's documented set, minus the superscript forms. Their guidance also reserves
 * {@code com¹}, {@code com²}, {@code com³} and the {@code lpt} equivalents, because Windows reads
 * ISO/IEC 8859-1 superscript digits as digits. Nothing can reach this method with one: every caller
 * gates on an ASCII-only allowlist first, so a superscript is refused a step earlier.
 *
 * <p>{@code com0} and {@code lpt0} are deliberately absent, because that guidance does not list
 * them. A probe created both as ordinary files, and {@code com1} and {@code lpt1} were refused on
 * the same machine. That probe covers Windows 10 only. Windows 11 is untested, here and in the
 * public reports that made this a question.
 */
public final class ReservedDeviceNames {

    // Case-insensitive by construction. Windows reserves the name whatever case it is written in,
    // and a callers-must-lower-case-first rule would be a contract nothing enforces.
    private static final Pattern RESERVED =
            Pattern.compile("con|prn|aux|nul|com[1-9]|lpt[1-9]", Pattern.CASE_INSENSITIVE);

    /**
     * Prevents instantiation of this static utility class.
     */
    private ReservedDeviceNames() {
    }

    /**
     * Whether a path segment names a device Windows reserves.
     *
     * @param segment {@link String} the candidate path segment
     * @return boolean true if the segment names a reserved device
     */
    public static boolean isReserved(final String segment) {
        return RESERVED.matcher(segment).matches();
    }
}
