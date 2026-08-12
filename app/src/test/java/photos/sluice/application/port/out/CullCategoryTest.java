package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CullCategoryTest {

    @Test
    void carriesNameAndDescription() {
        final var category = new CullCategory("receipts", "Paper receipts and invoices");

        assertThat(category.name()).isEqualTo("receipts");
        assertThat(category.description()).isEqualTo("Paper receipts and invoices");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new CullCategory("  ", "Paper receipts and invoices"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsANameThatCannotBecomeAFolder() {
        assertThatThrownBy(() -> new CullCategory("../Photos", "Escapes the review root"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower-case");
        assertThatThrownBy(() -> new CullCategory("Receipts", "Capitalised"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower-case");
        assertThatThrownBy(() -> new CullCategory("con", "A Windows device"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved device name");
    }

    @Test
    void rejectsABlankDescription() {
        assertThatThrownBy(() -> new CullCategory("receipts", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receipts");
    }

    @Test
    void rejectsNulls() {
        // Deliberately violates the non-null contract: Spring's reflective config binding can pass
        // null past the annotations, and these are the guards that catch it.
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new CullCategory(null, "Paper receipts and invoices"))
                .isInstanceOf(IllegalArgumentException.class);
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new CullCategory("receipts", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
