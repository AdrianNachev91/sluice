package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.util.List;

// A single problem found while validating a prep directory. Either a shard-contract violation
// ShardValidator checks for, or one of ApplyEngine's own batch-level checks - a stray shard, a
// decision whose file can't be accounted for. describe() renders the exact prose an aggregated
// ApplyException reports; callers read that rendered text, never a Finding's fields directly.
// remedy() classifies how (if at all) PrepDirDoctor's troubleshooter can resolve the finding on
// its own. AUTO is safe to apply unprompted. CHOICE means the user picks from enumerated options.
// NONE is informational only - a culler content mistake that only a re-cull can fix.
public sealed interface Finding {

    /**
     * Renders this finding as the exact prose an aggregated ApplyException reports.
     *
     * @return {@link String} the finding's human-readable description
     */
    String describe();

    /**
     * How this finding can be resolved. Defaults to NONE; only a finding with a real repair path
     * overrides it.
     *
     * @return {@link Remedy} this finding's remedy classification
     */
    default Remedy remedy() {
        return Remedy.NONE;
    }

    // AUTO: non-destructive and free, safe for the troubleshooter to apply unprompted. CHOICE:
    // resolving it loses work or money, so the user picks from enumerated options. NONE:
    // informational only, no engine-level repair exists.
    enum Remedy {
        AUTO, CHOICE, NONE
    }

    record MissingMontageField(String montage) implements Finding {
        @Override
        public String describe() {
            return montage + ": missing 'montage'";
        }
    }

    record MontageFieldMismatch(String montage, String declared) implements Finding {
        @Override
        public String describe() {
            return montage + ": 'montage' is '" + declared + "', expected '" + montage + "'";
        }
    }

    record InvalidCategory(String montage, int index, String category, String allowedClause) implements Finding {
        @Override
        public String describe() {
            return at(montage, index) + ": invalid action '" + category + "' (" + allowedClause + ")";
        }
    }

    record MissingReason(String montage, int index) implements Finding {
        @Override
        public String describe() {
            return at(montage, index) + ": missing 'reason'";
        }
    }

    record MissingGroup(String montage, int index) implements Finding {
        @Override
        public String describe() {
            return at(montage, index) + ": missing 'group'";
        }
    }

    record MissingChosenReason(String montage, int index) implements Finding {
        @Override
        public String describe() {
            return at(montage, index) + ": missing 'chosen_reason'";
        }
    }

    record WrongChosenCount(String montage, String group, int chosen) implements Finding {
        @Override
        public String describe() {
            return montage + ": near-dup group '" + group + "' has " + chosen + " chosen (need exactly 1)";
        }
    }

    record TooFewRejects(String montage, String group, int rejects) implements Finding {
        @Override
        public String describe() {
            return montage + ": near-dup group '" + group + "' has " + rejects + " reject(s) (need >=1)";
        }
    }

    record InvalidGroupSlug(String montage, String group, int maxLength) implements Finding {
        @Override
        public String describe() {
            return montage + ": near-dup group '" + group
                    + "' is not a valid slug (lowercase a-z0-9, hyphenated, max " + maxLength + " chars)";
        }
    }

    record DuplicateFileReference(String file, long count) implements Finding {
        @Override
        public String describe() {
            return "file listed " + count + " times across shards/unreviewable: " + file;
        }
    }

    /**
     * A file listed both as a decision (in some montage's shard) and in index.json's own
     * unreviewable list - the one duplicate-reference shape common and specific enough for a
     * troubleshooter to offer a real choice, unlike the more general {@link DuplicateFileReference}.
     * CHOICE because either resolution changes which pile the file ends up in, and the engine cannot
     * decide that on its own. "Trust the decision" means the shard's verdict applies, and the file is
     * no longer treated as unreviewable. "Treat as unreviewable" means the decision is dropped, and
     * the file stays put, unreviewed.
     */
    record DecisionUnreviewableOverlap(Decision decision) implements Finding {
        @Override
        public String describe() {
            return "file listed both as a decision and as unreviewable: " + decision.file();
        }

        @Override
        public Remedy remedy() {
            return Remedy.CHOICE;
        }
    }

    record GroupSpansMultipleMontages(String group, List<String> montages) implements Finding {
        public GroupSpansMultipleMontages {
            montages = List.copyOf(montages);
        }

        @Override
        public String describe() {
            return "near-dup group '" + group + "' spans " + montages.size() + " shards ("
                    + String.join(", ", montages) + "); a group must stay within one montage";
        }
    }

    record MissingFile(String montage, int index) implements Finding {
        @Override
        public String describe() {
            return at(montage, index) + ": missing 'file'";
        }
    }

    record FileOutOfScope(String montage, int index, Path file) implements Finding {
        @Override
        public String describe() {
            return at(montage, index) + ": file out of scope: " + file;
        }
    }

    // ApplyEngine's own batch-level checks, below - real failure paths beyond the per-decision
    // shard contract ShardValidator checks above.

    /**
     * A decisions-NNN.json file with no montage entry expecting it - almost always a culler
     * numbering slip. Always reports AUTO: this record is a pure value with no I/O, built before
     * any lookup of which montages are currently unclaimed, so it cannot itself know whether the
     * repair will turn out ambiguous. The real decision is made when the repair actually runs -
     * {@link photos.sluice.application.service.ApplyEngine#autoRepairStrayShard} renames the shard
     * into the one montage left unclaimed when that's unambiguous, or leaves it untouched
     * otherwise, for {@link photos.sluice.application.service.ApplyEngine#setAsideStrayShard}'s own
     * CHOICE fallback.
     */
    record StrayShard(String shardFile) implements Finding {
        @Override
        public String describe() {
            return shardFile + ": no matching montage";
        }

        @Override
        public Remedy remedy() {
            return Remedy.AUTO;
        }
    }

    /**
     * A montage entry with no shard yet, when a caller has asked not to accept a partial run.
     * NONE because there is nothing to auto-repair - the montage genuinely hasn't been culled.
     */
    record MissingShard(String montage, String expectedFile) implements Finding {
        @Override
        public String describe() {
            return montage + ": no shard " + expectedFile;
        }
    }

    /**
     * The prep directory's own index.json cannot be parsed - missing, truncated, or not valid JSON.
     * Always reports AUTO, the same reasoning {@link StrayShard} already relies on: index.json is a
     * derived summary (scope, base path, montage entries, photo/montage counts), reconstructible
     * from the surviving sidecars and the prep dir's own location - everything except the
     * unreviewable list, which is genuinely lost. {@link
     * photos.sluice.application.service.ApplyEngine#rebuildIndex} only actually rebuilds when every
     * sidecar is present, parseable, and forms a contiguous montage-001..NNN run; a gap or an
     * unparseable sidecar means the rebuild guard refuses, and this finding stays open with no
     * further engine-level remedy short of the last-resort discard-and-redo.
     */
    record CorruptIndex(Path indexPath) implements Finding {
        @Override
        public String describe() {
            return indexPath + ": corrupt or unreadable index.json";
        }

        @Override
        public Remedy remedy() {
            return Remedy.AUTO;
        }
    }

    /**
     * A montage's own sidecar (montage-NNN.json) cannot be parsed, while index.json itself is
     * intact. CHOICE because the sidecar names that montage's only surviving evidence of what was
     * actually in scope - the engine cannot decide unprompted whether to give up on that batch or
     * trust the shard's own decisions at face value. {@link
     * photos.sluice.application.service.ApplyEngine#resolveCorruptSidecar} records which the user
     * picked.
     */
    record CorruptSidecar(String montage) implements Finding {
        @Override
        public String describe() {
            return montage + ": sidecar unreadable or missing";
        }

        @Override
        public Remedy remedy() {
            return Remedy.CHOICE;
        }
    }

    /**
     * A decision's or an unreviewable file's source is gone, and no move record hash-verifies
     * where it ended up. CHOICE because the user must confirm what happened. The file was
     * restored (re-diagnose), or it should be skipped (a ledger entry) - the engine cannot tell
     * which.
     */
    record MissingSource(Path file, Path moveRecordLog) implements Finding {
        @Override
        public String describe() {
            return "file not found, and its move could not be verified: " + file
                    + " - if an earlier, crashed run already applied it, the automatic check that would confirm that"
                    + " (a move record matching this file, whose recorded destination still hash-verifies) found"
                    + " none. This needs manual investigation before re-running; see " + moveRecordLog + ".";
        }

        @Override
        public Remedy remedy() {
            return Remedy.CHOICE;
        }
    }

    /**
     * The shared "montage[#index]" location prefix used by every per-decision finding, and by
     * ShardValidator's own non-finding heal messages for the same location.
     *
     * @param montage {@link String} the montage id
     * @param index int the decision's 1-based position within its shard
     * @return {@link String} the location prefix
     */
    static String at(String montage, int index) {
        return montage + "[#" + index + "]";
    }
}
