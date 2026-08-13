package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.Finding.InvalidCategory;
import photos.sluice.domain.cull.Finding.MissingSource;
import photos.sluice.domain.cull.Finding.StrayShard;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.cull.SidecarPhotoEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.application.service.CullPrepTestSupport.fixedCategories;

// Fixture-writing helpers below mirror ApplyPlannerTest's own. PrepDirDoctor reuses ApplyPlanner's
// validate()/checkMissingSources() internally, so the same shard/sidecar/index fixtures apply.
//
// The never-throws tests share one stake, stated here rather than repeated on each. A dashboard poll
// and the startup scan both call diagnose() and runs(), so an escape takes down a whole reading or
// the app's own startup. Each test below names only what is distinct about its own failure.
class PrepDirDoctorTest {

    @Test
    void anAppliedRunReportsComplete(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.writeString(prepDir.resolve("decisions.json"), "{}");

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.COMPLETE);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void aCompleteRunReportsCompleteEvenWhenIndexJsonIsCorrupt(@TempDir final Path root) throws IOException {
        // The completion check reads only decisions.json, since a COMPLETE run needs nothing else.
        // A corrupt index.json past that point must never surface as a blocking problem. It could
        // have been clobbered long after the run already finished.
        final Path prepDir = prepDir(root);
        Files.writeString(prepDir.resolve("index.json"), "not valid json");
        Files.writeString(prepDir.resolve("decisions.json"), "{}");

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.COMPLETE);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void aCorruptIndexReportsBlockedWithAnAutoRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        Files.writeString(prepDir.resolve("index.json"), "not valid json");

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(new Finding.CorruptIndex(prepDir.resolve("index.json")));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.AUTO);
    }

    @Test
    void aMissingIndexReportsBlockedWithAnAutoRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root); // index.json never written at all

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(new Finding.CorruptIndex(prepDir.resolve("index.json")));
    }

    @Test
    void anUnreviewableEntryOutsideSortedReportsBlockedOnceEveryShardHasArrived(@TempDir final Path root)
            throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path outside = root.resolve("Documents/taxes.pdf");
        writeFile(photo, "x");
        writeFile(outside, "not media at all");
        writeIndex(prepDir, 1, List.of(outside), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings())
                .containsExactly(new Finding.SourceOutsideSorted(outside, root.resolve("Sorted")));
    }

    // The shard-count branch is checked before validity, so a run still being culled reports WAITING
    // even carrying a finding no shard can resolve. A watcher then arms and auto-resume runs, and the
    // refusal lands as Blocked at apply. Pinned because that sequence is what a user sees.
    @Test
    void anUnreviewableEntryOutsideSortedStillReportsWaitingWhileAShardIsOutstanding(@TempDir final Path root)
            throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path outside = root.resolve("Documents/taxes.pdf");
        writeFile(photo, "x");
        writeFile(outside, "not media at all");
        writeIndex(prepDir, 1, List.of(outside), List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.WAITING);
        assertThat(health.findings())
                .containsExactly(new Finding.SourceOutsideSorted(outside, root.resolve("Sorted")));
    }

    // A read that merely failed is DAMAGED, never corrupt. CorruptIndex carries an AUTO remedy, so
    // calling this one corrupt would offer to rebuild an index that was never broken. The finding's
    // NONE remedy offers no repair, because nothing has been established as broken.
    // Injected at the reader rather than provoked through the filesystem, so the assertion holds
    // identically on every platform.
    @Test
    void aFailedIndexReadReportsDamagedRatherThanCorruptOrAThrow(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        writeIndex(prepDir, 1, List.of("montage-001"));

        final PrepDirHealth health = doctor(root, new FailingIndexRead()).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.DAMAGED);
        assertThat(health.findings()).containsExactly(new Finding.UnreadablePrepDir(prepDir));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.NONE);
    }

    // Every read past the index can fail the same transient way the index itself can, and all of
    // them land on DAMAGED rather than escaping. This one fails on a shard, which reaches the
    // planner rather than the index read.
    @Test
    void aFailedShardReadReportsDamagedRatherThanThrowing(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final PrepDirHealth health = doctor(root, new FailingShardRead()).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.DAMAGED);
        assertThat(health.findings()).containsExactly(new Finding.UnreadablePrepDir(prepDir));
    }

    // The same classification reached the way a user actually reaches it, through the filesystem
    // rather than an injected reader. A directory sitting where index.json belongs is a real read
    // failure. That is distinct from "not valid json" (malformed content) and from "never written
    // at all" (permanently absent, diagnosed the same as corrupt). Which of the reader's own catch
    // clauses sees it differs by platform, so this pins both platforms to the same verdict.
    @Test
    void aDirectoryWhereIndexJsonBelongsReportsDamagedOnEveryPlatform(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        Files.createDirectory(prepDir.resolve("index.json"));

        assertThat(doctor(root).diagnose(prepDir).state()).isEqualTo(State.DAMAGED);
    }

    // A line well-formed enough to reach the parser and garbled where it counts, so its resolution
    // field cannot become a CorruptSidecarResolution. Dropped rather than thrown, so the disposition
    // it named reads as never given. The sidecar it was meant to answer for was never written
    // either, standing in for the corrupt one that answer was about. The run diagnoses to its real
    // state, with the original finding re-raised, rather than reporting the whole dir damaged over
    // one bad line.
    @Test
    void aGarbledChoicesLineIsDroppedAndTheOriginalFindingIsReRaisedInstead(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        writeIndex(prepDir, 1, List.of("montage-001"));
        final String d = MoveLedger.RECORD_DELIMITER;
        Files.writeString(prepDir.resolve("choices.log"),
                "montage-001" + d + "CORRUPT_SIDECAR_RESOLVED" + d + "NOT_A_RESOLUTION" + d + "why" + d + "when");

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.WAITING);
        assertThat(health.findings()).containsExactly(new Finding.CorruptSidecar("montage-001"));
    }

    // The same guarantee one layer up: a dir runs() cannot diagnose becomes a row rather than an
    // exception. The healthy dir alongside it proves the scan carried on rather than ending there.
    // Injected at the CullPrepPort seam rather than through the filesystem. That way the
    // classification does not depend on how a given platform's filesystem treats a directory
    // standing in for a file.
    @Test
    void runsListsADirItCannotDiagnoseInsteadOfThrowing(@TempDir final Path root) throws IOException {
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        final Path healthy = prepDir(root, "2019");
        writeIndex(healthy, 1, List.of("montage-001"));
        writeSidecar(healthy, "montage-001", sidecarEntry(photo));
        writeShard(healthy, "montage-001", classificationJson(photo, "junk", "blurry"));
        final Path damaged = prepDir(root, "2020");
        writeIndex(damaged, 1, List.of("montage-001"));

        final List<CullRunSummary> runs =
                doctor(root, new FailingIndexReadOf(damaged)).runs(root.resolve("logs/cull-prep"));

        assertThat(runs).extracting(CullRunSummary::scope).containsExactly("2019", "2020");
        assertThat(runs.getLast().health().state()).isEqualTo(State.DAMAGED);
        assertThat(runs.getLast().shards()).isNull();
        assertThat(runs.getFirst().health().state()).isEqualTo(State.READY);
    }

    @Test
    void aCorruptSidecarForAMontageWithAShardReportsBlockedWithAChoiceRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        // No sidecar written for montage-001 at all - stands in for a missing or corrupt one.
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(new Finding.CorruptSidecar("montage-001"));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.CHOICE);
    }

    // A shard file present but unparseable counts toward the tally as present, so the dir is past
    // WAITING and lands on the real gate. Diagnosis describes it rather than throwing.
    @Test
    void aCorruptShardReportsBlockedWithAnInformationalFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeFile(prepDir.resolve("decisions-001.json"), "{ not valid json");

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings())
                .containsExactly(new Finding.CorruptShard("montage-001", "decisions-001.json"));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.NONE);
    }

    // A montage with an unreadable sidecar and no shard looks like one still being culled and is
    // not. A culler keys its verdicts against the sidecar, so it can never produce a shard here.
    // The run stays WAITING because other montages genuinely are still coming, but the finding has
    // to be there. An empty list would leave the troubleshoot screen with nothing to offer, and
    // only a discard to escape by.
    @Test
    void aCorruptSidecarForAMontageWithNoShardYetStillReportsItsFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(culled, "x");
        writeIndex(prepDir, 1, List.of("montage-001", "montage-002", "montage-003"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        // montage-002 has a readable sidecar and no shard: genuinely still being culled, and the
        // reason this dir is WAITING rather than BLOCKED. montage-003 has neither.
        writeSidecar(prepDir, "montage-002", sidecarEntry(root.resolve("Sorted/Photos/2019/06/b.jpg")));

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.WAITING);
        assertThat(health.findings()).containsExactly(new Finding.CorruptSidecar("montage-003"));
    }

    @Test
    void aMontageWithNoShardYetReportsWaitingWithoutAMissingShardFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path culled = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path uncalled = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(culled, "x");
        writeFile(uncalled, "y");
        writeIndex(prepDir, 2, List.of("montage-001", "montage-002"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(culled));
        writeSidecar(prepDir, "montage-002", sidecarEntry(uncalled));
        writeShard(prepDir, "montage-001", classificationJson(culled, "junk", "blurry"));
        // montage-002 has no shard yet - still being culled.

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.WAITING);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void everyMontageShardedAndCleanReportsReady(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.READY);
        assertThat(health.findings()).isEmpty();
    }

    @Test
    void anOffContractDecisionReportsBlockedWithANoneRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "meme", "not a configured category"));

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(
                new InvalidCategory("montage-001", 1, "meme", "allowed: junk, scenery, food, funny"));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.NONE);
    }

    @Test
    void aStrayShardReportsBlockedWithAnAutoRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        writeShard(prepDir, "montage-002"); // no montage-002 entry in index.json - a stray shard

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(new StrayShard("decisions-002.json"));
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.AUTO);
    }

    @Test
    void aMissingSourceWithNoMoveRecordReportsBlockedWithAChoiceRemedyFinding(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root);
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg"); // never written to disk, no move record
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(photo));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).hasSize(1);
        assertThat(health.findings().getFirst()).isInstanceOf(MissingSource.class);
        assertThat(health.findings().getFirst().remedy()).isEqualTo(Finding.Remedy.CHOICE);
        assertThat(health.findings().getFirst().describe()).contains(photo.toString());
    }

    @Test
    void findingsAreOrderedAutoRemedyBeforeNoneRemedy(@TempDir final Path root) throws IOException {
        // A stray shard (AUTO) and an off-contract decision (NONE) are both shard-contract findings,
        // so both surface together. A MissingSource (CHOICE) is different - it only ever surfaces
        // once the shard contract is already clean (see PrepDirDoctor.diagnose()'s own doc for why).
        final Path prepDir = prepDir(root);
        final Path offContract = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(offContract, "x");
        writeIndex(prepDir, 1, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(offContract));
        writeShard(prepDir, "montage-001", classificationJson(offContract, "meme", "not a configured category"));
        writeShard(prepDir, "montage-002"); // stray shard, AUTO remedy

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).extracting(Finding::remedy)
                .containsExactly(Finding.Remedy.AUTO, Finding.Remedy.NONE);
    }

    @Test
    void aShardContractProblemSuppressesMissingSourceCheckingForAnUnrelatedDecision(@TempDir final Path root) throws IOException {
        // Guards against a misleading double finding. An off-contract decision already reports
        // InvalidCategory. An unrelated decision in the same batch, whose file is genuinely missing,
        // must not ALSO surface a MissingSource. The whole batch is already blocked on the shard
        // contract, the same gate apply() enforces before ever checking file existence.
        final Path prepDir = prepDir(root);
        final Path offContract = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path missingSource = root.resolve("Sorted/Photos/2019/06/b.jpg"); // never written, no move record
        writeFile(offContract, "x");
        writeIndex(prepDir, 2, List.of("montage-001"));
        writeSidecar(prepDir, "montage-001", sidecarEntry(offContract), sidecarEntry(missingSource));
        writeShard(prepDir, "montage-001",
                classificationJson(offContract, "meme", "not a configured category"),
                classificationJson(missingSource, "junk", "blurry"));

        final PrepDirHealth health = doctor(root).diagnose(prepDir);

        assertThat(health.state()).isEqualTo(State.BLOCKED);
        assertThat(health.findings()).containsExactly(
                new InvalidCategory("montage-001", 1, "meme", "allowed: junk, scenery, food, funny"));
    }

    @Test
    void purgeCompletedDeletesOnlyCompletedRunsAndReportsSkippedScopesWithTheirState(@TempDir final Path root)
            throws IOException {
        final Path complete = prepDir(root, "complete1");
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(complete, 1, List.of("montage-001"));
        writeSidecar(complete, "montage-001", sidecarEntry(photo));
        writeShard(complete, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.writeString(complete.resolve("decisions.json"), "{}");
        final Path waiting = prepDir(root, "waiting1");
        writeIndex(waiting, 1, List.of("montage-001")); // no shard yet - still culling

        final PurgeReport report = doctor(root).purgeCompleted(root.resolve("logs/cull-prep"));

        assertThat(report.purged()).containsExactly("complete1");
        assertThat(report.skipped()).containsExactly(entry("waiting1", State.WAITING));
        assertThat(Files.exists(complete)).isFalse();
        assertThat(Files.exists(waiting)).isTrue();
        // Forward-looking guard on the never-delete-media invariant. This fixture's photo sits
        // outside the purged tree, so nothing here could make purgeCompleted() touch it today.
        // A future change that followed shard-referenced paths instead would trip this.
        assertThat(Files.exists(photo)).isTrue();
    }

    // A dir holding shards but no index is exactly the run worth not overwriting, so the sweep has
    // to see it. Keyed on index.json it would be invisible here and in every other enumeration.
    @Test
    void purgeCompletedSeesAPrepDirWithNoIndexAndReportsItSkipped(@TempDir final Path root) throws IOException {
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        final Path indexless = prepDir(root, "indexless1");
        writeShard(indexless, "montage-001", classificationJson(photo, "junk", "blurry"));

        final PurgeReport report = doctor(root).purgeCompleted(root.resolve("logs/cull-prep"));

        assertThat(report.purged()).isEmpty();
        assertThat(report.skipped()).containsExactly(entry("indexless1", State.BLOCKED));
        assertThat(Files.exists(indexless)).isTrue();
    }

    // Two exclusions from what counts as a run. A loose file directly in the root belongs to no
    // run at all. A file inside a prep dir's own subdirectory - the disaster drawer is one - belongs
    // to that run, not to a run named after the subdirectory. Both fixtures below would produce a
    // spurious extra entry if either rule were dropped.
    @Test
    void runsCountsNeitherALooseRootFileNorASubdirectoryAsItsOwnRun(@TempDir final Path root) throws IOException {
        final Path cullPrepRoot = root.resolve("logs/cull-prep");
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        final Path real = prepDir(root, "2019");
        writeIndex(real, 1, List.of("montage-001"));
        writeSidecar(real, "montage-001", sidecarEntry(photo));
        writeShard(real, "montage-001", classificationJson(photo, "junk", "blurry"));
        writeFile(real.resolve("drawer/corrupt-sidecar-montage-001.json"), "{}");
        writeFile(cullPrepRoot.resolve("stray-note.txt"), "not part of any run");

        final List<CullRunSummary> runs = doctor(root).runs(cullPrepRoot);

        assertThat(runs).singleElement().extracting(CullRunSummary::scope).isEqualTo("2019");
    }

    @Test
    void purgeCompletedOnAMissingCullPrepRootReturnsAnEmptyReport(@TempDir final Path root) {
        final PurgeReport report = doctor(root).purgeCompleted(root.resolve("logs/cull-prep"));

        assertThat(report.purged()).isEmpty();
        assertThat(report.skipped()).isEmpty();
    }

    // A dir whose own occupancy could not be determined diagnoses DAMAGED. It lands in the report's
    // own unreadable bucket, distinct from skipped, and its healthy sibling still purges normally.
    @Test
    void purgeCompletedReportsAnUnreadableOccupancyCheckInItsOwnBucketAndStillPurgesItsSibling(
            @TempDir final Path root) throws IOException {
        final Path complete = prepDir(root, "complete1");
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        writeIndex(complete, 1, List.of("montage-001"));
        writeSidecar(complete, "montage-001", sidecarEntry(photo));
        writeShard(complete, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.writeString(complete.resolve("decisions.json"), "{}");
        final Path unreadable = prepDir(root, "unreadable1");
        writeIndex(unreadable, 1, List.of("montage-001"));
        final var store = new FailingListingOf(unreadable);

        final PurgeReport report =
                CullPrepTestSupport.prepDirDoctor(root, store).purgeCompleted(root.resolve("logs/cull-prep"));

        assertThat(report.purged()).containsExactly("complete1");
        assertThat(report.skipped()).isEmpty();
        assertThat(report.unreadable()).containsExactly(entry("unreadable1", "could not be read"));
        assertThat(Files.exists(complete)).isFalse();
        assertThat(Files.exists(unreadable)).isTrue();
    }

    // purgeDir() guards itself, so a delete failing on one prep dir does not abandon the sweep with
    // no report at all. That dir lands in the unreadable bucket instead of purged, and its sibling
    // still purges normally.
    @Test
    void purgeCompletedReportsAFailedDeleteInItsOwnBucketAndContinuesTheSweep(@TempDir final Path root)
            throws IOException {
        final Path first = prepDir(root, "complete1");
        final Path second = prepDir(root, "complete2");
        final Path photo1 = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path photo2 = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(photo1, "x");
        writeFile(photo2, "y");
        writeIndex(first, 1, List.of("montage-001"));
        writeSidecar(first, "montage-001", sidecarEntry(photo1));
        writeShard(first, "montage-001", classificationJson(photo1, "junk", "blurry"));
        Files.writeString(first.resolve("decisions.json"), "{}");
        writeIndex(second, 1, List.of("montage-001"));
        writeSidecar(second, "montage-001", sidecarEntry(photo2));
        writeShard(second, "montage-001", classificationJson(photo2, "junk", "blurry"));
        Files.writeString(second.resolve("decisions.json"), "{}");
        final var store = new FailingDeleteUnder(first);

        final PurgeReport report =
                CullPrepTestSupport.prepDirDoctor(root, store).purgeCompleted(root.resolve("logs/cull-prep"));

        assertThat(report.purged()).containsExactly("complete2");
        assertThat(report.skipped()).isEmpty();
        assertThat(report.unreadable()).containsExactly(entry("complete1", "could not be deleted"));
        assertThat(Files.exists(first)).isTrue();
        assertThat(Files.exists(second)).isFalse();
    }

    // Fails delete() for anything under one target prep dir, standing in for a lock or permission
    // denial on one of the files a purge is deleting. Every other dir's delete behaves normally.
    private static final class FailingDeleteUnder extends NioMediaStore {

        private final Path target;

        FailingDeleteUnder(final Path target) {
            this.target = target;
        }

        @Override
        public void delete(final Path path) {
            if (path.startsWith(this.target)) {
                throw new IllegalStateException("simulated delete failure");
            }
            super.delete(path);
        }
    }

    // Each asserts an empty result, so each first proves the same fixture yields a run through a
    // working store. Without that control the assertion would hold against a root that simply has
    // nothing in it, and would pass with no guard in the code at all.
    @Test
    void runsReportsNoRunsWhenTheRootListingFailsRatherThanThrowing(@TempDir final Path root) throws IOException {
        final Path cullPrepRoot = root.resolve("logs/cull-prep");
        writeFile(cullPrepRoot.resolve("2019-06/index.json"), "{}");
        assertThat(doctor(root).runs(cullPrepRoot)).hasSize(1);

        assertThat(CullPrepTestSupport.prepDirDoctor(root, new FailingListing()).runs(cullPrepRoot)).isEmpty();
    }

    // The epoch reads as "as old as anything", putting a dir nobody can stat at the top of a list
    // ordered by neglect. The working-store assertion first, so this cannot pass with the guard gone.
    @Test
    void aPrepDirWhoseMtimeCannotBeReadIsAgedAsTheEpoch(@TempDir final Path root) throws IOException {
        final Path prepDir = prepDir(root, "2019");
        writeIndex(prepDir, 1, List.of("montage-001"));

        assertThat(doctor(root).summaryOf(prepDir).since()).isNotEqualTo(Instant.EPOCH);
        assertThat(CullPrepTestSupport.prepDirDoctor(root, new FailingLastModified()).summaryOf(prepDir).since())
                .isEqualTo(Instant.EPOCH);
    }

    private static Path prepDir(final Path root) throws IOException {
        return prepDir(root, "scope1");
    }

    private static Path prepDir(final Path root, final String scope) throws IOException {
        final Path dir = root.resolve("logs/cull-prep").resolve(scope);
        Files.createDirectories(dir);
        return dir;
    }

    private static void writeIndex(final Path prepDir, final int photos, final List<String> entries) {
        writeIndex(prepDir, photos, List.of(), entries);
    }

    private static void writeIndex(final Path prepDir, final int photos, final List<Path> unreviewable,
                                   final List<String> entries) {
        new PrepIndexWriter().write(prepDir.resolve("index.json"),
                new PrepDir("2019-06", fixedCategories(), prepDir.resolve("base"), photos, unreviewable,
                        entries.size(), prepDir, entries));
    }

    private static void writeSidecar(final Path prepDir, final String montage, final SidecarPhotoEntry... photos) {
        new SidecarWriter().write(prepDir.resolve(montage + ".json"), prepDir.resolve(montage + ".jpg"),
                List.of(photos));
    }

    private static SidecarPhotoEntry sidecarEntry(final Path src) {
        return new SidecarPhotoEntry(src, src.getFileName().toString(), Instant.parse("2019-06-15T10:00:00Z"), false);
    }

    private static void writeShard(final Path prepDir, final String montage, final String... decisionsJson) throws IOException {
        final String shardName = montage.replaceFirst("^montage-", "decisions-") + ".json";
        Files.writeString(prepDir.resolve(shardName),
                "{ \"montage\": \"%s\", \"decisions\": [ %s ] }".formatted(montage, String.join(", ", decisionsJson)));
    }

    private static String classificationJson(final Path file, final String category, final String reason) {
        return "{ \"file\": \"%s\", \"action\": \"%s\", \"reason\": \"%s\" }"
                .formatted(file.toString().replace("\\", "\\\\"), category, reason);
    }

    // The existence check reaches the same port as the listing does. So the guard covers it too,
    // not only the call that looks like the risky one.
    @Test
    void runsReportsNoRunsWhenTheRootExistenceCheckFailsRatherThanThrowing(@TempDir final Path root)
            throws IOException {
        final Path cullPrepRoot = root.resolve("logs/cull-prep");
        writeFile(cullPrepRoot.resolve("2019-06/index.json"), "{}");
        assertThat(doctor(root).runs(cullPrepRoot)).hasSize(1);

        assertThat(CullPrepTestSupport.prepDirDoctor(root, new FailingExists()).runs(cullPrepRoot)).isEmpty();
    }

    // The new, narrower guard: only one candidate's own occupancy check fails, so only that one
    // candidate is affected. Its healthy sibling still gets enumerated and diagnosed normally,
    // proving isolation rather than the root-level guard above, which fails the whole enumeration.
    @Test
    void runsListsTheHealthySiblingAlongsideADamagedEntryForTheOneWhoseOccupancyCouldNotBeRead(
            @TempDir final Path root) throws IOException {
        final Path photo = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(photo, "x");
        final Path healthy = prepDir(root, "2019");
        writeIndex(healthy, 1, List.of("montage-001"));
        writeSidecar(healthy, "montage-001", sidecarEntry(photo));
        writeShard(healthy, "montage-001", classificationJson(photo, "junk", "blurry"));
        final Path unreadable = prepDir(root, "2020");
        writeIndex(unreadable, 1, List.of("montage-001"));
        final var store = new FailingListingOf(unreadable);

        final List<CullRunSummary> runs = CullPrepTestSupport.prepDirDoctor(root, store).runs(root.resolve("logs/cull-prep"));

        assertThat(runs).extracting(CullRunSummary::scope).containsExactly("2019", "2020");
        assertThat(runs.getFirst().health().state()).isEqualTo(State.READY);
        assertThat(runs.getLast().health().state()).isEqualTo(State.DAMAGED);
    }

    private static void writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // A stat that fails with a plain unchecked exception - the guard holds for the whole unchecked
    // space, not a list of expected types.
    private static final class FailingLastModified extends NioMediaStore {

        @Override
        public Instant lastModifiedTime(final Path path) {
            throw new IllegalStateException("simulated stat failure");
        }
    }

    private static PrepDirDoctor doctor(final Path root) {
        return CullPrepTestSupport.prepDirDoctor(root);
    }

    private static PrepDirDoctor doctor(final Path root, final CullPrepPort cullPrepPort) {
        return CullPrepTestSupport.prepDirDoctor(root, cullPrepPort);
    }

    // A media store whose directory listing fails with a plain unchecked exception rather than an
    // I/O one. A port constrains nothing about what its adapters may raise. So the doctor's guards
    // hold for the whole unchecked space, rather than for a list of expected types.
    private static final class FailingListing extends NioMediaStore {

        @Override
        public List<Path> listChildDirectories(final Path dir) {
            throw new IllegalStateException("simulated listing failure");
        }
    }

    // Fails listFiles() for exactly one candidate prep dir. Stands in for that one dir's own read
    // failing - a locked disaster-drawer file, say. Every sibling and the root-level shallow listing
    // behave normally.
    private static final class FailingListingOf extends NioMediaStore {

        private final Path target;

        FailingListingOf(final Path target) {
            this.target = target;
        }

        @Override
        public List<Path> listFiles(final Path dir) {
            if (dir.equals(this.target)) {
                throw new IllegalStateException("simulated listing failure");
            }
            return super.listFiles(dir);
        }
    }

    // A media store whose existence check fails the same way FailingListing's listing does.
    private static final class FailingExists extends NioMediaStore {

        @Override
        public boolean exists(final Path path) {
            throw new IllegalStateException("simulated existence-check failure");
        }
    }

    // What a lock, a permission denial or an unhydrated cloud placeholder raises. The content is
    // intact, and opening it is what did not work.
    private static UncheckedIOException simulatedReadFailure() {
        return new UncheckedIOException(new IOException("simulated read failure"));
    }

    // The two readers below fail at different depths of one call sequence. That is why they are two
    // classes rather than one. readIndex() runs before readShard(). A single reader failing both
    // would always stop at the index, leaving the shard test unable to reach the planner it exists
    // to exercise.

    // Fails on the shard read, with the index reading normally, so a diagnosis gets all the way into
    // the planner before this bites.
    private static final class FailingShardRead extends DelegatingPrepStore {

        @Override
        public DecisionShard readShard(final Path prepDir, final String montage) {
            throw simulatedReadFailure();
        }
    }

    // Fails on the index read, the first read a diagnosis makes.
    private static final class FailingIndexRead extends DelegatingPrepStore {

        @Override
        public PrepDir readIndex(final Path prepDir) {
            throw simulatedReadFailure();
        }
    }

    // Fails the index read for exactly one target prep dir, so a sibling prep dir's own diagnosis
    // reads normally alongside it.
    private static final class FailingIndexReadOf extends DelegatingPrepStore {

        private final Path target;

        FailingIndexReadOf(final Path target) {
            this.target = target;
        }

        @Override
        public PrepDir readIndex(final Path prepDir) {
            if (prepDir.equals(this.target)) {
                throw simulatedReadFailure();
            }
            return super.readIndex(prepDir);
        }
    }

    // Passes every read and write through to a real store, so a subclass overrides only the one call
    // it wants to fail and everything else behaves normally.
    private abstract static class DelegatingPrepStore implements CullPrepPort {

        private final CullPrepPort delegate = new JsonCullPrepStore();

        @Override
        public PrepDir readIndex(final Path prepDir) {
            return this.delegate.readIndex(prepDir);
        }

        @Override
        public void writeIndex(final Path prepDir, final PrepDir index) {
            this.delegate.writeIndex(prepDir, index);
        }

        @Override
        public List<SidecarPhotoEntry> readSidecar(final Path prepDir, final String montage) {
            return this.delegate.readSidecar(prepDir, montage);
        }

        @Override
        public boolean hasShard(final Path prepDir, final String montage) {
            return this.delegate.hasShard(prepDir, montage);
        }

        @Override
        public DecisionShard readShard(final Path prepDir, final String montage) {
            return this.delegate.readShard(prepDir, montage);
        }

        @Override
        public DecisionShard readShardFile(final Path shardFile) {
            return this.delegate.readShardFile(shardFile);
        }

        @Override
        public void writeMergedDecisions(final Path prepDir, final String scope, final List<Decision> decisions,
                                         final ApplyReport report) {
            this.delegate.writeMergedDecisions(prepDir, scope, decisions, report);
        }
    }
}
