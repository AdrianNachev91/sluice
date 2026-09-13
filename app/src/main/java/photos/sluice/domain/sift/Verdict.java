package photos.sluice.domain.sift;

import java.nio.file.Path;

/**
 * What the vision step said about one photo on one sheet. Either a {@link Keep}, or one of the
 * {@link Decision} shapes that route the photo somewhere else.
 *
 * <p>A shard carries one of these per photo the sheet showed. {@link ShardValidator} enforces that,
 * and is also where the keeps stop.
 */
public sealed interface Verdict permits Verdict.Keep, Decision {

    /**
     * The photo's absolute source path.
     *
     * @return {@link Path} the photo this verdict is about
     */
    Path file();

    /**
     * A photo the vision step looked at and left where it is.
     *
     * <p>Carries no reason. It records that the photo was judged, and nothing else. A reason for
     * keeping a photo is text nobody reads: the file simply stays in Sorted, where a reader already
     * expects it.
     */
    record Keep(Path file) implements Verdict {}
}
