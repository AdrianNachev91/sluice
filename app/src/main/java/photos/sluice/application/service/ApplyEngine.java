package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.ShardValidator.ShardFile;
import photos.sluice.domain.cull.ValidationReport;
import photos.sluice.domain.model.IndexEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

// Flowchart + scenario table: app/docs/design/application/service/apply-engine.md.
@Component
public class ApplyEngine {

    private static final String FUNNY_CATEGORY = "funny";
    private static final String REASONS_FILE = "_reasons.txt";
    private static final String APPLIED_LOG = "applied.log";
    private static final String UNDATED = "0000-00";

    private final PathsPort pathsPort;
    private final MediaStore mediaStore;
    private final CullPrepPort cullPrepPort;
    private final CullSettings cullSettings;
    private final Sha256Port sha256Port;
    private final HashIndexPort hashIndexPort;
    private final ShardValidator shardValidator = new ShardValidator();

    public ApplyEngine(PathsPort pathsPort, MediaStore mediaStore, CullPrepPort cullPrepPort,
            CullSettings cullSettings, Sha256Port sha256Port, HashIndexPort hashIndexPort) {
        this.pathsPort = pathsPort;
        this.mediaStore = mediaStore;
        this.cullPrepPort = cullPrepPort;
        this.cullSettings = cullSettings;
        this.sha256Port = sha256Port;
        this.hashIndexPort = hashIndexPort;
    }

    // Reads the prep directory's index.json and every montage's decision shard, then validates the
    // whole batch in one pass (see validate()). Each pending decision is then carried out in order.
    // A decision already recorded in applied.log is skipped, so an interrupted run can simply be
    // re-run. Once every decision is handled, the merged decisions.json is written and the
    // montage/tile intermediates are deleted.
    public ApplyReport apply(Path prepDirPath, ApplyOptions options) throws ApplyException {
        PrepDir prepDir = cullPrepPort.readIndex(prepDirPath);
        ValidationReport validation = validate(prepDirPath, prepDir, options);

        Path appliedLog = prepDirPath.resolve(APPLIED_LOG);
        Set<String> applied = new HashSet<>(mediaStore.readLines(appliedLog));
        assertEveryPendingFileExists(appliedLog, validation.decisions(), applied);

        Map<String, List<Decision>> nearDupGroups = groupNearDups(validation.decisions());
        var outcome = new ApplyOutcome();
        validation.decisions().stream()
                .filter(decision -> !applied.contains(decision.file().toString()))
                .forEach(decision -> {
                    apply(decision, nearDupGroups, outcome);
                    mediaStore.appendLine(appliedLog, decision.file().toString());
                });

        var report = new ApplyReport(prepDir.photos(), outcome.byCategory, prepDir.unreviewable().size(),
                outcome.nearDupGroupsChosen.size(), outcome.nearDupRejects, validation.heals());
        ApplyReport persistedSummary = summarize(validation.decisions(), prepDir, validation.heals());
        cullPrepPort.writeMergedDecisions(prepDirPath, prepDir.scope(), validation.decisions(), persistedSummary);
        cleanupIntermediates(prepDirPath);
        return report;
    }

    // The persisted decisions.json embeds a fresh recount over the whole decisions array it sits
    // next to. That covers this run's decisions and every prior run's alike, not just the
    // this-run-only report returned to the caller. decisions.json is overwritten wholesale on every
    // write, never appended to, so recomputing from the full list each time carries no
    // double-counting risk.
    private static ApplyReport summarize(List<Decision> decisions, PrepDir prepDir, List<String> heals) {
        Map<String, Integer> byCategory = new TreeMap<>();
        Set<String> groups = new HashSet<>();
        int rejects = 0;
        for (Decision decision : decisions) {
            switch (decision) {
                case Classification c -> byCategory.merge(c.category(), 1, Integer::sum);
                case NearDupChosen c -> groups.add(c.group());
                case NearDupReject _ -> rejects++;
            }
        }
        return new ApplyReport(prepDir.photos(), byCategory, prepDir.unreviewable().size(), groups.size(), rejects, heals);
    }

    // Three problem sources feed into one aggregated report before anything throws. A missing
    // montage shard is skipped only when allowPartial waives it. A decisions file with no matching
    // montage is always a problem: almost always a culler numbering mistake, and its decisions would
    // otherwise be silently ignored. The shard contract itself is always checked too. One combined
    // throw covers the whole to-fix list in a single pass, before any file moves.
    private ValidationReport validate(Path prepDirPath, PrepDir prepDir, ApplyOptions options)
            throws ApplyException {
        var problems = new ArrayList<String>();

        Set<String> missingMontages = prepDir.entries().stream()
                .filter(montage -> !cullPrepPort.hasShard(prepDirPath, montage))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!options.allowPartial()) {
            missingMontages.forEach(montage -> problems.add(montage + ": no shard " + MontageNaming.shardFileFor(montage)));
        }

        Set<String> expectedShardNames = prepDir.entries().stream()
                .map(MontageNaming::shardFileFor)
                .collect(Collectors.toSet());
        mediaStore.listFiles(prepDirPath).stream()
                .map(file -> file.getFileName().toString())
                .filter(name -> name.startsWith("decisions-") && name.endsWith(".json"))
                .filter(name -> !expectedShardNames.contains(name))
                .sorted()
                .forEach(name -> problems.add(name + ": no matching montage"));

        List<Path> sidecarSrcs = prepDir.entries().stream()
                .flatMap(montage -> cullPrepPort.readSidecar(prepDirPath, montage).stream())
                .map(SidecarPhotoEntry::src)
                .toList();
        List<ShardFile> shardFiles = prepDir.entries().stream()
                .filter(montage -> !missingMontages.contains(montage))
                .map(montage -> new ShardFile(montage, cullPrepPort.readShard(prepDirPath, montage)))
                .toList();
        List<String> categories = cullSettings.categories().stream().map(CullCategory::name).toList();

        ValidationReport report = shardValidator.validate(shardFiles, sidecarSrcs, categories);
        problems.addAll(report.problems());

        if (!problems.isEmpty()) {
            throw failure(problems);
        }
        return report;
    }

    // ShardValidator checks a decision's file against the sidecar's in-scope set, not the
    // filesystem - whether it still exists on disk is this engine's job. A decision already
    // recorded in applied.log is exempt: its file may be long gone (moved) or, for a near-dup
    // keeper, deliberately never moved at all.
    //
    // This is the one gap the crash-safety design doesn't close for now, for a move-based decision
    // (see the design doc's "Why NearDupChosen needs its own resume guard" section). A crash
    // between the move and applied.log's own write for it leaves no automatic way to tell it apart
    // from a genuinely missing file. So the whole run refuses rather than guessing.
    //
    // The message spells out the manual fix, but only offers it unconditionally where it is
    // actually complete. A Classification decision has a second write after its move (a hash-index
    // row for funny, a _reasons.txt line otherwise) that a crash could equally have skipped -
    // manually marking it applied without checking that would silently and permanently lose that
    // second write, since the decision never reaches this method again once logged. NearDupReject
    // has no such second write, so its recovery line is safe as-is.
    private void assertEveryPendingFileExists(Path appliedLog, List<Decision> decisions, Set<String> applied)
            throws ApplyException {
        List<String> notFound = decisions.stream()
                .filter(d -> !applied.contains(d.file().toString()) && !mediaStore.exists(d.file()))
                .map(d -> notFoundMessage(d, appliedLog))
                .toList();
        if (!notFound.isEmpty()) {
            throw failure(notFound);
        }
    }

    private static String notFoundMessage(Decision d, Path appliedLog) {
        String base = "file not found and not already applied: " + d.file();
        String line = "\n      " + d.file();
        return switch (d) {
            case Classification c -> base + " - if it already moved to its destination in an earlier, crashed "
                    + "run, first confirm its "
                    + (c.category().equals(FUNNY_CATEGORY) ? "library hash-index row" : "Review reason note")
                    + " is also already there (a crash could have landed between the two writes); only once"
                    + " that's confirmed, add this exact line to " + appliedLog + " to mark it done, then"
                    + " re-run:" + line;
            case NearDupReject _ -> base + " - if it already moved to its destination in an earlier, crashed run,"
                    + " add this exact line to " + appliedLog + " to mark it done, then re-run:" + line;
            case NearDupChosen _ -> base + " - if it already copied to its destination in an earlier, crashed run,"
                    + " add this exact line to " + appliedLog + " to mark it done, then re-run:" + line;
        };
    }

    private static ApplyException failure(List<String> problems) {
        return new ApplyException("Shard validation failed - " + problems.size()
                + " problem(s), nothing applied:\n  - " + String.join("\n  - ", problems));
    }

    // Every near-dup decision (chosen or reject), keyed by group, regardless of whether it will be
    // skipped this run. A resumed run's chosen-note must still list every reject, including ones a
    // prior run already moved.
    private static Map<String, List<Decision>> groupNearDups(List<Decision> decisions) {
        Map<String, List<Decision>> byGroup = new HashMap<>();
        for (Decision decision : decisions) {
            switch (decision) {
                case NearDupChosen c -> byGroup.computeIfAbsent(c.group(), _ -> new ArrayList<>()).add(decision);
                case NearDupReject r -> byGroup.computeIfAbsent(r.group(), _ -> new ArrayList<>()).add(decision);
                case Classification _ -> {
                }
            }
        }
        return byGroup;
    }

    private void apply(Decision decision, Map<String, List<Decision>> nearDupGroups, ApplyOutcome outcome) {
        switch (decision) {
            case Classification c -> applyClassification(c, outcome);
            case NearDupChosen c -> applyNearDupChosen(c, nearDupGroups.get(c.group()), outcome);
            case NearDupReject r -> applyNearDupReject(r, outcome);
        }
    }

    // funny is the one category with a fixed destination: the library's flat Funny/ folder. It's
    // hashed into the library index and gets no reason note, since it's being kept, not set aside
    // for review. Every other category, junk included, routes generically to Review/<category>/
    // with a _reasons.txt note. There is no per-category destination configuration yet.
    //
    // The index append happens immediately, not batched after the loop. A decision an earlier,
    // crashed run already carried out is skipped on resume, so it never reaches this method again.
    // A batched append collected only from this run's own outcome would then permanently lose that
    // file's index row.
    private void applyClassification(Classification c, ApplyOutcome outcome) {
        outcome.byCategory.merge(c.category(), 1, Integer::sum);
        if (c.category().equals(FUNNY_CATEGORY)) {
            Path dest = mediaStore.move(c.file(), pathsPort.library().resolve("Funny"));
            hashIndexPort.append(List.of(new IndexEntry(sha256Port.hash(dest), dest)));
        } else {
            Path destDir = pathsPort.review().resolve(c.category());
            mediaStore.move(c.file(), destDir);
            mediaStore.appendLine(destDir.resolve(REASONS_FILE), c.file().getFileName() + " - " + c.reason());
        }
    }

    // The keeper is copied, not moved. It stays a normal Sorted keeper, with a courtesy copy left
    // for context alongside the rejects it was chosen over.
    //
    // Unlike every other decision type, its source file is never removed. So a crash between the
    // copy and applied.log's write leaves no signal that this decision already ran. A resumed run
    // would otherwise re-copy it, landing a stray " (2)" duplicate in Duplicates/, and re-append a
    // now-duplicated note line.
    //
    // Guarded explicitly here: the copy is skipped when the exact destination this decision would
    // produce already exists. The note is always (re)written wholesale, never appended to. That
    // makes re-running safe regardless of how far a prior attempt got.
    private void applyNearDupChosen(NearDupChosen c, List<Decision> group, ApplyOutcome outcome) {
        Path dupDir = duplicatesDir(c.file(), c.group());
        Path dest = dupDir.resolve(c.file().getFileName().toString());
        if (!mediaStore.exists(dest)) {
            mediaStore.copy(c.file(), dupDir);
        }
        mediaStore.write(dupDir.resolve(c.file().getFileName() + ".txt"), chosenNote(c, group));
        outcome.nearDupGroupsChosen.add(c.group());
    }

    private void applyNearDupReject(NearDupReject r, ApplyOutcome outcome) {
        mediaStore.move(r.file(), duplicatesDir(r.file(), r.group()));
        outcome.nearDupRejects++;
    }

    private Path duplicatesDir(Path file, String group) {
        return pathsPort.duplicates().resolve(yearMonthOf(file) + "_" + group);
    }

    private static String chosenNote(NearDupChosen chosen, List<Decision> group) {
        String rejects = group.stream()
                .filter(NearDupReject.class::isInstance)
                .map(NearDupReject.class::cast)
                .map(r -> r.file().getFileName() + " - " + r.reason())
                .collect(Collectors.joining("; "));
        return "Chose " + chosen.file().getFileName() + " - " + chosen.chosenReason() + ". Rejects: " + rejects;
    }

    // A Sorted-relative file always sits under a .../<yyyy>/<MM>/ pair of directories. Read off the
    // path segments directly rather than pattern-matching the string form. Pattern-matching a string
    // is separator-sensitive across platforms, and unnecessary here - this app's Sorted layout
    // already guarantees the segments. Falls back to a clearly-undated marker if that guarantee
    // somehow doesn't hold (e.g. a file sitting directly under the scope's base path).
    private static String yearMonthOf(Path file) {
        Path monthDir = file.getParent();
        Path yearDir = monthDir == null ? null : monthDir.getParent();
        if (yearDir == null) {
            return UNDATED;
        }
        String month = monthDir.getFileName().toString();
        String year = yearDir.getFileName().toString();
        return year.matches("\\d{4}") && month.matches("\\d{2}") ? year + "-" + month : UNDATED;
    }

    // Drops the montage contact sheets and tile images once every decision has been carried out -
    // always, even when zero decisions exist. index.json, the per-montage shards, applied.log, and
    // the merged decisions.json are all left in place.
    private void cleanupIntermediates(Path prepDirPath) {
        for (Path file : mediaStore.listFiles(prepDirPath)) {
            String name = file.getFileName().toString();
            if (name.startsWith("montage-") || name.startsWith("tile-")) {
                mediaStore.delete(file);
            }
        }
    }

    private static final class ApplyOutcome {
        final Map<String, Integer> byCategory = new TreeMap<>();
        final Set<String> nearDupGroupsChosen = new HashSet<>();
        int nearDupRejects;
    }
}
