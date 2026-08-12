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
 * them. Probes on two Windows machines created both as ordinary files, which is what
 * {@code ReservedDeviceNamesTest} pins.
 *
 * <p>Which of the listed names a machine actually reserves is not fixed. That is the real argument
 * for refusing them everywhere. {@code com1} is a symbolic link to a serial device, not a name the
 * path parser reserves outright. A machine with no such device therefore does not have it.
 *
 * <p>Measured: {@code com1} is refused on a Windows 10 desktop (10.0.19045). It creates as an
 * ordinary file on a GitHub Windows Server 2025 runner (10.0.26100). One config, two machines, two
 * answers. A name whose meaning turns on installed hardware must not decide where a photo goes.
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
