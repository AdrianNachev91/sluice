package photos.sluice.domain.cull;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.time.Instant;

/**
 * One cull run as it currently sits on disk: which scope it belongs to, how
 * {@link photos.sluice.application.service.PrepDirDoctor#diagnose} reads it, and how far its shards
 * have got. A dashboard renders one card per summary, and the unresolved-run banner names the ones
 * that still need somebody.
 *
 * <p>There is no persistent job store. The whole list is derived by enumerating the cull-prep root
 * and diagnosing each prep dir in it. Enumerating is what makes a damaged run visible. A run whose
 * index cannot be read is the one most in need of attention. Any derivation starting from that
 * index would drop exactly that run.
 *
 * <p>{@code since} is the prep dir's own mtime, not a stored creation timestamp. So a run whose
 * index cannot be read still has one. A dir nobody can even stat falls back to the epoch, which
 * sorts it to the top of a list ordered by neglect.
 *
 * @param scope {@link String} the scope tag, read off the prep dir's own folder name
 * @param prepDir {@link Path} the prep dir, and the run's own identity for a resume or a discard
 * @param health {@link PrepDirHealth} the diagnosis, including every currently open finding
 * @param shards {@link ShardTally} present/valid/total shard counts, null whenever the diagnosis
 *         did not get far enough to compute one. Three states reach that: DAMAGED, the CorruptIndex
 *         form of BLOCKED, and COMPLETE. Check for null rather than deriving it from the state
 * @param since {@link Instant} when the prep dir was last written to, or the epoch if it could not
 *         be read
 */
public record CullRunSummary(String scope, Path prepDir, PrepDirHealth health, @Nullable ShardTally shards,
                             Instant since) {
}
