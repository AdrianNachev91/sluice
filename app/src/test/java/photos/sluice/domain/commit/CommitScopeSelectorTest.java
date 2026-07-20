package photos.sluice.domain.commit;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.model.MonthRange;

import static org.assertj.core.api.Assertions.assertThat;

class CommitScopeSelectorTest {

    private final CommitScopeSelector selector = new CommitScopeSelector();

    @Test
    void allScopeIncludesEveryPath() {
        assertThat(selector.isInScope("Photos/2019/06/a.jpg", new CommitScope.All())).isTrue();
        assertThat(selector.isInScope("Funny/a.jpg", new CommitScope.All())).isTrue();
    }

    @Test
    void yearScopeIncludesMatchingYearAcrossAnyMonth() {
        var scope = new CommitScope.Year(2019, null);

        assertThat(selector.isInScope("Photos/2019/01/a.jpg", scope)).isTrue();
        assertThat(selector.isInScope("Photos/2019/12/a.jpg", scope)).isTrue();
        assertThat(selector.isInScope("Photos/2020/01/a.jpg", scope)).isFalse();
    }

    @Test
    void yearScopeWithMonthRangeExcludesOutOfRangeMonths() {
        var scope = new CommitScope.Year(2019, new MonthRange(6, 8));

        assertThat(selector.isInScope("Videos/2019/07/a.mp4", scope)).isTrue();
        assertThat(selector.isInScope("Videos/2019/05/a.mp4", scope)).isFalse();
        assertThat(selector.isInScope("Videos/2019/09/a.mp4", scope)).isFalse();
    }

    @Test
    void yearScopeExcludesUndatedPathsLikeFunny() {
        assertThat(selector.isInScope("Funny/a.jpg", new CommitScope.Year(2019, null))).isFalse();
    }
}
