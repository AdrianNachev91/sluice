package photos.sluice.domain.cull;

import java.nio.file.Path;

// One non-keep decision the vision step made about a single photo in a montage. Keeps are never
// represented - an unlisted photo stays where it is. This is a closed set of three shapes:
//
//   Classification  - route one photo to a review/library category (junk, scenery, food, funny, or
//                     a user-defined one). The category is DATA, not a subtype: the configured
//                     category set can grow without touching this hierarchy. See ShardValidator for
//                     the "category must be configured" rule.
//   NearDupChosen   - the keeper of a near-duplicate group; carries chosenReason, not reason.
//   NearDupReject   - a rejected member of a near-duplicate group; carries the group and a reason.
//
// Near-dups keep their own two shapes because they carry a group identity and a chosen/reject split
// that a flat category cannot express. file is the photo's absolute source path.
public sealed interface Decision {

    /**
     * The photo's absolute source path.
     *
     * @return {@link Path} the decision's source file path
     */
    Path file();

    record Classification(Path file, String category, String reason) implements Decision {}

    record NearDupChosen(Path file, String group, String chosenReason) implements Decision {}

    record NearDupReject(Path file, String group, String reason) implements Decision {}
}
