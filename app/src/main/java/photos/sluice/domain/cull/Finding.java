package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.util.List;

/**
 * A single problem found while validating a prep directory. It is either a shard-contract
 * violation {@link ShardValidator} checks for, or one of
 * {@link photos.sluice.application.service.ApplyPlanner}'s own batch-level checks. Examples of the
 * latter are a stray shard, or a decision whose file cannot be accounted for.
 *
 * <p>{@link #describe()} renders the exact prose an aggregated {@code ApplyException} reports, so
 * no caller formats a message out of a finding's fields itself. A caller may still read those
 * fields directly to act on the finding - {@link photos.sluice.application.service.PrepDirRemedies}
 * takes a {@link StrayShard}'s own file name to repair it, and {@code ApplyPlanner} reads a
 * {@link DecisionUnreviewableOverlap}'s file to suppress it once resolved - it just never renders
 * text from them.
 *
 * <p>{@link #remedy()} classifies how, if at all, {@code PrepDirDoctor}'s troubleshooter can
 * resolve the finding on its own. AUTO is safe to apply unprompted. CHOICE means the user picks
 * from enumerated options. NONE is informational only, a culler content mistake that only a
 * re-cull can fix.
 */
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

    /**
     * How a finding can be resolved. AUTO is non-destructive and free, safe for the troubleshooter
     * to apply unprompted. CHOICE means resolving it loses work or money, so the user picks from
     * enumerated options. NONE is informational only - no engine-level repair exists.
     */
    enum Remedy {
        AUTO, CHOICE, NONE
    }

    /**
     * A shard's JSON is missing its {@code montage} field entirely, rather than declaring a value
     * that merely disagrees with the filename.
     */
    record MissingMontageField(String montage) implements Finding {
        @Override
        public String describe() {
            return this.montage + ": missing 'montage'";
        }
    }

    /**
     * A shard's {@code montage} field is present but names a different montage than its own
     * filename implies. The shard could be a misfiled or renamed copy of another montage's
     * decisions.
     */
    record MontageFieldMismatch(String montage, String declared) implements Finding {
        @Override
        public String describe() {
            return this.montage + ": 'montage' is '" + this.declared + "', expected '" + this.montage + "'";
        }
    }

    /**
     * A {@link Decision.Classification}'s category is not one of the categories currently
     * configured for this cull, so the decision cannot be routed to any known folder.
     */
    record InvalidCategory(String montage, int index, String category, String allowedClause) implements Finding {
        @Override
        public String describe() {
            return at(this.montage, this.index) + ": invalid action '" + this.category + "' (" + this.allowedClause + ")";
        }
    }

    /**
     * A {@link Decision.Classification} or {@link Decision.NearDupReject} is missing its required
     * {@code reason}, leaving no record of why the vision step made that call.
     */
    record MissingReason(String montage, int index) implements Finding {
        @Override
        public String describe() {
            return at(this.montage, this.index) + ": missing 'reason'";
        }
    }

    /**
     * A {@link Decision.NearDupChosen} or {@link Decision.NearDupReject} is missing its
     * {@code group} id, so it cannot be tied to any near-duplicate group.
     */
    record MissingGroup(String montage, int index) implements Finding {
        @Override
        public String describe() {
            return at(this.montage, this.index) + ": missing 'group'";
        }
    }

    /**
     * A {@link Decision.NearDupChosen} is missing its {@code chosen_reason}, leaving no record of
     * why the vision step picked it as the group's keeper.
     */
    record MissingChosenReason(String montage, int index) implements Finding {
        @Override
        public String describe() {
            return at(this.montage, this.index) + ": missing 'chosen_reason'";
        }
    }

    /**
     * A near-duplicate group has a chosen-keeper count other than exactly one, so apply cannot
     * tell which single file in the group should survive.
     */
    record WrongChosenCount(String montage, String group, int chosen) implements Finding {
        @Override
        public String describe() {
            return this.montage + ": near-dup group '" + this.group + "' has " + this.chosen + " chosen (need exactly" +
                    " 1)";
        }
    }

    /**
     * A near-duplicate group has no rejects at all, so it carries a chosen keeper but nothing for
     * that choice to have been made over.
     */
    record TooFewRejects(String montage, String group, int rejects) implements Finding {
        @Override
        public String describe() {
            return this.montage + ": near-dup group '" + this.group + "' has " + this.rejects + " reject(s) (need >=1)";
        }
    }

    /**
     * A near-duplicate group id is not a valid slug: lowercase {@code a-z0-9} runs joined by
     * single hyphens, at most {@code maxLength} characters. The id becomes part of a
     * {@code Duplicates/YYYY-MM_<slug>/} folder name, so it must stay a short, portable path
     * segment.
     */
    record InvalidGroupSlug(String montage, String group, int maxLength) implements Finding {
        @Override
        public String describe() {
            return this.montage + ": near-dup group '" + this.group
                    + "' is not a valid slug (lowercase a-z0-9, hyphenated, max " + this.maxLength + " chars)";
        }
    }

    /**
     * A file referenced more than once across all shards and the unreviewable list. This covers
     * every shape too general for any automatic resolution: two decisions, two unreviewable
     * entries, or three or more references altogether. See {@link DecisionUnreviewableOverlap} for
     * the one two-reference shape specific enough to offer a real choice.
     */
    record DuplicateFileReference(String file, long count) implements Finding {
        @Override
        public String describe() {
            return "file listed " + this.count + " times across shards/unreviewable: " + this.file;
        }
    }

    /**
     * A file listed both as a decision (in some montage's shard) and in index.json's own
     * unreviewable list. It is the one duplicate-reference shape common and specific enough for a
     * troubleshooter to offer a real choice, unlike the more general {@link DuplicateFileReference}.
     * CHOICE because either resolution changes which pile the file ends up in, and the engine cannot
     * decide that on its own. "Trust the decision" means the shard's verdict applies, and the file is
     * no longer treated as unreviewable. "Treat as unreviewable" means the decision is dropped, and
     * the file stays put, unreviewed.
     */
    record DecisionUnreviewableOverlap(Decision decision) implements Finding {
        @Override
        public String describe() {
            return "file listed both as a decision and as unreviewable: " + this.decision.file();
        }

        @Override
        public Remedy remedy() {
            return Remedy.CHOICE;
        }
    }

    /**
     * A near-duplicate group id is reused across more than one montage's shard, when a group is
     * expected to belong to exactly one montage. Left unresolved, the two unrelated groups would
     * merge into a single {@code Duplicates} folder at apply time.
     */
    record GroupSpansMultipleMontages(String group, List<String> montages) implements Finding {
        public GroupSpansMultipleMontages {
            montages = List.copyOf(montages);
        }

        @Override
        public String describe() {
            return "near-dup group '" + this.group + "' spans " + this.montages.size() + " shards ("
                    + String.join(", ", this.montages) + "); a group must stay within one montage";
        }
    }

    /**
     * A decision's {@code file} field is blank, leaving no source path for apply to act on.
     */
    record MissingFile(String montage, int index) implements Finding {
        @Override
        public String describe() {
            return at(this.montage, this.index) + ": missing 'file'";
        }
    }

    /**
     * A decision names a file the montages never actually showed, and no unique-basename heal
     * could resolve it back into scope.
     */
    record FileOutOfScope(String montage, int index, Path file) implements Finding {
        @Override
        public String describe() {
            return at(this.montage, this.index) + ": file out of scope: " + this.file;
        }
    }

    // ApplyPlanner's own batch-level checks, below - real failure paths beyond the per-decision
    // shard contract ShardValidator checks above.

    /**
     * A file a run would act on sits outside the Sorted root. Every candidate a cull can produce is
     * found by scanning Sorted, so a path naming anything else was not put there by a prep run.
     * Both lists a run draws its sources from can carry one: index.json's own unreviewable entries,
     * and the {@code src} set the montage sidecars record.
     *
     * <p>Distinct from {@link FileOutOfScope}, which says a decision named a file the montages never
     * showed. That check measures a decision against the sidecar set. This one measures the sidecar
     * set itself, and an edited sidecar passes the first check while failing this one.
     *
     * <p>The root naming itself gets its own wording. The general sentence reads as a contradiction
     * there, since the offending path and the root it escaped are the same string.
     *
     * <p>NONE, because no engine can tell which file was meant. Dropping the entry would silently
     * shrink what the run acts on. Correcting the prep dir, or discarding it and re-culling, are the
     * ways out.
     */
    record SourceOutsideSorted(Path file, Path sortedRoot) implements Finding {
        @Override
        public String describe() {
            return this.file.equals(this.sortedRoot)
                    ? "the Sorted root itself is named as a file to act on: " + this.file
                    : "file outside " + this.sortedRoot + ", the only place photos may be taken from: " + this.file;
        }
    }

    /**
     * A decisions-NNN.json file with no montage entry expecting it - almost always a culler
     * numbering slip. Always reports AUTO: this record is a pure value with no I/O, built before
     * any lookup of which montages are currently unclaimed. It cannot itself know whether the
     * repair will turn out ambiguous. The real decision is made when the repair actually runs.
     * {@link photos.sluice.application.service.PrepDirRemedies#autoRepairStrayShard} renames the
     * shard into the one montage left unclaimed when that's unambiguous. Otherwise it leaves the
     * shard untouched, for {@link
     * photos.sluice.application.service.PrepDirRemedies#setAsideStrayShard}'s own CHOICE fallback.
     */
    record StrayShard(String shardFile) implements Finding {
        @Override
        public String describe() {
            return this.shardFile + ": no matching montage";
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
            return this.montage + ": no shard " + this.expectedFile;
        }
    }

    /**
     * A montage's shard file is present but cannot be turned into decisions at all. Unparseable
     * JSON, an unknown field, a null document, or a null decision entry all land here. This is the
     * shard-side counterpart of {@link CorruptSidecar}, and the two split by who wrote the file.
     * A sidecar is this app's own output, so an unreadable one means the prep dir needs repairing.
     * A shard is the culling agent's output, so an unreadable one is content only a re-cull can
     * put right.
     *
     * <p>NONE for exactly that reason. No engine-level repair can invent the judgements the shard
     * was supposed to carry. Rewriting the shard, or the last-resort discard-and-redo, are the two
     * ways out.
     */
    record CorruptShard(String montage, String shardFile) implements Finding {
        @Override
        public String describe() {
            return this.montage + ": shard " + this.shardFile + " is unreadable";
        }
    }

    /**
     * The prep directory's own index.json cannot be parsed - missing, truncated, or not valid JSON.
     * Always reports AUTO, the same reasoning {@link StrayShard} already relies on. index.json is a
     * derived summary (scope, base path, montage entries, photo/montage counts), reconstructible
     * from the surviving sidecars and the prep dir's own location. Everything except the
     * unreviewable list survives, and that one is genuinely lost. {@link
     * photos.sluice.application.service.PrepDirRemedies#rebuildIndex} only actually rebuilds when
     * every sidecar is present, parseable, and forms a contiguous montage-001..NNN run. A gap or an
     * unparseable sidecar means the rebuild guard refuses. The finding then stays open with no
     * further engine-level remedy short of the last-resort discard-and-redo.
     */
    record CorruptIndex(Path indexPath) implements Finding {
        @Override
        public String describe() {
            return this.indexPath + ": corrupt or unreadable index.json";
        }

        @Override
        public Remedy remedy() {
            return Remedy.AUTO;
        }
    }

    /**
     * Reading the prep directory did not get far enough to say anything about it. This is the
     * catch-all diagnosis, so two different causes reach it and they are not distinguished here.
     *
     * <p>The first is a read that failed outright, leaving what is on disk intact. A file held open
     * by a backup or antivirus process, a permission denial, and a cloud placeholder that never
     * hydrated all land here. The second is content the readers past index.json could not make sense
     * of, such as a garbled line in the move ledger. There the files opened fine and the bytes are
     * the problem.
     *
     * <p>NONE because neither cause has a repair worth offering unprompted. Naming an {@code AUTO}
     * remedy would offer to rebuild an artifact nothing has established as broken. Retrying costs
     * nothing and resolves the first cause once whatever held the file lets go. Discard is the way
     * out when it does not, which is where the second cause tends to end up.
     *
     * <p>This is what keeps {@code PrepDirHealth.State.DAMAGED} from being a state with nothing to
     * show. A dashboard renders this line. A troubleshoot report says it plainly, rather than
     * reporting a clean bill of health for a directory the app is refusing to cull.
     */
    record UnreadablePrepDir(Path prepDir) implements Finding {
        @Override
        public String describe() {
            return this.prepDir + ": could not be read far enough to diagnose - see the log for what failed";
        }
    }

    /**
     * A montage's own sidecar (montage-NNN.json) cannot be parsed, while index.json itself is
     * intact. CHOICE because the sidecar names that montage's only surviving evidence of what was
     * actually in scope. The engine cannot decide unprompted whether to give up on that batch or
     * trust the shard's own decisions at face value. {@link
     * photos.sluice.application.service.PrepDirRemedies#resolveCorruptSidecar} records which the user
     * picked.
     */
    record CorruptSidecar(String montage) implements Finding {
        @Override
        public String describe() {
            return this.montage + ": sidecar unreadable or missing";
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
            return "file not found, and its move could not be verified: " + this.file
                    + " - if an earlier, crashed sift already applied it, the automatic check that would confirm that"
                    + " (a move record matching this file, whose recorded destination still hash-verifies) found"
                    + " none. This needs manual investigation before re-running; see " + this.moveRecordLog + ".";
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
    static String at(final String montage, final int index) {
        return montage + "[#" + index + "]";
    }
}
