package photos.sluice.application.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.imports.ImportKind;
import photos.sluice.domain.imports.ImportSummary;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportEngineTest {

    @Nested
    class Copying {

        @Test
        void writesEveryFileWhereItSatUnderTheFolderChosen(@TempDir final Path card, @TempDir final Path root) {
            write(card.resolve("loose.jpg"), "loose");
            write(card.resolve("100CANON/holiday.jpg"), "holiday");

            final ImportSummary summary = importEngine(root).importFrom(List.of(card), ImportKind.COPY,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(inbox(root).resolve("loose.jpg")).hasContent("loose");
            assertThat(inbox(root).resolve("100CANON/holiday.jpg")).hasContent("holiday");
            assertThat(summary).isEqualTo(new ImportSummary(2, 2, 0, 0, 0, 0, false));
        }

        @Test
        void leavesEveryOriginalWhereItWas(@TempDir final Path card, @TempDir final Path root) {
            write(card.resolve("100CANON/holiday.jpg"), "holiday");

            importEngine(root).importFrom(List.of(card), ImportKind.COPY, ProgressCallback.NO_OP,
                    CancellationSignal.NEVER);

            assertThat(card.resolve("100CANON/holiday.jpg")).hasContent("holiday");
        }

        @Test
        void putsAFileChosenOnItsOwnAtTheTopOfTheInbox(@TempDir final Path card, @TempDir final Path root) {
            write(card.resolve("100CANON/holiday.jpg"), "holiday");

            importEngine(root).importFrom(List.of(card.resolve("100CANON/holiday.jpg")), ImportKind.COPY,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(inbox(root).resolve("holiday.jpg")).hasContent("holiday");
        }

        @Test
        void passesOverAFileTheInboxAlreadyHoldsByteForByte(@TempDir final Path card,
                                                            @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");
            write(card.resolve("beach.jpg"), "beach");
            write(inbox(root).resolve("holiday.jpg"), "holiday");

            final ImportSummary summary = importEngine(root).importFrom(List.of(card), ImportKind.COPY,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(filesUnder(inbox(root))).containsExactlyInAnyOrder(
                    inbox(root).resolve("holiday.jpg"), inbox(root).resolve("beach.jpg"));
            assertThat(summary).isEqualTo(new ImportSummary(2, 1, 1, 0, 0, 0, false));
        }

        @Test
        void writesBesideAFileMatchingOnNameAndSizeButNotOnBytes(@TempDir final Path card,
                                                                 @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "aaaaaaa");
            write(inbox(root).resolve("holiday.jpg"), "bbbbbbb");

            final ImportSummary summary = importEngine(root).importFrom(List.of(card),
                    ImportKind.COPY, ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(inbox(root).resolve("holiday.jpg")).hasContent("bbbbbbb");
            assertThat(inbox(root).resolve("holiday (2).jpg")).hasContent("aaaaaaa");
            assertThat(card.resolve("holiday.jpg")).hasContent("aaaaaaa");
            assertThat(summary).isEqualTo(new ImportSummary(1, 1, 0, 0, 0, 0, false));
        }

        @Test
        void writesBesideAFileOfTheSameNameThatDiffersInSize(@TempDir final Path card,
                                                             @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "the one being brought in");
            write(inbox(root).resolve("holiday.jpg"), "different");

            importEngine(root).importFrom(List.of(card), ImportKind.COPY, ProgressCallback.NO_OP,
                    CancellationSignal.NEVER);

            assertThat(inbox(root).resolve("holiday.jpg")).hasContent("different");
            assertThat(inbox(root).resolve("holiday (2).jpg")).hasContent("the one being brought in");
        }

        @Test
        void ticksOncePerFileAgainstTheWholeTotal(@TempDir final Path card, @TempDir final Path root) {
            write(card.resolve("one.jpg"), "one");
            write(card.resolve("two.jpg"), "two");
            final var ticks = new ArrayList<String>();

            importEngine(root).importFrom(List.of(card), ImportKind.COPY,
                    (current, total) -> ticks.add(current + "/" + total), CancellationSignal.NEVER);

            assertThat(ticks).containsExactly("1/2", "2/2");
        }

        @Test
        void bringsInEveryFolderChosenAtOnce(@TempDir final Path card, @TempDir final Path phone,
                                             @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");
            write(phone.resolve("beach.jpg"), "beach");

            final ImportSummary summary = importEngine(root).importFrom(List.of(card, phone),
                    ImportKind.COPY, ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(inbox(root).resolve("holiday.jpg")).hasContent("holiday");
            assertThat(inbox(root).resolve("beach.jpg")).hasContent("beach");
            assertThat(summary.found()).isEqualTo(2);
        }
    }

    @Nested
    class TheTemporaryName {

        @Test
        void isGoneOnceTheFileIsInPlace(@TempDir final Path card, @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");

            importEngine(root).importFrom(List.of(card), ImportKind.COPY, ProgressCallback.NO_OP,
                    CancellationSignal.NEVER);

            assertThat(filesUnder(inbox(root))).containsExactly(inbox(root).resolve("holiday.jpg"));
        }

        @Test
        void isLeftAloneWhereAnEarlierRunAbandonedOne(@TempDir final Path card,
                                                      @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");
            write(inbox(root).resolve("holiday.jpg.sluice-part"), "an earlier attempt");

            importEngine(root).importFrom(List.of(card), ImportKind.COPY, ProgressCallback.NO_OP,
                    CancellationSignal.NEVER);

            assertThat(inbox(root).resolve("holiday.jpg")).hasContent("holiday");
            assertThat(inbox(root).resolve("holiday.jpg.sluice-part"))
                    .hasContent("an earlier attempt");
        }
    }

    @Nested
    class Moving {

        @Test
        void removesEachOriginalOnceItsBytesAreInTheInbox(@TempDir final Path card,
                                                          @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");

            final ImportSummary summary = importEngine(root).importFrom(List.of(card), ImportKind.MOVE,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(inbox(root).resolve("holiday.jpg")).hasContent("holiday");
            assertThat(card.resolve("holiday.jpg")).doesNotExist();
            assertThat(summary).isEqualTo(new ImportSummary(1, 1, 0, 0, 0, 0, false));
        }

        @Test
        void removesAnOriginalTheInboxAlreadyHeldByteForByte(@TempDir final Path card,
                                                             @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");
            write(inbox(root).resolve("holiday.jpg"), "holiday");

            final ImportSummary summary = importEngine(root).importFrom(List.of(card), ImportKind.MOVE,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(card.resolve("holiday.jpg")).doesNotExist();
            assertThat(filesUnder(inbox(root))).containsExactly(inbox(root).resolve("holiday.jpg"));
            assertThat(summary).isEqualTo(new ImportSummary(1, 0, 1, 0, 0, 0, false));
        }

        @Test
        void bringsInAFileThatMatchesOnNameAndSizeButNotOnBytes(@TempDir final Path card,
                                                                @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "aaaaaaa");
            write(inbox(root).resolve("holiday.jpg"), "bbbbbbb");

            final ImportSummary summary = importEngine(root).importFrom(List.of(card), ImportKind.MOVE,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(inbox(root).resolve("holiday.jpg")).hasContent("bbbbbbb");
            assertThat(inbox(root).resolve("holiday (2).jpg")).hasContent("aaaaaaa");
            assertThat(card.resolve("holiday.jpg")).doesNotExist();
            assertThat(summary).isEqualTo(new ImportSummary(1, 1, 0, 0, 0, 0, false));
        }

        @Test
        void takesAFileNamedByTwoOverlappingSourcesOnlyOnce(@TempDir final Path card,
                                                            @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");

            final ImportSummary summary = importEngine(root).importFrom(
                    List.of(card, card.resolve("holiday.jpg")), ImportKind.MOVE,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(summary).isEqualTo(new ImportSummary(1, 1, 0, 0, 0, 0, false));
            assertThat(filesUnder(inbox(root))).containsExactly(inbox(root).resolve("holiday.jpg"));
            assertThat(card.resolve("holiday.jpg")).doesNotExist();
        }

        // Pins the resolution rather than what it prevents. The spellings that actually differ are
        // a Windows short name and a link, and no temp directory produces either on every platform
        // this ships to. So a fixture cannot make the de-duplication fail here.
        @Test
        void resolvesAFileNamedOnItsOwnBeforeComparingItWithAWalkedOne(@TempDir final Path card,
                                                                        @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");
            final List<Path> resolved = new ArrayList<>();
            final MediaStore recording = new NioMediaStore() {
                @Override
                public Path realFile(final Path path) {
                    resolved.add(path);
                    return super.realFile(path);
                }
            };

            new ImportEngine(recording, paths(root), new Sha256Hasher()).importFrom(
                    List.of(card.resolve("holiday.jpg")), ImportKind.COPY,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(resolved).containsExactly(card.resolve("holiday.jpg"));
        }

        @Test
        void keepsBothSidesWhereTheCopyCannotBeProvedToMatch(@TempDir final Path card,
                                                             @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");
            final var engine = new ImportEngine(new NioMediaStore(), paths(root), everyFileHashesDifferently());

            final ImportSummary summary = engine.importFrom(List.of(card), ImportKind.MOVE,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(card.resolve("holiday.jpg")).hasContent("holiday");
            assertThat(filesUnder(inbox(root))).isEmpty();
            assertThat(summary).isEqualTo(new ImportSummary(1, 0, 0, 1, 0, 0, false));
        }
    }

    @Nested
    class Cancelling {

        @Test
        void stopsBeforeTheNextFileAndSaysHowFarItGot(@TempDir final Path card, @TempDir final Path root) {
            write(card.resolve("one.jpg"), "one");
            write(card.resolve("two.jpg"), "two");
            write(card.resolve("three.jpg"), "three");

            final ImportSummary summary = importEngine(root).importFrom(List.of(card), ImportKind.COPY,
                    ProgressCallback.NO_OP, cancelAfter(1));

            assertThat(summary).isEqualTo(new ImportSummary(3, 1, 0, 0, 0, 0, true));
            assertThat(filesUnder(inbox(root))).hasSize(1);
        }

        @Test
        void bringsNothingInWhenItIsAlreadyCancelled(@TempDir final Path card, @TempDir final Path root) {
            write(card.resolve("one.jpg"), "one");

            final ImportSummary summary = importEngine(root).importFrom(List.of(card), ImportKind.COPY,
                    ProgressCallback.NO_OP, () -> true);

            assertThat(summary).isEqualTo(new ImportSummary(1, 0, 0, 0, 0, 0, true));
            assertThat(filesUnder(inbox(root))).isEmpty();
        }

        @Test
        void leavesTheOriginalsOfEverythingItDidNotReach(@TempDir final Path card, @TempDir final Path root) {
            write(card.resolve("one.jpg"), "one");
            write(card.resolve("two.jpg"), "two");
            write(card.resolve("three.jpg"), "three");

            importEngine(root).importFrom(List.of(card), ImportKind.MOVE, ProgressCallback.NO_OP,
                    cancelAfter(1));

            assertThat(filesUnder(card)).hasSize(2);
        }
    }

    @Nested
    class WhatItCannotRead {

        @Test
        void carriesOnPastAFolderItCannotLookInside(@TempDir final Path card,
                                                    @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");
            final var engine = new ImportEngine(refusingToWalkInto(card.resolve("locked")),
                    paths(root), new Sha256Hasher());

            final ImportSummary summary = engine.importFrom(List.of(card), ImportKind.COPY,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(inbox(root).resolve("holiday.jpg")).hasContent("holiday");
            assertThat(summary).isEqualTo(new ImportSummary(1, 1, 0, 0, 0, 1, false));
        }

        @Test
        void carriesOnPastAFileItCannotCopy(@TempDir final Path card, @TempDir final Path root) {
            write(card.resolve("one.jpg"), "one");
            write(card.resolve("two.jpg"), "two");
            final var engine = new ImportEngine(refusingToCopy("one.jpg"),
                    paths(root), new Sha256Hasher());

            final ImportSummary summary = engine.importFrom(List.of(card), ImportKind.COPY,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER);

            assertThat(inbox(root).resolve("two.jpg")).hasContent("two");
            assertThat(summary).isEqualTo(new ImportSummary(2, 1, 0, 0, 1, 0, false));
        }

        @Test
        void refusesInWordsWhereTheSourceStopsAnsweringPartway(@TempDir final Path card,
                                                               @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");
            final var engine = new ImportEngine(refusingToWalkAtAll(), paths(root),
                    new Sha256Hasher());

            assertThatThrownBy(() -> engine.importFrom(List.of(card), ImportKind.COPY,
                    ProgressCallback.NO_OP, CancellationSignal.NEVER))
                    .isInstanceOf(ImportSourceException.class)
                    .hasMessageContaining("could not be read")
                    .hasMessageContaining("still plugged in");
        }

        @Test
        void leavesTheOriginalOfAFileItCouldNotCopy(@TempDir final Path card,
                                                    @TempDir final Path root) {
            write(card.resolve("one.jpg"), "one");
            final var engine = new ImportEngine(refusingToCopy("one.jpg"),
                    paths(root), new Sha256Hasher());

            engine.importFrom(List.of(card), ImportKind.MOVE, ProgressCallback.NO_OP,
                    CancellationSignal.NEVER);

            assertThat(card.resolve("one.jpg")).hasContent("one");
        }
    }

    @Nested
    class RefusingWhatItCannotImport {

        @Test
        void refusesAFolderInsideTheInbox(@TempDir final Path root) {
            final Path inside = inbox(root).resolve("2019");
            write(inside.resolve("holiday.jpg"), "holiday");

            assertThatThrownBy(() -> importEngine(root).requireImportable(List.of(inside)))
                    .isInstanceOf(ImportSourceException.class)
                    .hasMessageContaining("already in your Inbox");
        }

        @Test
        void refusesTheInboxItself(@TempDir final Path root) {
            write(inbox(root).resolve("holiday.jpg"), "holiday");

            assertThatThrownBy(() -> importEngine(root).requireImportable(List.of(inbox(root))))
                    .isInstanceOf(ImportSourceException.class)
                    .hasMessageContaining("already in your Inbox");
        }

        @Test
        void refusesAFolderHoldingTheInbox(@TempDir final Path root) {
            write(inbox(root).resolve("holiday.jpg"), "holiday");

            assertThatThrownBy(() -> importEngine(root).requireImportable(List.of(root)))
                    .isInstanceOf(ImportSourceException.class)
                    .hasMessageContaining("because your Inbox is inside it");
        }

        @Test
        void refusesAFolderThatIsNoLongerThere(@TempDir final Path root) {
            assertThatThrownBy(() -> importEngine(root)
                    .requireImportable(List.of(root.resolve("a-card-that-was-pulled-out"))))
                    .isInstanceOf(ImportSourceException.class)
                    .hasMessageContaining("could not be found");
        }

        @Test
        void refusesBeingHandedNothingAtAll(@TempDir final Path root) {
            assertThatThrownBy(() -> importEngine(root).requireImportable(List.of()))
                    .isInstanceOf(ImportSourceException.class);
        }

        @Test
        void takesAFolderBesideTheInbox(@TempDir final Path card, @TempDir final Path root) {
            write(card.resolve("holiday.jpg"), "holiday");

            importEngine(root).requireImportable(List.of(card));
        }
    }

    // No temp directory is made unreadable the same way on every platform this ships to.
    private static MediaStore refusingToWalkInto(final Path locked) {
        return new NioMediaStore() {
            @Override
            public Walk listFilesToleratingRefusals(final Path root) {
                return new Walk(this.listFiles(root), List.of(locked));
            }
        };
    }

    private static MediaStore refusingToWalkAtAll() {
        return new NioMediaStore() {
            @Override
            public Walk listFilesToleratingRefusals(final Path root) {
                throw new UncheckedIOException(new IOException("gone " + root));
            }
        };
    }

    // Matched on the file name, since a path built here is not equal to the one the engine holds
    // wherever the two spell it differently.
    private static MediaStore refusingToCopy(final String unreadable) {
        return new NioMediaStore() {
            @Override
            public Path copyTo(final Path source, final Path destination, final CancellationSignal stop,
                    final TransferProgress transferProgress) {
                if (source.getFileName().toString().equals(unreadable)) {
                    throw new UncheckedIOException(new IOException("refused " + source));
                }
                return super.copyTo(source, destination, stop, transferProgress);
            }
        };
    }

    private static ImportEngine importEngine(final Path root) {
        try {
            Files.createDirectories(inbox(root));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ImportEngine(new NioMediaStore(), paths(root), new Sha256Hasher());
    }

    private static PathsPort paths(final Path root) {
        return SettingsFixture.pathsConfig(root, root.resolve("library"), inbox(root));
    }

    private static Path inbox(final Path root) {
        return root.resolve("Inbox");
    }

    private static Sha256Port everyFileHashesDifferently() {
        return Path::toString;
    }

    private static CancellationSignal cancelAfter(final int count) {
        final int[] asked = {0};
        return () -> asked[0]++ >= count;
    }

    private static List<Path> filesUnder(final Path root) {
        return new NioMediaStore().listFiles(root);
    }

    private static void write(final Path file, final String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
