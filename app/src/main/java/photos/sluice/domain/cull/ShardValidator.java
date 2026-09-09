package photos.sluice.domain.cull;

import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.Finding.VerdictUnreviewableOverlap;
import photos.sluice.domain.cull.Finding.DuplicateFileReference;
import photos.sluice.domain.cull.Finding.FileOutOfScope;
import photos.sluice.domain.cull.Finding.GroupSpansMultipleMontages;
import photos.sluice.domain.cull.Finding.InvalidCategory;
import photos.sluice.domain.cull.Finding.InvalidGroupSlug;
import photos.sluice.domain.cull.Finding.MissingChosenReason;
import photos.sluice.domain.cull.Finding.MissingFile;
import photos.sluice.domain.cull.Finding.MissingGroup;
import photos.sluice.domain.cull.Finding.MissingMontageField;
import photos.sluice.domain.cull.Finding.MissingReason;
import photos.sluice.domain.cull.Finding.MontageFieldMismatch;
import photos.sluice.domain.cull.Finding.PhotosNotJudged;
import photos.sluice.domain.cull.Finding.TooFewRejects;
import photos.sluice.domain.cull.Finding.WrongChosenCount;
import photos.sluice.domain.cull.Verdict.Keep;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Validates a prep directory's decision shards against the shard contract, the single source of
 * truth for what a well-formed cull looks like. It reports every representable-but-wrong problem
 * at once, so the vision agent gets its whole to-fix list in one pass instead of one error per
 * re-run. A single decision with a bad category, a blank reason, and an out-of-scope file reports
 * all three.
 *
 * <p>Pure: no I/O. The caller supplies the parsed shards, the authoritative in-scope file list
 * (every montage sidecar's {@code src}), and the category set to judge against. That set is the one
 * {@link PrepDir} recorded at prep time, never whatever config holds at the moment of validation.
 * See {@link PrepDir} for why.
 *
 * <p>It is also where the keeps stop. A shard carries a {@link Verdict} per photo, and the report
 * it produces carries {@link Decision}s alone. Nothing past this class can be handed a keep, and
 * nothing past it needs a case for one.
 *
 * <p>The contract, stated positively:
 *
 * <ul>
 *   <li>Every photo a sheet showed carries a verdict in that sheet's shard, a keep included.
 *   <li>Every verdict names a photo its own sheet showed.
 *   <li>A verdict's file must be one the montages actually showed, i.e. a member of the sidecar
 *       {@code src} set, either directly or after a unique-basename heal. Whether that file still
 *       exists on disk is a separate, later concern; this class does no I/O.
 *   <li>The shard's {@code montage} field must be present and equal to the montage id its filename
 *       implies.
 *   <li>A classification's category must be one of the prep dir's own categories, matched exactly.
 *   <li>Each decision carries its required reasons ({@code reason}, or {@code chosen_reason} for a
 *       near-dup keeper).
 *   <li>Each near-dup group has exactly one chosen keeper and at least one reject, and belongs to a
 *       single montage - a group id reused across shards is rejected.
 *   <li>A group id is a slug: lowercase {@code a-z0-9} runs joined by single hyphens, at most 24
 *       characters.
 *   <li>No file is acted on twice, across all shards and against the unreviewable list too.
 * </ul>
 */
public final class ShardValidator {

    private static final Pattern GROUP_SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    static final int GROUP_SLUG_MAX_LENGTH = 24;

    /**
     * How long a near-duplicate group name may be, for the prompts that ask an agent for one. A
     * prompt naming a limit this class does not hold buys its own refusal, once the answer has
     * already been paid for.
     *
     * @return int the longest group name this accepts
     */
    public static int groupSlugMaxLength() {
        return GROUP_SLUG_MAX_LENGTH;
    }

    /**
     * A parsed shard paired with the montage id its on-disk filename implies (e.g.
     * {@code decisions-003.json} implies {@code montage-003}). The id is passed in because the
     * filename is the only place that linkage is known, and this class sees no filenames.
     *
     * <p>{@code sheetPhotos} is what that one sheet showed, from its own sidecar, and it is what
     * coverage is measured against. An empty list is not a shard covering nothing. It is a caller
     * saying it could not read that sheet's sidecar, so the coverage rule has nothing to judge and
     * does not fire.
     *
     * @param expectedMontage {@link String} the montage id the shard's filename implies
     * @param shard {@link DecisionShard} the parsed shard
     * @param sheetPhotos a {@link List} of {@link Path} the photos that sheet showed, or empty
     *     where its sidecar could not be read
     */
    public record ShardFile(String expectedMontage, DecisionShard shard, List<Path> sheetPhotos) {

        /**
         * Defensively copies the mutable list.
         *
         * @param expectedMontage {@link String} the montage id the shard's filename implies
         * @param shard {@link DecisionShard} the parsed shard
         * @param sheetPhotos a {@link List} of {@link Path} the photos that sheet showed
         */
        public ShardFile {
            sheetPhotos = List.copyOf(sheetPhotos);
        }
    }

    /**
     * Validates every shard against the shard contract and merges the results.
     *
     * @param shards a {@link List} of {@link ShardFile} the parsed shards paired with their expected montage ids
     * @param sidecarSrcs a {@link Collection} of {@link Path} every in-scope file the montages actually showed
     * @param categories a {@link List} of {@link String} the category set the prep dir recorded
     * @param unreviewable a {@link Collection} of {@link Path} files that could not be rendered for review
     * @return {@link ValidationReport} the aggregated validation report
     */
    public ValidationReport validate(final List<ShardFile> shards, final Collection<Path> sidecarSrcs,
                                     final List<String> categories, final Collection<Path> unreviewable) {
        final Set<Path> inScope = Set.copyOf(sidecarSrcs);
        final Map<String, Path> healableByBasename = healableByBasename(sidecarSrcs);
        final Set<String> categorySet = Set.copyOf(categories);
        final String allowedClause = categories.isEmpty()
                ? "no categories configured"
                : "allowed: " + String.join(", ", categories);

        final var problems = new ArrayList<Finding>();
        final var heals = new ArrayList<String>();
        final var decisions = new ArrayList<Decision>();
        // Every verdict, keeps included, so the duplicate-reference check below can count a keep as
        // a reference.
        final var healedVerdicts = new ArrayList<Verdict>();
        // group id -> the montage ids that reference it.
        final Map<String, Set<String>> montagesByGroup = new TreeMap<>();

        final List<ShardFile> ordered = shards.stream()
                .sorted(Comparator.comparing(ShardFile::expectedMontage))
                .toList();

        for (final ShardFile file : ordered) {
            this.validateShard(file, inScope, healableByBasename, categorySet, allowedClause,
                    montagesByGroup, problems, heals, decisions, healedVerdicts);
        }

        // A single file acted on twice would double-move at apply time. Checked across the merged
        // (heal-corrected) list, since a heal can collapse two differently-typed paths onto one src.
        // The unreviewable list joins the same count, its files moving exactly like a decision's.
        //
        // Counted over verdicts, so a keep counts as a reference. One file named both as a keep
        // and as a decision is a shard contradicting itself. Counting only decisions would resolve
        // that silently toward the one that moves the photo.
        final Map<String, List<Verdict>> verdictsByFile = new TreeMap<>();
        final Map<String, Integer> verdictCountByFile = new TreeMap<>();
        for (final Verdict verdict : healedVerdicts) {
            final String file = verdict.file().toString();
            if (!file.isBlank()) {
                verdictCountByFile.merge(file, 1, Integer::sum);
                verdictsByFile.computeIfAbsent(file, _ -> new ArrayList<>()).add(verdict);
            }
        }
        final Map<String, Integer> unreviewableCountByFile = new TreeMap<>();
        for (final Path u : unreviewable) {
            unreviewableCountByFile.merge(u.toString(), 1, Integer::sum);
        }
        final Set<String> allReferencedFiles = new TreeSet<>();
        allReferencedFiles.addAll(verdictCountByFile.keySet());
        allReferencedFiles.addAll(unreviewableCountByFile.keySet());
        allReferencedFiles.forEach(f -> checkDuplicateReferences(f,
                verdictsByFile.getOrDefault(f, List.of()), verdictCountByFile.getOrDefault(f, 0),
                unreviewableCountByFile.getOrDefault(f, 0), problems));

        // The same slug reused across two shards would let two unrelated groups pass independently,
        // then merge into one Duplicates folder at apply time.
        montagesByGroup.forEach((group, montages) -> {
            if (montages.size() > 1) {
                problems.add(new GroupSpansMultipleMontages(group, List.copyOf(montages)));
            }
        });

        return new ValidationReport(problems, heals, decisions);
    }

    /**
     * One file's worth of the duplicate-reference check. Exactly one verdict paired with exactly
     * one unreviewable entry is a {@link VerdictUnreviewableOverlap}, which carries a real CHOICE
     * remedy. Any other multi-reference shape is the general {@link DuplicateFileReference}. A
     * no-op when f is referenced at most once.
     *
     * @param f {@link String} the file path, as it appears in a verdict or the unreviewable list
     * @param verdictsForFile a {@link List} of {@link Verdict} every verdict naming f, keeps included
     * @param verdictCount int how many verdicts name f, keeps included
     * @param unreviewableCount int how many times f appears in the unreviewable list
     * @param problems a {@link List} of {@link Finding} accumulated contract violations
     */
    private static void checkDuplicateReferences(final String f, final List<Verdict> verdictsForFile,
                                                 final int verdictCount, final int unreviewableCount,
                                                 final List<Finding> problems) {
        final long count = verdictCount + unreviewableCount;
        if (count <= 1) {
            return;
        }
        if (verdictCount == 1 && unreviewableCount == 1) {
            problems.add(new VerdictUnreviewableOverlap(verdictsForFile.getFirst()));
        } else {
            problems.add(new DuplicateFileReference(f, count));
        }
    }

    /**
     * Validates one shard's decisions and appends its findings to the shared accumulators.
     *
     * @param file {@link ShardFile} the shard paired with its expected montage id
     * @param inScope a {@link Set} of {@link Path} every in-scope file the montages actually showed
     * @param healableByBasename a {@link Map} of {@link String} to {@link Path} in-scope files healable by unique
     * basename
     * @param categorySet a {@link Set} of {@link String} the category set the prep dir recorded
     * @param allowedClause {@link String} message fragment listing allowed categories
     * @param montagesByGroup a {@link Map} of {@link String} to {@link Set} of {@link String} group id to the
     * montage ids referencing it
     * @param problems a {@link List} of {@link Finding} accumulated contract violations
     * @param heals a {@link List} of {@link String} accumulated non-fatal path heals
     * @param decisions a {@link List} of {@link Decision} accumulated merged, heal-corrected decisions
     * @param healedVerdicts a {@link List} of {@link Verdict} accumulated merged, heal-corrected
     *     verdicts, keeps included
     */
    private void validateShard(final ShardFile file, final Set<Path> inScope,
                               final Map<String, Path> healableByBasename,
                               final Set<String> categorySet, final String allowedClause, final Map<String,
                    Set<String>> montagesByGroup,
                               final List<Finding> problems, final List<String> heals, final List<Decision> decisions,
                               final List<Verdict> healedVerdicts) {
        final String montageId = file.expectedMontage();
        final DecisionShard shard = file.shard();

        if (shard.montage().isBlank()) {
            problems.add(new MissingMontageField(montageId));
        } else if (!shard.montage().equals(montageId)) {
            problems.add(new MontageFieldMismatch(montageId, shard.montage()));
        }

        final var chosenPerGroup = new HashMap<String, Integer>();
        final var rejectsPerGroup = new HashMap<String, Integer>();
        final Set<Path> judged = new HashSet<>();
        final Set<Path> sheet = Set.copyOf(file.sheetPhotos());
        int index = 0;
        for (final Verdict verdict : shard.verdicts()) {
            index++;
            this.validateFields(verdict, montageId, index, categorySet, allowedClause, chosenPerGroup,
                    rejectsPerGroup, problems);
            final Verdict resolved = this.healFile(verdict, montageId, index, inScope, healableByBasename, problems,
                    heals);
            judged.add(resolved.file());
            healedVerdicts.add(resolved);
            checkPhotoBelongsToSheet(montageId, index, resolved, sheet, inScope, problems);
            // A keep reaching the report's list would move a photo somebody asked to leave alone.
            if (resolved instanceof final Decision decision) {
                decisions.add(decision);
            }
        }
        checkCoverage(montageId, file.sheetPhotos(), judged, problems);

        // Groups never span montages, so a group is complete within the one shard that declares it.
        final Set<String> groups = new TreeSet<>();
        groups.addAll(chosenPerGroup.keySet());
        groups.addAll(rejectsPerGroup.keySet());
        for (final String group : groups) {
            final int chosen = chosenPerGroup.getOrDefault(group, 0);
            final int rejects = rejectsPerGroup.getOrDefault(group, 0);
            if (chosen != 1) {
                problems.add(new WrongChosenCount(montageId, group, chosen));
            }
            if (rejects < 1) {
                problems.add(new TooFewRejects(montageId, group, rejects));
            }
            if (!GROUP_SLUG.matcher(group).matches() || group.length() > GROUP_SLUG_MAX_LENGTH) {
                problems.add(new InvalidGroupSlug(montageId, group, GROUP_SLUG_MAX_LENGTH));
            }
            montagesByGroup.computeIfAbsent(group, _ -> new TreeSet<>()).add(montageId);
        }
    }

    /**
     * Reports a verdict about a photo that is in the run but was shown on a different sheet.
     *
     * <p>Silent where the sheet list is empty, like the coverage rule it pairs with. A caller that
     * could not read the sidecar has given this nothing to judge against.
     *
     * <p>Silent too where the file reached no sheet at all, which {@link Finding.FileOutOfScope}
     * has already reported. One verdict raises one of the two, never both.
     *
     * @param montage {@link String} the montage id
     * @param index int the verdict's 1-based position within its shard
     * @param verdict {@link Verdict} the verdict, with its file already resolved
     * @param sheet a {@link Set} of {@link Path} what this sheet showed, empty where its sidecar
     *     could not be read
     * @param inScope a {@link Set} of {@link Path} every in-scope file the montages actually showed
     * @param problems a {@link List} of {@link Finding} accumulated contract violations
     */
    private static void checkPhotoBelongsToSheet(final String montage, final int index, final Verdict verdict,
                                                 final Set<Path> sheet, final Set<Path> inScope,
                                                 final List<Finding> problems) {
        final Path file = verdict.file();
        if (!sheet.isEmpty() && inScope.contains(file) && !sheet.contains(file)) {
            problems.add(new Finding.PhotoFromAnotherSheet(montage, index, file));
        }
    }

    /**
     * Reports the photos a sheet showed that its own shard says nothing about.
     *
     * <p>Measured over files rather than over positions in the list. A shard is free to list its
     * verdicts in any order, and two verdicts naming one photo leave another photo uncovered, which
     * is what this reports.
     *
     * @param montage {@link String} the montage id
     * @param sheetPhotos a {@link List} of {@link Path} what that sheet showed, empty where its
     *     sidecar could not be read
     * @param judged a {@link Set} of {@link Path} the files the shard's verdicts name, healed
     * @param problems a {@link List} of {@link Finding} accumulated contract violations
     */
    private static void checkCoverage(final String montage, final List<Path> sheetPhotos, final Set<Path> judged,
                                      final List<Finding> problems) {
        // Distinct over the path, never over the name it is rendered as. One sheet can show two
        // photos of the same name from different months, and collapsing those would report one
        // unjudged photo where two are.
        final List<String> unjudged = sheetPhotos.stream()
                .filter(photo -> !judged.contains(photo))
                .distinct()
                .map(photo -> photo.getFileName().toString())
                .toList();
        if (!unjudged.isEmpty()) {
            problems.add(new PhotosNotJudged(montage, unjudged));
        }
    }

    /**
     * Validates one verdict's required fields and tallies near-dup group membership.
     *
     * @param verdict {@link Verdict} the verdict to validate
     * @param montage {@link String} the montage id this decision belongs to
     * @param index int the decision's 1-based position within its shard
     * @param categorySet a {@link Set} of {@link String} the category set the prep dir recorded
     * @param allowedClause {@link String} message fragment listing allowed categories
     * @param chosenPerGroup a {@link Map} of {@link String} to {@link Integer} accumulated chosen-keeper count per
     * group
     * @param rejectsPerGroup a {@link Map} of {@link String} to {@link Integer} accumulated reject count per group
     * @param problems a {@link List} of {@link Finding} accumulated contract violations
     */
    private void validateFields(final Verdict verdict, final String montage, final int index,
                                final Set<String> categorySet, final String allowedClause,
                                final Map<String, Integer> chosenPerGroup, final Map<String, Integer> rejectsPerGroup
            , final List<Finding> problems) {
        switch (verdict) {
            // A keep carries nothing but its file, which healFile checks like any other verdict's.
            case Keep _ -> { }
            case final Classification c -> {
                if (!categorySet.contains(c.category())) {
                    problems.add(new InvalidCategory(montage, index, c.category(), allowedClause));
                }
                if (c.reason().isBlank()) {
                    problems.add(new MissingReason(montage, index));
                }
            }
            case final NearDupChosen c -> {
                if (c.group().isBlank()) {
                    problems.add(new MissingGroup(montage, index));
                } else {
                    chosenPerGroup.merge(c.group(), 1, Integer::sum);
                }
                if (c.chosenReason().isBlank()) {
                    problems.add(new MissingChosenReason(montage, index));
                }
            }
            case final NearDupReject reject -> {
                if (reject.group().isBlank()) {
                    problems.add(new MissingGroup(montage, index));
                } else {
                    rejectsPerGroup.merge(reject.group(), 1, Integer::sum);
                }
                if (reject.reason().isBlank()) {
                    problems.add(new MissingReason(montage, index));
                }
            }
        }
    }

    /**
     * Returns the verdict with its file resolved into scope. Unchanged if it is already in scope,
     * or re-pointed to the unique sidecar src sharing its basename, which is a culler having
     * retyped the path's \YYYY\MM\ segment. A blank or unhealable-out-of-scope file is a problem,
     * and the verdict is returned untouched.
     *
     * @param verdict {@link Verdict} the verdict to resolve
     * @param montage {@link String} the montage id this verdict belongs to
     * @param index int the verdict's 1-based position within its shard
     * @param inScope a {@link Set} of {@link Path} every in-scope file the montages actually showed
     * @param healableByBasename a {@link Map} of {@link String} to {@link Path} in-scope files healable by unique
     * basename
     * @param problems a {@link List} of {@link Finding} accumulated contract violations
     * @param heals a {@link List} of {@link String} accumulated non-fatal path heals
     * @return {@link Verdict} the verdict, with its file resolved or unchanged
     */
    private Verdict healFile(final Verdict verdict, final String montage, final int index, final Set<Path> inScope,
                             final Map<String, Path> healableByBasename, final List<Finding> problems,
                             final List<String> heals) {
        final Path fileValue = verdict.file();
        if (fileValue.toString().isBlank()) {
            problems.add(new MissingFile(montage, index));
            return verdict;
        }
        if (inScope.contains(fileValue)) {
            return verdict;
        }
        final Path healed = healableByBasename.get(fileValue.getFileName().toString());
        if (healed != null) {
            heals.add(Finding.locationPrefix(montage, index) + ": '" + fileValue + "' -> '" + healed + "'");
            return withFile(verdict, healed);
        }
        problems.add(new FileOutOfScope(montage, index, fileValue));
        return verdict;
    }

    /**
     * basename -> its single owning source file. A basename shared by two or more distinct in-scope
     * files is ambiguous and dropped. Say the same filename in two month folders, after a camera
     * resets its counter. A drifted decision path whose basename isn't unique can't be resolved to
     * one owner, so it never auto-heals.
     *
     * @param sidecarSrcs a {@link Collection} of {@link Path} every in-scope file the montages actually showed
     * @return a {@link Map} of {@link String} to {@link Path} in-scope files healable by unique basename
     */
    private static Map<String, Path> healableByBasename(final Collection<Path> sidecarSrcs) {
        final Map<String, Set<Path>> srcsByBasename = new HashMap<>();
        for (final Path src : sidecarSrcs) {
            srcsByBasename.computeIfAbsent(src.getFileName().toString(), _ -> new HashSet<>()).add(src);
        }
        final Map<String, Path> unique = new HashMap<>();
        srcsByBasename.forEach((basename, srcs) -> {
            if (srcs.size() == 1) {
                unique.put(basename, srcs.iterator().next());
            }
        });
        return unique;
    }

    /**
     * Returns a copy of the verdict with its file replaced.
     *
     * @param verdict {@link Verdict} the verdict to copy
     * @param file {@link Path} the replacement file path
     * @return {@link Verdict} the verdict with the replaced file
     */
    private static Verdict withFile(final Verdict verdict, final Path file) {
        return switch (verdict) {
            case Keep _ -> new Keep(file);
            case final Classification c -> new Classification(file, c.category(), c.reason());
            case final NearDupChosen c -> new NearDupChosen(file, c.group(), c.chosenReason());
            case final NearDupReject reject -> new NearDupReject(file, reject.group(), reject.reason());
        };
    }
}
