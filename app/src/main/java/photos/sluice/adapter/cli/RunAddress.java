package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * Turns what a caller typed to name one sift into the folder that sift lives in.
 *
 * <p>Two spellings. The scope tag, which is what {@code runs} prints in its first column. And a
 * full path, for a caller that has one in hand.
 *
 * <p>A tag is matched against the sifts that are there rather than pieced into a path. So a tag
 * naming no sift is refused here, with the ones that do exist named. And nothing here has to know
 * how a tag becomes a folder name, which is the domain's own business and would drift if it were
 * written twice.
 *
 * <p>A path is passed on untouched, because what may be worked on is the facade's ruling rather
 * than this adapter's.
 */
@Component
@Profile("cli")
public class RunAddress {

    private final Pipeline pipeline;

    /**
     * Creates the resolver.
     *
     * @param pipeline {@link Pipeline} enumerates the sifts a tag is matched against
     */
    public RunAddress(final Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * The folder one address names.
     *
     * @param address {@link String} the scope tag or path the caller typed
     * @return {@link Path} the folder that sift lives in
     * @throws ScopeRefusedException when no sift answers to it, or the folder holding them could
     *         not be read
     */
    public Path folderFor(final String address) {
        final Path path = pathOrNull(address);
        return path == null ? this.tagged(address) : path;
    }

    /**
     * The folder the sift with this tag lives in.
     *
     * @param tag {@link String} the scope tag the caller typed
     * @return {@link Path} that sift's folder
     * @throws ScopeRefusedException when no sift carries the tag, or the folder holding them could
     *         not be read
     */
    private Path tagged(final String tag) {
        return switch (this.pipeline.cullRuns()) {
            case CullRuns.Listed(final List<CullRunSummary> runs) -> matched(tag, runs);
            case CullRuns.Unlistable(final Path root) -> throw new ScopeRefusedException(
                    RunsRefusals.unreadable(root));
        };
    }

    /**
     * The folder of the one sift carrying this tag.
     *
     * @param tag {@link String} the scope tag the caller typed
     * @param runs a {@link List} of {@link CullRunSummary} every sift that is there
     * @return {@link Path} that sift's folder
     * @throws ScopeRefusedException when none of them carries the tag
     */
    private static Path matched(final String tag, final List<CullRunSummary> runs) {
        return runs.stream()
                .filter(run -> run.scope().equals(tag))
                .findFirst()
                .map(CullRunSummary::prepDir)
                .orElseThrow(() -> notFound(tag, runs));
    }

    /**
     * The path an address spells out, or null where it is a tag rather than a path.
     *
     * <p>Every tag the domain builds is a bare folder name, so no tag can be read as a path.
     *
     * @param address {@link String} the scope tag or path the caller typed
     * @return {@link Path} the path it names, or null
     */
    private static @Nullable Path pathOrNull(final String address) {
        try {
            final Path path = Path.of(address);
            return path.isAbsolute() ? path : null;
        } catch (final InvalidPathException notAPath) {
            return null;
        }
    }

    /**
     * The refusal for an address no sift answers to.
     *
     * @param tag {@link String} the scope tag the caller typed
     * @param runs a {@link List} of {@link CullRunSummary} every sift that is there
     * @return {@link ScopeRefusedException} the refusal to throw
     */
    private static ScopeRefusedException notFound(final String tag, final List<CullRunSummary> runs) {
        final List<String> tags = runs.stream().map(CullRunSummary::scope).toList();
        final String remedy = tags.isEmpty() ? "There are none yet."
                : "These are on disk: " + String.join(", ", tags) + ".";
        return new ScopeRefusedException(new Refusal(RefusalKind.RUN_NOT_FOUND,
                "No sift called " + Refusal.shown(tag) + ". " + remedy,
                Fields.of("address", tag, "known", tags)));
    }
}
