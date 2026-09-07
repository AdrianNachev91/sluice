package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.RunMode;

import static org.assertj.core.api.Assertions.assertThat;

// RunMode holds a phase count so a screen can reserve room for the bars before any arrive. Nothing
// relates that number to the lists the engines actually announce, and the two sit in different
// layers. A phase added to an engine would leave the reservation short with no test going red.
class ReservedBarsMatchThePhasesTest {

    @Test
    void aSiftReservesRoomForEveryPhaseItAnnounces() {
        assertThat(RunMode.SIFT.phases()).isEqualTo(CullEngine.FRESH_PHASES.size());
    }

    // A resume announces fewer, having already read the prep dir. The reservation is a ceiling, so
    // it is the longer of the two that has to fit.
    @Test
    void aResumeAnnouncesNoMorePhasesThanASiftReservesRoomFor() {
        assertThat(CullEngine.RESUME_PHASES.size()).isLessThanOrEqualTo(RunMode.SIFT.phases());
    }

    @Test
    void aSortReservesRoomForEveryPhaseItAnnounces() {
        assertThat(RunMode.SORT.phases()).isEqualTo(SortEngine.PHASES.size());
    }
}
