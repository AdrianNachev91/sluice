package photos.sluice.application.port.in;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Every folder Sluice has filled with photos it will not touch again until somebody looks at them.
 *
 * <p>The three roots of {@link Root} are listed together because a reader has one question about
 * all three. What is waiting for me, and where is it.
 *
 * <p>A folder here is one that directly holds files, whatever depth that sits at under its root.
 *
 * @param folders a {@link List} of {@link Folder} every folder holding photos or video, newest
 *     first
 * @param unreadable a {@link List} of {@link Path} the roots that could not be read at all, as
 *     configured. The path rather than which of the three it is, because a caller reporting one is
 *     naming a folder somebody has to go and open
 */
public record ReviewListing(List<Folder> folders, List<Path> unreadable) {

    /**
     * Defensively copies the mutable collection components.
     *
     * @param folders a {@link List} of {@link Folder} every folder holding anything
     * @param unreadable a {@link List} of {@link Path} the roots that could not be read
     */
    public ReviewListing {
        folders = List.copyOf(folders);
        unreadable = List.copyOf(unreadable);
    }

    /**
     * One folder, and what is in it.
     *
     * @param root {@link Root} which of the three it sits under
     * @param filedBy {@link FiledBy} which job put it there
     * @param name {@link String} its path below that root, using {@code /} whatever the platform
     *     writes. This is also the name {@link RescueUseCase#rescue} takes, alongside the root's own
     *     {@link Root#rescueRoot}
     * @param path {@link Path} where it is, for handing to the file manager
     * @param photos int how many photos are in it
     * @param videos int how many videos are in it
     * @param changed {@link Instant} when it was last written to
     */
    public record Folder(Root root, FiledBy filedBy, String name, Path path, int photos, int videos,
                         Instant changed) {
    }

    /**
     * Which job filed a folder.
     *
     * <p>Only the Review root has both. The two answer different questions, so a reader meets them
     * under headings of their own.
     */
    public enum FiledBy {

        /** A sort, which files what it could not date and what it judged too small to keep. */
        A_SORT,

        /** A sift, which files a category, a near-copy group and anything it could not judge. */
        A_SIFT
    }

    /**
     * Which root a folder sits under.
     */
    public enum Root {

        /** The categories somebody configured, plus what a sort would not put in Sorted. */
        REVIEW,

        /** One folder per near-duplicate group, holding every candidate and a note. */
        DUPLICATES,

        /** What a sift could not render a judgeable tile for, so no model ever saw it. */
        UNREVIEWABLE;

        /**
         * The rescue this root's folders are moved back into Sorted by.
         *
         * @return {@link RescueRoot} what {@link RescueUseCase#rescue} takes for this root
         */
        public RescueRoot rescueRoot() {
            return switch (this) {
                case REVIEW -> RescueRoot.REVIEW;
                case UNREVIEWABLE -> RescueRoot.UNREVIEWABLE;
                case DUPLICATES -> RescueRoot.DUPLICATES;
            };
        }
    }
}
