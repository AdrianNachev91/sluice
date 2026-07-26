package photos.sluice.domain.cull;

import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;

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

        var problems = new ArrayList<String>();
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
                problems.add("file listed " + count + " times across shards/unreviewable: " + f);
            }
        });

        // A near-dup group belongs to exactly one montage (groups never span montages). The same
        // slug reused across two shards would let two unrelated groups pass independently, then merge
        // into one Duplicates folder at apply time - so reject any group seen in more than one shard.
        montagesByGroup.forEach((group, montages) -> {
            if (montages.size() > 1) {
                problems.add("near-dup group '" + group + "' spans " + montages.size()
                        + " shards (" + String.join(", ", montages) + "); a group must stay within one montage");
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
     * @param problems a {@link List} of {@link String} accumulated contract violations
     * @param heals a {@link List} of {@link String} accumulated non-fatal path heals
     * @param decisions a {@link List} of {@link Decision} accumulated merged, heal-corrected decisions
     */
    private void validateShard(ShardFile file, Set<Path> inScope, Map<String, Path> healableByBasename,
            Set<String> categorySet, String allowedClause, Map<String, Set<String>> montagesByGroup,
            List<String> problems, List<String> heals, List<Decision> decisions) {
        String montageId = file.expectedMontage();
        DecisionShard shard = file.shard();

        if (shard.montage().isBlank()) {
            problems.add(montageId + ": missing 'montage'");
        } else if (!shard.montage().equals(montageId)) {
            problems.add(montageId + ": 'montage' is '" + shard.montage() + "', expected '" + montageId + "'");
        }

        var chosenPerGroup = new HashMap<String, Integer>();
        var rejectsPerGroup = new HashMap<String, Integer>();
        int index = 0;
        for (Decision decision : shard.decisions()) {
            index++;
            String at = montageId + "[#" + index + "]";
            validateFields(decision, at, categorySet, allowedClause, chosenPerGroup, rejectsPerGroup, problems);
            decisions.add(healFile(decision, at, inScope, healableByBasename, problems, heals));
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
                problems.add(montageId + ": near-dup group '" + group + "' has " + chosen + " chosen (need exactly 1)");
            }
            if (rejects < 1) {
                problems.add(montageId + ": near-dup group '" + group + "' has " + rejects + " reject(s) (need >=1)");
            }
            if (!GROUP_SLUG.matcher(group).matches() || group.length() > GROUP_SLUG_MAX_LENGTH) {
                problems.add(montageId + ": near-dup group '" + group
                        + "' is not a valid slug (lowercase a-z0-9, hyphenated, max "
                        + GROUP_SLUG_MAX_LENGTH + " chars)");
            }
            montagesByGroup.computeIfAbsent(group, _ -> new TreeSet<>()).add(montageId);
        }
    }

    /**
     * Validates one decision's required fields and tallies near-dup group membership.
     *
     * @param decision {@link Decision} the decision to validate
     * @param at {@link String} the location label for problem messages
     * @param categorySet a {@link Set} of {@link String} the configured category set
     * @param allowedClause {@link String} message fragment listing allowed categories
     * @param chosenPerGroup a {@link Map} of {@link String} to {@link Integer} accumulated chosen-keeper count per group
     * @param rejectsPerGroup a {@link Map} of {@link String} to {@link Integer} accumulated reject count per group
     * @param problems a {@link List} of {@link String} accumulated contract violations
     */
    private void validateFields(Decision decision, String at, Set<String> categorySet, String allowedClause,
            Map<String, Integer> chosenPerGroup, Map<String, Integer> rejectsPerGroup, List<String> problems) {
        switch (decision) {
            case Classification c -> {
                if (!categorySet.contains(c.category())) {
                    problems.add(at + ": invalid action '" + c.category() + "' (" + allowedClause + ")");
                }
                if (c.reason().isBlank()) {
                    problems.add(at + ": missing 'reason'");
                }
            }
            case NearDupChosen c -> {
                if (c.group().isBlank()) {
                    problems.add(at + ": missing 'group'");
                } else {
                    chosenPerGroup.merge(c.group(), 1, Integer::sum);
                }
                if (c.chosenReason().isBlank()) {
                    problems.add(at + ": missing 'chosen_reason'");
                }
            }
            case NearDupReject r -> {
                if (r.group().isBlank()) {
                    problems.add(at + ": missing 'group'");
                } else {
                    rejectsPerGroup.merge(r.group(), 1, Integer::sum);
                }
                if (r.reason().isBlank()) {
                    problems.add(at + ": missing 'reason'");
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
     * @param at {@link String} the location label for problem messages
     * @param inScope a {@link Set} of {@link Path} every in-scope file the montages actually showed
     * @param healableByBasename a {@link Map} of {@link String} to {@link Path} in-scope files healable by unique basename
     * @param problems a {@link List} of {@link String} accumulated contract violations
     * @param heals a {@link List} of {@link String} accumulated non-fatal path heals
     * @return {@link Decision} the decision, with its file resolved or unchanged
     */
    private Decision healFile(Decision decision, String at, Set<Path> inScope,
            Map<String, Path> healableByBasename, List<String> problems, List<String> heals) {
        Path fileValue = decision.file();
        if (fileValue.toString().isBlank()) {
            problems.add(at + ": missing 'file'");
            return decision;
        }
        if (inScope.contains(fileValue)) {
            return decision;
        }
        Path healed = healableByBasename.get(fileValue.getFileName().toString());
        if (healed != null) {
            heals.add(at + ": '" + fileValue + "' -> '" + healed + "'");
            return withFile(decision, healed);
        }
        problems.add(at + ": file out of scope: " + fileValue);
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
