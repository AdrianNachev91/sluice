package photos.sluice.adapter.imaging;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.MontageRenderer;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.domain.cull.CullCandidate;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.CullScopeSelector;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.MediaType;
import photos.sluice.domain.scan.MediaTypeDetector;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A {@link MontageRenderer} that wires {@link TileRenderer}, {@link MontageBuilder},
 * {@link SidecarWriter}, and {@link PrepIndexWriter} together. It resolves a cull scope to
 * {@code Sorted} photo files and renders each to a tile. It drops unreviewable ones before
 * batching the rest into montages and writing the whole prep directory.
 *
 * <p>{@link MontageBuilder} itself never sees the unreviewable flag. This is the only place that
 * acts on it.
 */
@Component
public class CullMontageRenderer implements MontageRenderer {

    // WhatsApp's received-photo filename convention. A reliable non-vision prior for received
    // clutter (documents, screenshots, memes, product shots), not auto-junk. Just a signal for the
    // culler to scrutinize harder, at zero extra image-token cost.
    private static final Pattern RECEIVED_PATTERN = Pattern.compile("^IMG-\\d{8}-WA\\d", Pattern.CASE_INSENSITIVE);

    private final TileRenderer tileRenderer;
    private final MontageBuilder montageBuilder;
    private final SidecarWriter sidecarWriter;
    private final PrepIndexWriter prepIndexWriter;
    private final MediaStore mediaStore;
    private final PathsPort pathsPort;
    private final CullSettings cullSettings;
    private final CullScopeSelector cullScopeSelector = new CullScopeSelector();
    private final MediaTypeDetector mediaTypeDetector = new MediaTypeDetector();

    /**
     * Creates a montage renderer wired to its rendering and I/O collaborators.
     *
     * @param tileRenderer {@link TileRenderer} renders each candidate to a tile image
     * @param montageBuilder {@link MontageBuilder} composes tiles into a montage canvas
     * @param sidecarWriter {@link SidecarWriter} writes the per-montage sidecar metadata
     * @param prepIndexWriter {@link PrepIndexWriter} writes the prep dir's index file
     * @param mediaStore {@link MediaStore} lists and checks files on disk
     * @param pathsPort {@link PathsPort} resolves the Sorted and logs roots
     * @param cullSettings {@link CullSettings} supplies the category set this run is stamped with
     */
    public CullMontageRenderer(final TileRenderer tileRenderer, final MontageBuilder montageBuilder,
                               final SidecarWriter sidecarWriter, final PrepIndexWriter prepIndexWriter,
                               final MediaStore mediaStore,
                               final PathsPort pathsPort,
                               final CullSettings cullSettings) {
        this.tileRenderer = tileRenderer;
        this.montageBuilder = montageBuilder;
        this.sidecarWriter = sidecarWriter;
        this.prepIndexWriter = prepIndexWriter;
        this.mediaStore = mediaStore;
        this.pathsPort = pathsPort;
        this.cullSettings = cullSettings;
    }

    /**
     * Pairs a candidate with its rendered tile, before the unreviewable filter runs.
     */
    private record RenderedCandidate(CullCandidate candidate, TileRenderer.TileResult tile) {
    }

    /**
     * Builds a prep dir for the given scope using the default progress callback.
     *
     * @param scope {@link CullScope} the cull scope to render
     * @param config {@link MontageConfig} the montage layout configuration
     * @return {@link PrepDir} the resulting prep dir
     */
    @Override
    public PrepDir build(final CullScope scope, final MontageConfig config) {
        return this.build(scope, config, ProgressCallback.NO_OP);
    }

    /**
     * Builds a prep dir for the given scope, reporting progress as montages are written.
     *
     * @param scope {@link CullScope} the cull scope to render
     * @param config {@link MontageConfig} the montage layout configuration
     * @param progress {@link ProgressCallback} callback notified as each montage completes
     * @return {@link PrepDir} the resulting prep dir
     */
    @Override
    public PrepDir build(final CullScope scope, final MontageConfig config, final ProgressCallback progress) {
        // NEVER never trips, so the cancellation-aware overload below always runs to completion and
        // returns non-null here - this just asserts that rather than silently trusting it.
        return Objects.requireNonNull(this.build(scope, config, progress, CancellationSignal.NEVER));
    }

    /**
     * Builds a prep dir for the given scope, checking for cancellation between candidates and
     * between montages.
     *
     * @param scope {@link CullScope} the cull scope to render
     * @param config {@link MontageConfig} the montage layout configuration
     * @param progress {@link ProgressCallback} callback notified as each montage completes
     * @param cancellation {@link CancellationSignal} signal checked between rendering and writing
     * steps
     * @return {@link PrepDir} the resulting prep dir, or null if cancelled before completion
     */
    @Override
    public @Nullable PrepDir build(final CullScope scope, final MontageConfig config, final ProgressCallback progress,
                                   final CancellationSignal cancellation) {
        // Ordering happens before rendering. Batch boundaries (which photos land in montage-001 vs
        // montage-002) must be decided from the full candidate list, not from however MediaStore
        // happened to return files from disk.
        final Path photosRoot = this.pathsPort.sorted().resolve("Photos");
        final List<CullCandidate> ordered =
                this.cullScopeSelector.order(this.collectCandidates(photosRoot, scope), scope);

        // Render every candidate's tile up front and split off the unreviewable ones here, before
        // any batching decision is made. MontageBuilder composes whatever list it's handed with no
        // concept of "skip this one". Once photos are grouped into a montage, there's no way to pull
        // one back out without reshuffling every batch after it. Splitting first means batch
        // boundaries are only ever computed over photos that will actually appear. Unreviewable
        // files are never moved here (see PrepDir's own doc comment). They're only reported, so a
        // caller has something to act on instead of the file just silently staying wherever it
        // already is.
        //
        // This is the long pass (one HEIC CLI decode per candidate), and it runs entirely before
        // clearPrepDir() below. A cancellation seen here leaves disk fully untouched - there is no
        // partial prep dir for a caller to resume from.
        final List<RenderedCandidate> rendered = new ArrayList<>();
        for (final CullCandidate candidate : ordered) {
            if (cancellation.isCancelled()) {
                return null;
            }
            rendered.add(new RenderedCandidate(candidate, this.tileRenderer.render(candidate.path(),
                    config.tileSize())));
        }
        final List<RenderedCandidate> reviewable = rendered.stream()
                .filter(candidate -> !candidate.tile().unreviewable())
                .toList();
        final List<Path> unreviewable = rendered.stream()
                .filter(candidate -> candidate.tile().unreviewable())
                .map(candidate -> candidate.candidate().path())
                .toList();

        // logs/cull-prep/<scopeTag> is cleared before writing, not appended to. A prior run of the
        // same scope may have produced more montages than this run does, if fewer photos are
        // reviewable this time around. A stale montage-002.* from that prior run would otherwise
        // survive alongside this run's smaller output, with nothing to indicate it's no longer
        // current.
        final String scopeTag = CullScope.tag(scope);
        final Path prepDir = this.pathsPort.logs().resolve("cull-prep").resolve(scopeTag);
        clearPrepDir(prepDir);
        this.mediaStore.ensureDirectory(prepDir);

        final int tilesPerMontage = config.tilesPerRow() * config.tilesPerRow();
        final int totalMontages = (reviewable.size() + tilesPerMontage - 1) / tilesPerMontage;
        final List<String> entries = new ArrayList<>();
        for (int start = 0; start < reviewable.size(); start += tilesPerMontage) {
            // Checked per montage. Whatever montages this run wrote before stopping are cleared
            // again on the way out, so a cancelled render leaves no directory behind at all. A
            // scope is occupied by any prep dir holding files, and a half-rendered one holds
            // nothing worth occupying it with. No index.json was ever written, so nothing here
            // records a single decision. The images cost only the time to render them again.
            if (cancellation.isCancelled()) {
                clearPrepDir(prepDir);
                return null;
            }
            final int end = Math.min(start + tilesPerMontage, reviewable.size());
            // entries.size() + 1, not (start / tilesPerMontage) + 1. Both give the same number
            // today, but entries.size() stays correct even if a future change makes montages
            // variable-sized rather than a fixed tilesPerMontage each.
            final String tag = MontageNaming.montageIdFor(entries.size() + 1);
            this.writeMontage(prepDir, tag, reviewable.subList(start, end), config);
            entries.add(tag);
            progress.tick(entries.size(), totalMontages);
        }

        // photos reports reviewable.size(), not the raw count found in scope. An unreviewable file
        // never appears in any montage or sidecar. Counting it here would make this number
        // disagree with what a caller can actually see on disk.
        //
        // The category set is read once, here, and travels with the run from now on. This is the
        // last moment it is a live value rather than a recorded one.
        final var result = new PrepDir(
                scopeTag,
                this.cullSettings.categories(),
                this.cullScopeSelector.basePath(photosRoot, scope),
                reviewable.size(),
                unreviewable,
                entries.size(),
                prepDir,
                entries);
        this.prepIndexWriter.write(prepDir.resolve("index.json"), result);
        return result;
    }

    /**
     * Collects photo candidates from the directories the scope selects.
     *
     * @param photosRoot {@link Path} the root of the Sorted photos tree
     * @param scope {@link CullScope} the cull scope determining which directories to scan
     * @return a {@link List} of {@link CullCandidate}, the candidates found, unordered
     */
    private List<CullCandidate> collectCandidates(final Path photosRoot, final CullScope scope) {
        return this.cullScopeSelector.directoriesToScan(photosRoot, scope).stream()
                // A requested month directory may not exist (e.g. no photos ever landed there) -
                // skipped silently rather than treated as an error.
                .filter(this.mediaStore::exists)
                .flatMap(dir -> this.mediaStore.listFiles(dir).stream())
                .filter(file -> this.mediaTypeDetector.classify(file).filter(MediaType.PHOTO::equals).isPresent())
                .map(file -> new CullCandidate(file, mtimeOf(file)))
                .toList();
    }

    /**
     * Composes a batch of rendered candidates into one montage image and its sidecar.
     *
     * @param prepDir {@link Path} the prep dir to write into
     * @param tag {@link String} the montage's file-name tag
     * @param batch a {@link List} of {@link RenderedCandidate}, the rendered candidates to include
     * @param config {@link MontageConfig} the montage layout configuration
     */
    private void writeMontage(final Path prepDir, final String tag, final List<RenderedCandidate> batch,
                              final MontageConfig config) {
        final List<MontageBuilder.MontageTile> tiles = batch.stream()
                .map(rendered -> new MontageBuilder.MontageTile(
                        rendered.tile().image(), rendered.candidate().path().getFileName().toString()))
                .toList();
        final BufferedImage canvas = this.montageBuilder.compose(tiles, config);
        final Path montageFile = prepDir.resolve(tag + ".jpg");
        try {
            ImageIO.write(canvas, "jpg", montageFile.toFile());
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write montage " + montageFile, e);
        }

        final List<SidecarPhotoEntry> photos = batch.stream()
                .map(rendered -> new SidecarPhotoEntry(
                        rendered.candidate().path(),
                        rendered.candidate().path().getFileName().toString(),
                        rendered.candidate().mtime(),
                        isReceived(rendered.candidate().path())))
                .toList();
        this.sidecarWriter.write(prepDir.resolve(tag + ".json"), montageFile, photos);
    }

    /**
     * Checks whether a file name matches WhatsApp's received-photo naming convention.
     *
     * @param path {@link Path} the candidate file path
     * @return boolean true if the file name looks like a WhatsApp-received photo
     */
    private static boolean isReceived(final Path path) {
        return RECEIVED_PATTERN.matcher(path.getFileName().toString()).find();
    }

    /**
     * Reads a file's last-modified time.
     *
     * @param file {@link Path} the file to inspect
     * @return {@link Instant} the file's last-modified instant
     */
    private static Instant mtimeOf(final Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read mtime of " + file, e);
        }
    }

    /**
     * {@code logs/cull-prep/<scopeTag>} is a directory this feature exclusively generates and
     * owns. That's different from Inbox/Sorted/Review/Duplicates, which the project's
     * media-safety invariant protects from bulk deletes. Wiping and regenerating it is safe, so a
     * rerun with fewer photos doesn't leave stale montage files behind from a prior larger run.
     *
     * @param prepDir {@link Path} the prep dir to clear
     */
    private static void clearPrepDir(final Path prepDir) {
        if (!Files.exists(prepDir)) {
            return;
        }
        try (final Stream<Path> walk = Files.walk(prepDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(CullMontageRenderer::deleteQuietly);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to clear stale prep dir " + prepDir, e);
        }
    }

    /**
     * Deletes a single file, letting any failure propagate as unchecked.
     *
     * @param path {@link Path} the file to delete
     */
    private static void deleteQuietly(final Path path) {
        try {
            Files.delete(path);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to delete " + path, e);
        }
    }
}
