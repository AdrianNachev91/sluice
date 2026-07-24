package photos.sluice.adapter.imaging;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.MontageRenderer;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.domain.cull.CullCandidate;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.CullScopeSelector;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Wires TileRenderer + MontageBuilder + SidecarWriter + PrepIndexWriter behind the MontageRenderer
// port. Resolves a scope to Sorted photo files, renders each to a tile, and drops unreviewable ones
// before batching the rest into montages and writing the whole prep dir. MontageBuilder itself never
// sees the unreviewable flag, so this is the only place left that can act on it.
@Component
public class CullMontageRenderer implements MontageRenderer {

    // WhatsApp's received-photo filename convention. A reliable non-vision prior for received
    // clutter (documents, screenshots, memes, product shots) - not auto-junk, just a signal for the
    // culler to scrutinize harder, at zero extra image-token cost.
    private static final Pattern RECEIVED_PATTERN = Pattern.compile("^IMG-\\d{8}-WA\\d", Pattern.CASE_INSENSITIVE);

    private final TileRenderer tileRenderer;
    private final MontageBuilder montageBuilder;
    private final SidecarWriter sidecarWriter;
    private final PrepIndexWriter prepIndexWriter;
    private final MediaStore mediaStore;
    private final PathsPort pathsPort;
    private final CullScopeSelector cullScopeSelector = new CullScopeSelector();
    private final MediaTypeDetector mediaTypeDetector = new MediaTypeDetector();

    public CullMontageRenderer(TileRenderer tileRenderer, MontageBuilder montageBuilder,
            SidecarWriter sidecarWriter, PrepIndexWriter prepIndexWriter, MediaStore mediaStore,
            PathsPort pathsPort) {
        this.tileRenderer = tileRenderer;
        this.montageBuilder = montageBuilder;
        this.sidecarWriter = sidecarWriter;
        this.prepIndexWriter = prepIndexWriter;
        this.mediaStore = mediaStore;
        this.pathsPort = pathsPort;
    }

    // Pairs a candidate with its rendered tile, before the unreviewable filter runs.
    private record RenderedCandidate(CullCandidate candidate, TileRenderer.TileResult tile) {
    }

    @Override
    public PrepDir build(CullScope scope, MontageConfig config) {
        return build(scope, config, ProgressCallback.NO_OP);
    }

    @Override
    public PrepDir build(CullScope scope, MontageConfig config, ProgressCallback progress) {
        // Ordering happens before rendering. Batch boundaries (which photos land in montage-001 vs
        // montage-002) must be decided from the full candidate list, not from however MediaStore
        // happened to return files from disk.
        Path photosRoot = pathsPort.sorted().resolve("Photos");
        List<CullCandidate> ordered =
                cullScopeSelector.order(collectCandidates(photosRoot, scope), scope);

        // Render every candidate's tile up front and split off the unreviewable ones here, before
        // any batching decision is made. MontageBuilder composes whatever list it's handed with no
        // concept of "skip this one". Once photos are grouped into a montage, there's no way to pull
        // one back out without reshuffling every batch after it. Splitting first means batch
        // boundaries are only ever computed over photos that will actually appear. Unreviewable
        // files are never moved here (see PrepDir's own doc comment). They're only reported, so a
        // caller has something to act on instead of the file just silently staying wherever it
        // already is.
        List<RenderedCandidate> rendered = ordered.stream()
                .map(candidate -> new RenderedCandidate(candidate, tileRenderer.render(candidate.path(), config.tileSize())))
                .toList();
        List<RenderedCandidate> reviewable = rendered.stream()
                .filter(candidate -> !candidate.tile().unreviewable())
                .toList();
        List<Path> unreviewable = rendered.stream()
                .filter(candidate -> candidate.tile().unreviewable())
                .map(candidate -> candidate.candidate().path())
                .toList();

        // logs/cull-prep/<scopeTag> is cleared before writing, not appended to. A prior run of the
        // same scope may have produced more montages than this run does, if fewer photos are
        // reviewable this time around. A stale montage-002.* from that prior run would otherwise
        // survive alongside this run's smaller output, with nothing to indicate it's no longer
        // current.
        String scopeTag = scopeTag(scope);
        Path prepDir = pathsPort.logs().resolve("cull-prep").resolve(scopeTag);
        clearPrepDir(prepDir);
        mediaStore.ensureDirectory(prepDir);

        int tilesPerMontage = config.tilesPerRow() * config.tilesPerRow();
        int totalMontages = (reviewable.size() + tilesPerMontage - 1) / tilesPerMontage;
        List<String> entries = new ArrayList<>();
        for (int start = 0; start < reviewable.size(); start += tilesPerMontage) {
            int end = Math.min(start + tilesPerMontage, reviewable.size());
            // entries.size() + 1, not (start / tilesPerMontage) + 1. Both give the same number
            // today, but entries.size() stays correct even if a future change makes montages
            // variable-sized rather than a fixed tilesPerMontage each.
            String tag = "montage-%03d".formatted(entries.size() + 1);
            writeMontage(prepDir, tag, reviewable.subList(start, end), config);
            entries.add(tag);
            progress.tick(entries.size(), totalMontages);
        }

        // photos reports reviewable.size(), not the raw count found in scope. An unreviewable file
        // never appears in any montage or sidecar, so counting it here would make this number
        // disagree with what a caller can actually see on disk.
        var result = new PrepDir(
                scopeTag,
                cullScopeSelector.basePath(photosRoot, scope),
                reviewable.size(),
                unreviewable,
                entries.size(),
                prepDir,
                entries);
        prepIndexWriter.write(prepDir.resolve("index.json"), result);
        return result;
    }

    private List<CullCandidate> collectCandidates(Path photosRoot, CullScope scope) {
        return cullScopeSelector.directoriesToScan(photosRoot, scope).stream()
                // A requested month directory may not exist (e.g. no photos ever landed there) -
                // skipped silently rather than treated as an error.
                .filter(mediaStore::exists)
                .flatMap(dir -> mediaStore.listFiles(dir).stream())
                .filter(file -> mediaTypeDetector.classify(file).filter(MediaType.PHOTO::equals).isPresent())
                .map(file -> new CullCandidate(file, mtimeOf(file)))
                .toList();
    }

    private void writeMontage(Path prepDir, String tag, List<RenderedCandidate> batch, MontageConfig config) {
        List<MontageBuilder.MontageTile> tiles = batch.stream()
                .map(rendered -> new MontageBuilder.MontageTile(
                        rendered.tile().image(), rendered.candidate().path().getFileName().toString()))
                .toList();
        BufferedImage canvas = montageBuilder.compose(tiles, config);
        Path montageFile = prepDir.resolve(tag + ".jpg");
        try {
            ImageIO.write(canvas, "jpg", montageFile.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write montage " + montageFile, e);
        }

        List<SidecarPhotoEntry> photos = batch.stream()
                .map(rendered -> new SidecarPhotoEntry(
                        rendered.candidate().path(),
                        rendered.candidate().path().getFileName().toString(),
                        rendered.candidate().mtime(),
                        isReceived(rendered.candidate().path())))
                .toList();
        sidecarWriter.write(prepDir.resolve(tag + ".json"), montageFile, photos);
    }

    private static boolean isReceived(Path path) {
        return RECEIVED_PATTERN.matcher(path.getFileName().toString()).find();
    }

    private static Instant mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read mtime of " + file, e);
        }
    }

    private static String scopeTag(CullScope scope) {
        return switch (scope) {
            case CullScope.Year(int year, List<Integer> months) -> yearTag(year, months);
            case CullScope.OldestN(int n) -> "oldest-" + n;
        };
    }

    private static String yearTag(int year, @Nullable List<Integer> months) {
        if (months == null) {
            return String.valueOf(year);
        }
        String monthSuffix = months.stream()
                .distinct()
                .sorted()
                .map("%02d"::formatted)
                .collect(Collectors.joining("-"));
        return year + "-" + monthSuffix;
    }

    // logs/cull-prep/<scopeTag> is a directory this feature exclusively generates and owns. That's
    // different from Inbox/Sorted/Review/Duplicates, which the project's media-safety invariant
    // protects from bulk deletes. Wiping and regenerating it is safe, so a rerun with fewer photos
    // doesn't leave stale montage files behind from a prior larger run.
    private static void clearPrepDir(Path prepDir) {
        if (!Files.exists(prepDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(prepDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(CullMontageRenderer::deleteQuietly);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clear stale prep dir " + prepDir, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + path, e);
        }
    }
}
