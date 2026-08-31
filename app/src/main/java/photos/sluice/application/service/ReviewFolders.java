package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.in.ReviewListing;
import photos.sluice.application.port.in.ReviewListing.FiledBy;
import photos.sluice.application.port.in.ReviewListing.Folder;
import photos.sluice.application.port.in.ReviewListing.Root;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.domain.model.MediaType;
import photos.sluice.domain.paths.SortFolderNames;
import photos.sluice.domain.scan.MediaTypeDetector;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lists the folders Sluice has filled and left for somebody to look at, and reads the notes it wrote
 * beside them.
 *
 * <p>Takes a {@link MediaReader} rather than a {@link MediaStore}, so nothing here can move or
 * delete what it is describing.
 *
 * <p>One walk per root, and each walk is guarded on its own. A root that cannot be read then costs
 * its own entry rather than the whole listing. The three are independent folders, and a user may
 * have configured them onto different drives.
 */
final class ReviewFolders {

    private static final Logger log = LoggerFactory.getLogger(ReviewFolders.class);

    // What the app writes beside the photos it sets aside.
    private static final String NOTE_SUFFIX = ".txt";

    private final MediaReader media;
    private final PathsPort paths;
    private final MediaTypeDetector mediaTypeDetector = new MediaTypeDetector();

    /**
     * Creates the listing over the tree reader and the roots it is taken under.
     *
     * @param media {@link MediaReader} walks a tree and stats what is in it
     * @param paths {@link PathsPort} resolves the three roots
     */
    ReviewFolders(final MediaReader media, final PathsPort paths) {
        this.media = media;
        this.paths = paths;
    }

    /**
     * Every folder under the three roots that holds photos or video, newest first.
     *
     * <p>A folder whose media has all gone is left out, which is what a reader who weeded one to
     * nothing has made. Its note file survives the weeding and would otherwise keep a card alive
     * reading "0 photos", offering to move nothing.
     *
     * <p>What that leaves on disk is a folder holding one note file and no way to reach it from
     * here. Deleting it is the file manager's job, which is where the reader already was.
     *
     * @return {@link ReviewListing} the folders, and any root that could not be read
     */
    ReviewListing list() {
        final List<Folder> folders = new ArrayList<>();
        final List<Path> unreadable = new ArrayList<>();
        for (final Root root : Root.values()) {
            this.collect(root, folders, unreadable);
        }
        folders.removeIf(folder -> folder.photos() == 0 && folder.videos() == 0);
        folders.sort(Comparator.comparing(Folder::changed).reversed()
                .thenComparing(Folder::name));
        return new ReviewListing(folders, unreadable);
    }

    /**
     * What Sluice wrote beside the photos in one folder, line by line.
     *
     * <p>Every note in the folder, in filename order, run together. A Review folder keeps one file
     * naming each photo and why it is there. A Duplicates folder keeps one per near-duplicate group
     * instead, so a folder holding several groups answers with several notes.
     *
     * @param folder {@link Path} a folder this listing named
     * @return a {@link List} of {@link String} the lines, empty where nothing was written
     * @throws IllegalArgumentException if folder is not under one of the three roots
     */
    List<String> notesIn(final Path folder) {
        final Path asked = folder.normalize();
        if (this.rootHolding(asked).isEmpty()) {
            throw new IllegalArgumentException("folder must be under a review root: " + folder);
        }
        return this.filesIn(asked).stream()
                .filter(file -> file.getFileName().toString().endsWith(NOTE_SUFFIX))
                .sorted()
                .flatMap(file -> this.media.readLines(file).stream())
                .toList();
    }

    /**
     * Adds one root's folders to the listing, or records that it could not be read.
     *
     * <p>A root that is not there yet is neither. Nothing has ever set a photo aside, which is the
     * ordinary state of a fresh install rather than a failure to report.
     *
     * <p>A directory has to be confirmed rather than merely something existing there. A walk of a
     * path that is a file answers with that file, whose own folder is the working root. Relativized
     * against this root, that produces a name climbing out of it.
     *
     * <p>A root the operating system refuses at the root itself reads as absent rather than as
     * unreadable. {@code realDirectory} answers empty for a denied permission, for a share that has
     * gone away and for a path naming nothing. No port method here separates the three. A refusal
     * one level down is caught, since walking it throws.
     *
     * @param root {@link Root} which root to read
     * @param folders a {@link List} of {@link Folder} collected so far, added to
     * @param unreadable a {@link List} of {@link Path} the roots that failed, added to
     */
    private void collect(final Root root, final List<Folder> folders, final List<Path> unreadable) {
        final Path rootPath = this.pathOf(root);
        try {
            if (this.media.realDirectory(rootPath).isPresent()) {
                this.byFolder(rootPath).forEach((dir, files) ->
                        folders.add(this.folder(root, rootPath, dir, files)));
            }
        } catch (final RuntimeException e) {
            log.warn("Could not read {}", rootPath, e);
            unreadable.add(rootPath);
        }
    }

    /**
     * Every file under a root, grouped by the folder that directly holds it.
     *
     * <p>Grouping a whole-tree walk rather than listing subdirectories, because the three roots do
     * not agree on how deep their folders sit. Grouping by parent lands on the level holding files
     * whatever that depth is. It also skips an intermediate year folder that holds only months.
     *
     * <p>A file lying loose in the root itself belongs to no folder, so it is left out. Nothing this
     * app writes puts one there.
     *
     * @param rootPath {@link Path} the root to walk
     * @return a {@link Map} of {@link Path} to a {@link List} of {@link Path}, files by folder
     */
    private Map<Path, List<Path>> byFolder(final Path rootPath) {
        final Map<Path, List<Path>> byFolder = new LinkedHashMap<>();
        this.media.listFiles(rootPath).stream()
                .filter(file -> !MediaStore.isIncompleteTransfer(file))
                .filter(file -> file.getParent() != null && !file.getParent().equals(rootPath))
                .forEach(file -> byFolder.computeIfAbsent(file.getParent(), _ -> new ArrayList<>()).add(file));
        return byFolder;
    }

    /**
     * One folder as the listing describes it.
     *
     * @param root {@link Root} which root it sits under
     * @param rootPath {@link Path} that root's own directory
     * @param dir {@link Path} the folder itself
     * @param files a {@link List} of {@link Path} everything directly in it
     * @return {@link Folder} the row
     */
    private Folder folder(final Root root, final Path rootPath, final Path dir, final List<Path> files) {
        final List<MediaType> kinds = files.stream()
                .map(this.mediaTypeDetector::classify)
                .flatMap(Optional::stream)
                .toList();
        final int photos = (int) kinds.stream().filter(MediaType.PHOTO::equals).count();
        final int videos = (int) kinds.stream().filter(MediaType.VIDEO::equals).count();
        final String name = slashed(rootPath.relativize(dir));
        return new Folder(root, filedBy(root, name), name, dir, photos, videos, this.changed(dir));
    }

    /**
     * Which job filed a folder, read off the name it was given.
     *
     * <p>Only the Review root holds both. {@link SortFolderNames} holds the two names a sort writes
     * there, and a category may not take either. Anything else under that root was named by
     * somebody, whether as a category or by hand.
     *
     * <p>Read off the name rather than looked up against the configured categories. A category
     * renamed or switched off after its folder was filled would otherwise move into the wrong
     * place.
     *
     * @param root {@link Root} which root the folder sits under
     * @param name {@link String} the folder's name below that root
     * @return {@link FiledBy} the job that put it there
     */
    private static FiledBy filedBy(final Root root, final String name) {
        return root == Root.REVIEW && SortFolderNames.writtenByASort(name)
                ? FiledBy.A_SORT
                : FiledBy.A_SIFT;
    }

    /**
     * When a folder was last written to, or the epoch where the filesystem would not say.
     *
     * @param dir {@link Path} the folder
     * @return {@link Instant} when it changed, or {@link Instant#EPOCH} where that is not known
     */
    private Instant changed(final Path dir) {
        try {
            return this.media.lastModifiedTime(dir);
        } catch (final RuntimeException e) {
            log.warn("Could not read the age of {}", dir, e);
            return Instant.EPOCH;
        }
    }

    /**
     * The files in a folder, or none where it has gone since it was listed.
     *
     * @param dir {@link Path} the folder
     * @return a {@link List} of {@link Path} the files in it
     */
    private List<Path> filesIn(final Path dir) {
        return this.media.exists(dir) ? this.media.listFiles(dir) : List.of();
    }

    /**
     * Which root a path sits under, or empty where none of them does.
     *
     * <p>Normalized before the comparison rather than matched for {@code ..}. A legitimately dotted
     * folder name would trip a string match, and a longer traversal could dodge one.
     *
     * @param path {@link Path} the path to place
     * @return an {@link Optional} of {@link Root} the root holding it
     */
    private Optional<Root> rootHolding(final Path path) {
        return Arrays.stream(Root.values())
                .filter(root -> path.startsWith(this.pathOf(root).normalize()))
                .findFirst();
    }

    /**
     * Where one root is configured.
     *
     * @param root {@link Root} which root
     * @return {@link Path} its directory
     */
    private Path pathOf(final Root root) {
        return switch (root) {
            case REVIEW -> this.paths.review();
            case DUPLICATES -> this.paths.duplicates();
            case UNREVIEWABLE -> this.paths.unreviewable();
        };
    }

    /**
     * A relative path written the one way, whatever the platform separates with.
     *
     * <p>The name reaches a screen and, under Review, {@code RescueUseCase.rescue}, which resolves
     * it again. A resolve takes either separator, so the choice is only about what a reader sees.
     *
     * @param relative {@link Path} the path below a root
     * @return {@link String} it written with forward slashes
     */
    private static String slashed(final Path relative) {
        final List<String> parts = new ArrayList<>();
        relative.forEach(part -> parts.add(part.toString()));
        return String.join("/", parts);
    }
}
