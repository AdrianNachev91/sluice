package photos.sluice.domain.cull;

import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
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
import photos.sluice.domain.cull.Finding.TooFewRejects;
import photos.sluice.domain.cull.Finding.WrongChosenCount;

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

// Validates a prep directory's decision shards against the shard contract - the single source of
// truth for what a well-formed cull looks like. It reports every representable-but-wrong problem at
// once, so the vision agent gets its whole to-fix list in one pass instead of one error per re-run;
// a single decision with a bad category, a blank reason, and an out-of-scope file reports all three.
// Pure: no I/O. The caller supplies the parsed shards, the authoritative in-scope file list (every
// montage sidecar's src), and the configured category set.
//
// The contract, stated positively:
//   - A decision's file must be one the montages actually showed - i.e. a member of the sidecar src
//     set - either directly or after a unique-basename heal. Whether that file still exists on disk
//     is a separate, later concern; this class does no I/O.
//   - The shard's montage field must be present and equal to the montage id its filename implies.
//   - A classification's category must be one of the configured categories, matched exactly.
//   - Each decision carries its required reasons (reason, or chosen_reason for a near-dup keeper).
//   - Each near-dup group has exactly one chosen keeper and at least one reject, and belongs to a
//     single montage - a group id reused across shards is rejected.
//   - A group id is a slug: lowercase a-z0-9 runs joined by single hyphens, at most 24 chars. It
//     becomes part of a Duplicates/YYYY-MM_<slug>/ folder name, so it must stay a short, portable
//     path segment.
//   - No file is acted on twice - across all shards, and against the unreviewable list too.
public final class ShardValidator {

    private static final Pattern GROUP_SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final int GROUP_SLUG_MAX_LENGTH = 24;

    // A parsed shard paired with the montage id its on-disk filename implies (decisions-003.json ->
    // montage-003). The caller derives the id from the filename - the only place that linkage is
    // known - so the validator can check the shard's self-declared montage field against it.
    public record ShardFile(String expectedMontage, DecisionShard shard) {
    }

    /**
     * Validates every shard against the shard contract and merges the results.
     *
     * @param shards a {@link List} of {@link ShardFile} the parsed shards paired with their expected montage ids
     * @param sidecarSrcs a {@link Collection} of {@link Path} every in-scope file the montages actually showed
     * @param categories a {@link List} of {@link String} the configured category set
     * @param unreviewable a {@link Collection} of {@link Path} files that could not be rendered for review
     * @return {@link ValidationReport} the aggregated validation report
     */
    public ValidationReport validate(List<ShardFile> shards, Collection<Path> sidecarSrcs,
            List<String> categories, Collection<Path> unreviewable) {
        Set<Path> inScope = Set.copyOf(sidecarSrcs);
        Map<String, Path> healableByBasename = healableByBasename(sidecarSrcs);
        Set<String> categorySet = Set.copyOf(categories);
        String allowedClause = categories.isEmpty()
                ? "no categories configured"
                : "allowed: " + String.join(", ", categories);

        var problems = new ArrayList<Finding>();
        var heals = new ArrayList<String>();
        var decisions = new ArrayList<Decision>();
        // group id -> the montage ids that reference it, for the cross-shard uniqueness check below.
        Map<String, Set<String>> montagesByGroup = new TreeMap<>();

        List<ShardFile> ordered = shards.stream()
                .sorted(Comparator.comparing(ShardFile::expectedMontage))
                .toList();

        for (ShardFile file : ordered) {
            validateShard(file, inScope, healableByBasename, categorySet, allowedClause,
                    montagesByGroup, problems, heals, decisions);
        }

        // A single file acted on twice would double-move at apply time. Checked across the merged
        // (heal-corrected) list, since a heal can collapse two differently-typed paths onto one src.
        // The unreviewable list joins the same count. It has no shard of its own, but ApplyEngine
        // moves it exactly like a decision - a file listed there AND in a decision would double-move
        // just the same. A duplicate within the unreviewable list alone would too.
        Map<String, Long> countByFile = new TreeMap<>();
        for (Decision d : decisions) {
            String f = d.file().toString();
            if (!f.isBlank()) {
                countByFile.merge(f, 1L, Long::sum);
            }
        }
        for (Path u : unreviewable) {
            countByFile.merge(u.toString(), 1L, Long::sum);
        }
        countByFile.forEach((f, count) -> {
            if (count > 1) {
                problems.add(new DuplicateFileReference(f, count));
            }
        });

        // A near-dup group belongs to exactly one montage (groups never span montages). The same
        // slug reused across two shards would let two unrelated groups pass independently, then merge
        // into one Duplicates folder at apply time - so reject any group seen in more than one shard.
        montagesByGroup.forEach((group, montages) -> {
            if (montages.size() > 1) {
                problems.add(new GroupSpansMultipleMontages(group, List.copyOf(montages)));
            }
        });

        return new ValidationReport(problems, heals, decisions);
    }

    /**
     * Validates one shard's decisions and appends its findings to the shared accumulators.
     *
     * @param file {@link ShardFile} the shard paired with its expected montage id
     * @param inScope a {@link Set} of {@link Path} every in-scope file the montages actually showed
     * @param healableByBasename a {@link Map} of {@link String} to {@link Path} in-scope files healable by unique basename
     * @param categorySet a {@link Set} of {@link String} the configured category set
     * @param allowedClause {@link String} message fragment listing allowed categories
     * @param montagesByGroup a {@link Map} of {@link String} to {@link Set} of {@link String} group id to the montage ids referencing it
     * @param problems a {@link List} of {@link Finding} accumulated contract violations
     * @param heals a {@link List} of {@link String} accumulated non-fatal path heals
     * @param decisions a {@link List} of {@link Decision} accumulated merged, heal-corrected decisions
     */
    private void validateShard(ShardFile file, Set<Path> inScope, Map<String, Path> healableByBasename,
            Set<String> categorySet, String allowedClause, Map<String, Set<String>> montagesByGroup,
            List<Finding> problems, List<String> heals, List<Decision> decisions) {
        String montageId = file.expectedMontage();
        DecisionShard shard = file.shard();

        if (shard.montage().isBlank()) {
            problems.add(new MissingMontageField(montageId));
        } else if (!shard.montage().equals(montageId)) {
            problems.add(new MontageFieldMismatch(montageId, shard.montage()));
        }

        var chosenPerGroup = new HashMap<String, Integer>();
        var rejectsPerGroup = new HashMap<String, Integer>();
        int index = 0;
        for (Decision decision : shard.decisions()) {
            index++;
            validateFields(decision, montageId, index, categorySet, allowedClause, chosenPerGroup, rejectsPerGroup, problems);
            decisions.add(healFile(decision, montageId, index, inScope, healableByBasename, problems, heals));
        }

        // Each near-dup group within a shard needs exactly one chosen keeper and at least one reject.
        // Groups never span montages, so a group is complete within the one shard that declares it.
        Set<String> groups = new TreeSet<>();
        groups.addAll(chosenPerGroup.keySet());
        groups.addAll(rejectsPerGroup.keySet());
        for (String group : groups) {
            int chosen = chosenPerGroup.getOrDefault(group, 0);
            int rejects = rejectsPerGroup.getOrDefault(group, 0);
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
     * Validates one decision's required fields and tallies near-dup group membership.
     *
     * @param decision {@link Decision} the decision to validate
     * @param montage {@link String} the montage id this decision belongs to
     * @param index int the decision's 1-based position within its shard
     * @param categorySet a {@link Set} of {@link String} the configured category set
     * @param allowedClause {@link String} message fragment listing allowed categories
     * @param chosenPerGroup a {@link Map} of {@link String} to {@link Integer} accumulated chosen-keeper count per group
     * @param rejectsPerGroup a {@link Map} of {@link String} to {@link Integer} accumulated reject count per group
     * @param problems a {@link List} of {@link Finding} accumulated contract violations
     */
    private void validateFields(Decision decision, String montage, int index, Set<String> categorySet, String allowedClause,
            Map<String, Integer> chosenPerGroup, Map<String, Integer> rejectsPerGroup, List<Finding> problems) {
        switch (decision) {
            case Classification c -> {
                if (!categorySet.contains(c.category())) {
                    problems.add(new InvalidCategory(montage, index, c.category(), allowedClause));
                }
                if (c.reason().isBlank()) {
                    problems.add(new MissingReason(montage, index));
                }
            }
            case NearDupChosen c -> {
                if (c.group().isBlank()) {
                    problems.add(new MissingGroup(montage, index));
                } else {
                    chosenPerGroup.merge(c.group(), 1, Integer::sum);
                }
                if (c.chosenReason().isBlank()) {
                    problems.add(new MissingChosenReason(montage, index));
                }
            }
            case NearDupReject r -> {
                if (r.group().isBlank()) {
                    problems.add(new MissingGroup(montage, index));
                } else {
                    rejectsPerGroup.merge(r.group(), 1, Integer::sum);
                }
                if (r.reason().isBlank()) {
                    problems.add(new MissingReason(montage, index));
                }
            }
        }
    }

    /**
     * Returns the decision with its file resolved into scope: unchanged if already in scope, or
     * re-pointed to the unique sidecar src that shares its basename (a culler retyped the path's
     * \YYYY\MM\ segment). A blank or unhealable-out-of-scope file is a problem and the decision is
     * returned untouched.
     *
     * @param decision {@link Decision} the decision to resolve
     * @param montage {@link String} the montage id this decision belongs to
     * @param index int the decision's 1-based position within its shard
     * @param inScope a {@link Set} of {@link Path} every in-scope file the montages actually showed
     * @param healableByBasename a {@link Map} of {@link String} to {@link Path} in-scope files healable by unique basename
     * @param problems a {@link List} of {@link Finding} accumulated contract violations
     * @param heals a {@link List} of {@link String} accumulated non-fatal path heals
     * @return {@link Decision} the decision, with its file resolved or unchanged
     */
    private Decision healFile(Decision decision, String montage, int index, Set<Path> inScope,
            Map<String, Path> healableByBasename, List<Finding> problems, List<String> heals) {
        Path fileValue = decision.file();
        if (fileValue.toString().isBlank()) {
            problems.add(new MissingFile(montage, index));
            return decision;
        }
        if (inScope.contains(fileValue)) {
            return decision;
        }
        Path healed = healableByBasename.get(fileValue.getFileName().toString());
        if (healed != null) {
            heals.add(Finding.at(montage, index) + ": '" + fileValue + "' -> '" + healed + "'");
            return withFile(decision, healed);
        }
        problems.add(new FileOutOfScope(montage, index, fileValue));
        return decision;
    }

    /**
     * basename -> its single owning source file. A basename shared by two or more distinct in-scope
     * files (the same filename living in different month folders - a camera resets its counter, two
     * cameras both emit IMG_0001.jpg) is ambiguous and dropped: a drifted decision path whose basename
     * isn't unique can't be resolved to one owner, so it never auto-heals. The count is over distinct
     * source paths, which is why a Set collects them per basename.
     *
     * @param sidecarSrcs a {@link Collection} of {@link Path} every in-scope file the montages actually showed
     * @return a {@link Map} of {@link String} to {@link Path} in-scope files healable by unique basename
     */
    private static Map<String, Path> healableByBasename(Collection<Path> sidecarSrcs) {
        Map<String, Set<Path>> srcsByBasename = new HashMap<>();
        for (Path src : sidecarSrcs) {
            srcsByBasename.computeIfAbsent(src.getFileName().toString(), _ -> new HashSet<>()).add(src);
        }
        Map<String, Path> unique = new HashMap<>();
        srcsByBasename.forEach((basename, srcs) -> {
            if (srcs.size() == 1) {
                unique.put(basename, srcs.iterator().next());
            }
        });
        return unique;
    }

    /**
     * Returns a copy of the decision with its file replaced.
     *
     * @param decision {@link Decision} the decision to copy
     * @param file {@link Path} the replacement file path
     * @return {@link Decision} the decision with the replaced file
     */
    private static Decision withFile(Decision decision, Path file) {
        return switch (decision) {
            case Classification c -> new Classification(file, c.category(), c.reason());
            case NearDupChosen c -> new NearDupChosen(file, c.group(), c.chosenReason());
            case NearDupReject r -> new NearDupReject(file, r.group(), r.reason());
        };
    }
}
