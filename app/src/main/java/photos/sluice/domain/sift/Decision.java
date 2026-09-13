package photos.sluice.domain.sift;

import java.nio.file.Path;

/**
 * One non-keep decision the vision step made about a single photo in a montage. A photo it decided
 * to keep is a {@link Verdict.Keep}, which is not one of these.
 *
 * <p>A {@link Classification}'s category is data rather than a subtype, so the configured category
 * set can grow without touching this hierarchy. See {@link ShardValidator} for the "category must
 * be configured" rule.
 *
 * <p>Near-dups keep their own two shapes because they carry a group identity and a chosen/reject
 * split that a flat category cannot express. {@code file} is the photo's absolute source path,
 * declared on {@link Verdict}.
 */
public sealed interface Decision extends Verdict {

    /**
     * Routes one photo to a review or library category, such as junk, scenery, food, funny, or a
     * user-defined one, with the vision step's reason for that call.
     */
    record Classification(Path file, String category, String reason) implements Decision {}

    /**
     * The keeper chosen from a near-duplicate group, with the vision step's reason for picking it
     * over the group's rejects.
     */
    record NearDupChosen(Path file, String group, String chosenReason) implements Decision {}

    /**
     * A rejected member of a near-duplicate group, with the vision step's reason for not keeping
     * it.
     */
    record NearDupReject(Path file, String group, String reason) implements Decision {}
}
