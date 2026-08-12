package photos.sluice.domain.cull;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MontageNamingTest {

    @Test
    void acceptsAnIdItProducedItself() {
        assertThat(MontageNaming.isMontageId(MontageNaming.montageIdFor(1))).isTrue();
        assertThat(MontageNaming.isMontageId(MontageNaming.montageIdFor(999))).isTrue();
        assertThat(MontageNaming.isMontageId(MontageNaming.montageIdFor(1000))).isTrue();
    }

    @Test
    void refusesAnIdThatWouldNotStayOneFileInsideThePrepDir() {
        assertThat(MontageNaming.isMontageId("../../../evil")).isFalse();
        assertThat(MontageNaming.isMontageId("montage-003/../..")).isFalse();
        assertThat(MontageNaming.isMontageId("/montage-003")).isFalse();
        assertThat(MontageNaming.isMontageId("")).isFalse();
    }

    @Test
    void refusesAnIdWithTheRightPrefixButNoNumber() {
        assertThat(MontageNaming.isMontageId("montage-")).isFalse();
        assertThat(MontageNaming.isMontageId("montage-abc")).isFalse();
        assertThat(MontageNaming.isMontageId("montage-003x")).isFalse();
        assertThat(MontageNaming.isMontageId("xmontage-003")).isFalse();
    }
}
