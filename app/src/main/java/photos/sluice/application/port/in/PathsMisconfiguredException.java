package photos.sluice.application.port.in;

import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;
import photos.sluice.domain.paths.PathViolation.Unreadable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when folder roots that cannot be worked in are refused.
 *
 * <p>Two callers throw it, and they admit different things. Work is refused whenever a root is
 * unset, missing or overlapping, so a fresh install meets this on every call until its first run is
 * configured. A settings save is refused only for a root set to somewhere unusable. An unset root
 * saves there, because that is what an install still choosing its folders looks like.
 *
 * <p>The violations are carried as values. A surface that marks a field, or routes a user to the
 * right setting, reads those rather than the message. The message exists for a log and for a caller
 * that only reports what it caught.
 *
 * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know it was refused
 * needs no knowledge of this type at all.
 */
public final class PathsMisconfiguredException extends IllegalStateException {

    private final transient List<PathViolation> violations;

    /**
     * Creates the exception over the violations that caused the refusal.
     *
     * @param violations a {@link List} of {@link PathViolation} why the roots are not usable
     */
    public PathsMisconfiguredException(final List<PathViolation> violations) {
        super(render(violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * Why the roots are not usable.
     *
     * @return a {@link List} of {@link PathViolation} the violations behind this refusal
     */
    public List<PathViolation> violations() {
        return this.violations;
    }

    /**
     * Words the violations for a log.
     *
     * @param violations a {@link List} of {@link PathViolation} the violations to word
     * @return {@link String} the message
     */
    private static String render(final List<PathViolation> violations) {
        return "Sluice cannot work with these folder settings: "
                + violations.stream().map(PathsMisconfiguredException::render).collect(Collectors.joining(" "));
    }

    /**
     * Words one violation for a log.
     *
     * @param violation {@link PathViolation} the violation to word
     * @return {@link String} the sentence for it
     */
    private static String render(final PathViolation violation) {
        return switch (violation) {
            case final NotConfigured v -> property(v.role()) + " is not set.";
            case final NotAPath v -> property(v.role()) + " (" + v.value() + ") is not a usable folder path.";
            case final NotADirectory v -> property(v.role()) + " (" + v.path() + ") is not an existing folder.";
            case final Unreadable v -> property(v.role()) + " (" + v.path() + ") is there but could not be read.";
            case final Overlap v -> property(v.first()) + " and " + property(v.second())
                    + " must not contain each other.";
        };
    }

    /**
     * What a config file and an environment variable call this root. Kept here rather than on
     * {@link PathRole}, so how a root is spelled outside the app stays the caller's business rather
     * than the domain's. Adding a fourth root fails to compile here until it is named.
     *
     * @param role {@link PathRole} the root to name
     * @return {@link String} the configuration property that sets it
     */
    private static String property(final PathRole role) {
        return switch (role) {
            case WORKING_ROOT -> "sluice.paths.repo-root";
            case LIBRARY_ROOT -> "sluice.paths.library-root";
            case INBOX -> "sluice.paths.inbox";
        };
    }
}
