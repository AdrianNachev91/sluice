package photos.sluice.domain.cull;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CullCategoryTest {

    @Test
    void carriesNameAndDescription() {
        final var category = CullCategory.of("receipts", "Paper receipts and invoices");

        assertThat(category.name()).isEqualTo("receipts");
        assertThat(category.description()).isEqualTo("Paper receipts and invoices");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> CullCategory.of("  ", "Paper receipts and invoices"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsANameThatCannotBecomeAFolder() {
        assertThatThrownBy(() -> CullCategory.of("../Photos", "Escapes the review root"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower-case");
        assertThatThrownBy(() -> CullCategory.of("Receipts", "Capitalised"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower-case");
        assertThatThrownBy(() -> CullCategory.of("con", "A Windows device"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved by the operating system");
    }

    @Test
    void rejectsABlankDescription() {
        assertThatThrownBy(() -> CullCategory.of("receipts", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receipts");
    }

    @Test
    void ofDefaultsToNoExamplesAndSwitchedOn() {
        final var category = CullCategory.of("receipts", "Paper receipts and invoices");

        assertThat(category.examples()).isEmpty();
        assertThat(category.enabled()).isTrue();
    }

    @Test
    void dropsBlankExamplesAndTrimsTheRest() {
        // A YAML list written with a bare "-" binds that entry as null. So the list has to tolerate
        // one, not only the blank string beside it.
        //noinspection NullableProblems
        final var category = new CullCategory("food", "Meals",
                Arrays.asList("  restaurant plates ", "   ", null, "home dinners"), Boolean.TRUE);

        assertThat(category.examples()).containsExactly("restaurant plates", "home dinners");
    }

    @Test
    void aCardNobodySwitchedOffIsOn() {
        //noinspection DataFlowIssue
        final var category = new CullCategory("receipts", "Paperwork", null, null);

        assertThat(category.enabled()).isTrue();
        assertThat(category.examples()).isEmpty();
    }

    @Test
    void aCardSwitchedOffStaysOff() {
        final var category = new CullCategory("receipts", "Paperwork", List.of(), Boolean.FALSE);

        assertThat(category.enabled()).isFalse();
    }

    @Test
    void rejectsADescriptionPastItsCeiling() {
        final String tooLong = "x".repeat(CullCategory.maxDescription() + 1);

        assertThatThrownBy(() -> CullCategory.of("receipts", tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description longer");
        assertThat(CullCategory.of("receipts", "x".repeat(CullCategory.maxDescription())).description())
                .hasSize(CullCategory.maxDescription());
    }

    @Test
    void rejectsAnExamplePastItsCeiling() {
        final List<String> tooLong = List.of("x".repeat(CullCategory.maxExample() + 1));

        assertThatThrownBy(() -> new CullCategory("receipts", "Paperwork", tooLong, Boolean.TRUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("example longer");
    }

    @Test
    void rejectsMoreExamplesThanACardMayCarry() {
        final List<String> tooMany = IntStream.rangeClosed(0, CullCategory.maxExamples())
                .mapToObj(Integer::toString).toList();

        assertThatThrownBy(() -> new CullCategory("receipts", "Paperwork", tooMany, Boolean.TRUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than the");
    }

    @Test
    void rejectsNulls() {
        // Deliberately violates the non-null contract: Spring's reflective config binding can pass
        // null past the annotations, and these are the guards that catch it.
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> CullCategory.of(null, "Paper receipts and invoices"))
                .isInstanceOf(IllegalArgumentException.class);
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> CullCategory.of("receipts", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
