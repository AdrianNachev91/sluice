package photos.sluice.domain.cull;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JunkCategoryTest {

    @Test
    void theCardIsOnAndCarriesTheDescriptionAProviderIsGiven() {
        final CullCategory card = JunkCategory.card();

        assertThat(card.name()).isEqualTo("junk");
        assertThat(card.enabled()).isTrue();
        assertThat(card.description()).contains("Objectively worthless photos");
    }

    // The description reaches a paying provider's system prompt, so a card built from a name alone
    // would cost money and judge nothing.
    @Test
    void theDescriptionNamesTheClassItSaysIsMostMissed() {
        assertThat(JunkCategory.card().description())
                .contains("photo of a monitor")
                .contains("single most-missed junk class");
    }

    @Test
    void onlyItsOwnNameIsClaimed() {
        assertThat(JunkCategory.claims("junk")).isTrue();
        assertThat(JunkCategory.claims("Junk")).isFalse();
        assertThat(JunkCategory.claims("junky")).isFalse();
        assertThat(JunkCategory.claims("food")).isFalse();
    }
}
