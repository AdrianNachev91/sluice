package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.util.List;

// A single problem found while validating a prep directory's decision shards against the shard
// contract - one record case per distinct violation shape ShardValidator checks for. describe()
// renders the exact prose an aggregated ApplyException reports; callers read that rendered text,
// never a Finding's fields directly. Every case here is a culler content mistake (wrong category,
// missing reason, a malformed near-dup group) that only a re-cull can fix. There is no
// engine-level auto-repair for any of them. A later round adds finding cases that DO carry a
// remedy (a missing source file, a corrupt index) alongside these, at which point a shared
// remedy-classification accessor lands on this interface too.
public sealed interface Finding {

    /**
     * Renders this finding as the exact prose an aggregated ApplyException reports.
     *
     * @return {@link String} the finding's human-readable description
     */
    String describe();

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
