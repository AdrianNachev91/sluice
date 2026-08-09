package photos.sluice.application.port.in;

import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when work is refused because the folder roots it would reach are not usable. A fresh
 * install meets this on every call until its first run is configured.
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
        return "Sluice's folders are not set up yet: "
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
            case final Overlap v -> property(v.first()) + " and " + property(v.second())
                    + " must not contain each other.";
        };
    }

    /**
     * What a config file and an environment variable call this root. A switch rather than a field on
     * the role itself, so how a root is spelled outside the app stays the caller's business rather
     * than the rule's. Adding a fourth root fails to compile here until it is named.
     *
     * @param role {@link PathRole} the root to name
     * @return {@link String} the configuration property that sets it
     */
    private static String property(final PathRole role) {
        return switch (role) {
            case REPO_ROOT -> "sluice.paths.repo-root";
            case LIBRARY_ROOT -> "sluice.paths.library-root";
            case INBOX -> "sluice.paths.inbox";
        };
    }
}
