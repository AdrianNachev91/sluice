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

    // A prep-dir write publishes through a temporary file named after its destination, so a killed
    // process can leave one behind. These predicates are what decide whether the leftover is then
    // mistaken for a shard, a sidecar or a montage entry.
    @Test
    void aTemporaryFileLeftBesideAnArtifactIsNotMistakenForOne() {
        assertThat(MontageNaming.isShardFile("decisions-003.json7215043.tmp")).isFalse();
        assertThat(MontageNaming.sidecarMontageNumber("montage-003.json7215043.tmp")).isEmpty();
        assertThat(MontageNaming.isMontageId("montage-003.json7215043.tmp")).isFalse();
        assertThat(MontageNaming.isShardFile("decisions-003.json")).isTrue();
        assertThat(MontageNaming.sidecarMontageNumber("montage-003.json")).hasValue(3);
    }
}
