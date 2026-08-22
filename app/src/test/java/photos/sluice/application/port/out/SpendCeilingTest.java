package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpendCeilingTest {

    @Test
    void theTokenArmStaysQuietUntilEnoughMontagesHaveBeenAttempted() {
        final var ceiling = new SpendCeiling(100, 1_000, 5);

        assertThat(ceiling.tokensExhausted(4, 1_000_000)).isFalse();
    }

    @Test
    void theTokenArmFiresOnceSpendPassesWhatTheWorkDoneAllows() {
        final var ceiling = new SpendCeiling(100, 1_000, 5);

        assertThat(ceiling.tokensExhausted(5, 5_001)).isTrue();
    }

    @Test
    void spendExactlyAtTheAllowanceHasNotPassedIt() {
        final var ceiling = new SpendCeiling(100, 1_000, 5);

        assertThat(ceiling.tokensExhausted(5, 5_000)).isFalse();
    }

    @Test
    void theAllowanceGrowsWithEveryMontageAttempted() {
        final var ceiling = new SpendCeiling(100, 1_000, 5);

        assertThat(ceiling.tokensExhausted(20, 20_000)).isFalse();
        assertThat(ceiling.tokensExhausted(20, 20_001)).isTrue();
    }

    @Test
    void aCeilingAllowingNoCallsIsRefused() {
        assertThatThrownBy(() -> new SpendCeiling(0, 1_000, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCalls=0");
    }

    @Test
    void aCeilingAllowingNoTokensIsRefused() {
        assertThatThrownBy(() -> new SpendCeiling(100, 0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenBudgetPerMontage=0");
    }

    @Test
    void aNegativeWarmUpIsRefused() {
        assertThatThrownBy(() -> new SpendCeiling(100, 1_000, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("armAfterMontages=-1");
    }
}
