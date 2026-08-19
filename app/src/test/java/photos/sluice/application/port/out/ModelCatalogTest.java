package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCatalogTest {

    private static final ModelOption CHEAP = new ModelOption("cheap-model", "Cheap model");
    private static final ModelOption DEAR = new ModelOption("dear-model", "Dear model");

    @Test
    void aCatalogRecommendingOneOfItsOwnOptionsIsAccepted() {
        assertThatCode(() -> new ModelCatalog(List.of(CHEAP, DEAR), "dear-model"))
                .doesNotThrowAnyException();
    }

    @Test
    void aCatalogRecommendingNothingIsAccepted() {
        assertThatCode(() -> new ModelCatalog(List.of(CHEAP), null)).doesNotThrowAnyException();
    }

    @Test
    void aCatalogOfferingNothingIsRefused() {
        assertThatThrownBy(() -> new ModelCatalog(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRecommendationNoneOfTheOptionsCarriesIsRefused() {
        assertThatThrownBy(() -> new ModelCatalog(List.of(CHEAP), "dear-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dear-model");
    }

    @Test
    void theOptionsItAnswersWithAreItsOwn() {
        final var mutable = new ArrayList<>(List.of(CHEAP));
        final var catalog = new ModelCatalog(mutable, "cheap-model");

        mutable.add(DEAR);

        assertThat(catalog.options()).containsExactly(CHEAP);
    }

    @Test
    void theOptionsStayInTheOrderTheyWereGiven() {
        final var catalog = new ModelCatalog(List.of(DEAR, CHEAP), null);

        assertThat(catalog.options()).containsExactly(DEAR, CHEAP);
    }
}
