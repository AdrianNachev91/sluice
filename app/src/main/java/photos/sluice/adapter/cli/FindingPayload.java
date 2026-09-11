package photos.sluice.adapter.cli;

import photos.sluice.domain.cull.Finding;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

/**
 * One problem with a run, in the shape a machine reads it.
 *
 * <p>The type name and the fields, never the sentence the app would show a person. A caller acts on
 * a finding by its type and the file, montage or shard it names.
 *
 * <p>{@code detail} is nested rather than flattened alongside {@code type}, because which keys it
 * holds depends on which type it is.
 *
 * @param type {@link String} the finding's own type name
 * @param remedy {@link Finding.Remedy} how, if at all, the app can resolve it unprompted
 * @param detail a {@link SequencedMap} of {@link String} to {@link Object} the fields this type
 *        carries, in a fixed order
 */
public record FindingPayload(String type, Finding.Remedy remedy, SequencedMap<String, Object> detail) {

    /**
     * Copies the fields, so nothing can change a payload after it was read.
     *
     * @param type {@link String} the finding's own type name
     * @param remedy {@link Finding.Remedy} how, if at all, the app can resolve it unprompted
     * @param detail a {@link SequencedMap} of {@link String} to {@link Object} the fields this type carries
     */
    public FindingPayload {
        detail = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(detail));
    }

    /**
     * Reads one finding onto the wire.
     *
     * @param finding {@link Finding} the problem to report
     * @return {@link FindingPayload} its machine-readable shape
     */
    public static FindingPayload of(final Finding finding) {
        return new FindingPayload(finding.getClass().getSimpleName(), finding.remedy(), detailOf(finding));
    }

    /**
     * The fields one finding carries, keyed as the wire names them.
     *
     * @param finding {@link Finding} the problem to report
     * @return a {@link SequencedMap} of {@link String} to {@link Object} its own fields
     */
    private static SequencedMap<String, Object> detailOf(final Finding finding) {
        return switch (finding) {
            case final Finding.MissingMontageField f -> Fields.of("montage", f.montage());
            case final Finding.MontageFieldMismatch f -> Fields.of("montage", f.montage(), "declared", f.declared());
            case final Finding.InvalidCategory f -> Fields.of("montage", f.montage(), "index", f.index(),
                    "category", f.category(), "allowed", f.allowedClause());
            case final Finding.MissingReason f -> Fields.of("montage", f.montage(), "index", f.index());
            case final Finding.FillerReason f -> Fields.of("montage", f.montage(), "index", f.index(),
                    "reason", f.reason());
            case final Finding.MissingGroup f -> Fields.of("montage", f.montage(), "index", f.index());
            case final Finding.MissingChosenReason f -> Fields.of("montage", f.montage(), "index", f.index());
            case final Finding.WrongChosenCount f -> Fields.of("montage", f.montage(), "group", f.group(),
                    "chosen", f.chosen());
            case final Finding.TooFewRejects f -> Fields.of("montage", f.montage(), "group", f.group(),
                    "rejects", f.rejects());
            case final Finding.InvalidGroupSlug f -> Fields.of("montage", f.montage(), "group", f.group(),
                    "maxLength", f.maxLength());
            case final Finding.DuplicateFileReference f -> Fields.of("file", f.file(), "count", f.count());
            case final Finding.VerdictUnreviewableOverlap f -> Fields.of("file", text(f.verdict().file()));
            case final Finding.GroupSpansMultipleMontages f -> Fields.of("group", f.group(), "montages", f.montages());
            case final Finding.PhotosNotJudged f -> Fields.of("montage", f.montage(), "photos", f.photos());
            case final Finding.PhotoFromAnotherSheet f -> Fields.of("montage", f.montage(), "index", f.index(),
                    "file", text(f.file()));
            case final Finding.MissingFile f -> Fields.of("montage", f.montage(), "index", f.index());
            case final Finding.FileOutOfScope f -> Fields.of("montage", f.montage(), "index", f.index(),
                    "file", text(f.file()));
            case final Finding.SourceOutsideSorted f -> Fields.of("file", text(f.file()),
                    "sortedRoot", text(f.sortedRoot()));
            case final Finding.StrayShard f -> Fields.of("shardFile", f.shardFile());
            case final Finding.MissingShard f -> Fields.of("montage", f.montage(), "expectedFile", f.expectedFile());
            case final Finding.CorruptShard f -> Fields.of("montage", f.montage(), "shardFile", f.shardFile());
            case final Finding.CorruptIndex f -> Fields.of("indexPath", text(f.indexPath()));
            case final Finding.UnreadablePrepDir f -> Fields.of("prepDir", text(f.prepDir()));
            case final Finding.CorruptSidecar f -> Fields.of("montage", f.montage());
            case final Finding.MissingSource f -> Fields.of("file", text(f.file()),
                    "moveRecordLog", text(f.moveRecordLog()));
        };
    }

    /**
     * Writes a path the way this machine spells it.
     *
     * @param path {@link Path} the path to write
     * @return {@link String} the path as text
     */
    private static String text(final Path path) {
        return path.toString();
    }
}
