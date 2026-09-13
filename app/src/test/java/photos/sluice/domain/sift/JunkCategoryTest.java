package photos.sluice.domain.sift;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JunkCategoryTest {

    @Test
    void theCardIsOnAndCarriesTheDescriptionAProviderIsGiven() {
        final SiftCategory card = JunkCategory.category();

        assertThat(card.name()).isEqualTo("junk");
        assertThat(card.enabled()).isTrue();
        assertThat(card.description()).contains("Objectively worthless photos");
    }

    // The description reaches a paying provider's system prompt, so a card built from a name alone
    // would cost money and judge nothing.
    @Test
    void theDescriptionNamesTheClassItSaysIsMostMissed() {
        assertThat(JunkCategory.category().description())
                .contains("photo of a monitor")
                .contains("single most-missed junk class");
    }

    @Test
    void onlyItsOwnNameIsClaimed() {
        assertThat(JunkCategory.isJunkName("junk")).isTrue();
        assertThat(JunkCategory.isJunkName("Junk")).isFalse();
        assertThat(JunkCategory.isJunkName("junky")).isFalse();
        assertThat(JunkCategory.isJunkName("food")).isFalse();
    }
}
