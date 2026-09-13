package photos.sluice.domain.sift;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryNameTest {

    @Test
    void acceptsTheShapesACategoryNameIsAllowedToTake() {
        assertThat(CategoryName.problemWith("junk")).isNull();
        assertThat(CategoryName.problemWith("scenery")).isNull();
        assertThat(CategoryName.problemWith("near-dup-candidates")).isNull();
        assertThat(CategoryName.problemWith("receipts2019")).isNull();
        assertThat(CategoryName.problemWith("2019")).isNull();
    }

    @Test
    void refusesANameThatWouldNotStayOneFolderUnderTheReviewRoot() {
        assertThat(CategoryName.problemWith("../Photos/2019/06")).isNotNull();
        assertThat(CategoryName.problemWith("..")).isNotNull();
        assertThat(CategoryName.problemWith(".")).isNotNull();
        assertThat(CategoryName.problemWith("a/b")).isNotNull();
        assertThat(CategoryName.problemWith("a\\b")).isNotNull();
        assertThat(CategoryName.problemWith("/junk")).isNotNull();
        assertThat(CategoryName.problemWith("C:junk")).isNotNull();
    }

    @Test
    void refusesANameThatIsEmptyOrOnlySpace() {
        assertThat(CategoryName.problemWith("")).isNotNull();
        assertThat(CategoryName.problemWith("  ")).isNotNull();
        assertThat(CategoryName.problemWith(" junk")).isNotNull();
        assertThat(CategoryName.problemWith("junk ")).isNotNull();
    }

    @Test
    void refusesUpperCase() {
        assertThat(CategoryName.problemWith("Junk")).isNotNull();
        assertThat(CategoryName.problemWith("JUNK")).isNotNull();
    }

    @Test
    void refusesAHyphenThatDoesNotJoinTwoRuns() {
        assertThat(CategoryName.problemWith("-junk")).isNotNull();
        assertThat(CategoryName.problemWith("junk-")).isNotNull();
        assertThat(CategoryName.problemWith("junk--food")).isNotNull();
        assertThat(CategoryName.problemWith("-")).isNotNull();
    }

    @Test
    void refusesANameTooLongToSpendOnOneFolder() {
        assertThat(CategoryName.problemWith("a".repeat(24))).isNull();
        assertThat(CategoryName.problemWith("a".repeat(25))).contains("longer than");
    }

    @Test
    void refusesAWindowsDeviceNameInAnyCase() {
        assertThat(CategoryName.problemWith("con")).contains("reserved by the operating system");
        assertThat(CategoryName.problemWith("nul")).contains("reserved by the operating system");
        assertThat(CategoryName.problemWith("com1")).contains("reserved by the operating system");
        assertThat(CategoryName.problemWith("lpt9")).contains("reserved by the operating system");
    }

    @Test
    void acceptsANameThatOnlyResemblesAWindowsDevice() {
        assertThat(CategoryName.problemWith("console")).isNull();
        assertThat(CategoryName.problemWith("nullify")).isNull();
        assertThat(CategoryName.problemWith("com0")).isNull();
    }

    @Test
    void refusesTheTwoFolderNamesASortFillsByItself() {
        assertThat(CategoryName.problemWith("unsorted")).contains("a sort fills by itself");
        assertThat(CategoryName.problemWith("2019-06")).contains("a sort fills by itself");
        assertThat(CategoryName.problemWith("1999-12")).contains("a sort fills by itself");
    }

    @Test
    void acceptsANameThatOnlyResemblesOneASortFills() {
        assertThat(CategoryName.problemWith("unsorted-later")).isNull();
        assertThat(CategoryName.problemWith("2019-6")).isNull();
        assertThat(CategoryName.problemWith("2019-06-01")).isNull();
    }

    @Test
    void refusesANameAVerdictAlreadyUses() {
        assertThat(CategoryName.problemWith("keep")).contains("the app's own photo decisions");
        assertThat(CategoryName.problemWith("near-dup-chosen")).contains("the app's own photo decisions");
        assertThat(CategoryName.problemWith("near-dup-reject")).contains("the app's own photo decisions");
    }

    @Test
    void acceptsANameThatOnlyResemblesAVerdict() {
        assertThat(CategoryName.problemWith("keepers")).isNull();
        assertThat(CategoryName.problemWith("near-dup-candidates")).isNull();
        assertThat(CategoryName.problemWith("near-dup")).isNull();
    }

    // Compared rather than substring-matched. A negative quoting the shape refusal's own wording
    // would stop proving anything the moment that sentence is reworded, and would go on passing.
    // Both are asserted non-null so a rule that stopped refusing at all cannot pass as "distinct".
    @Test
    void reportsTheShapeAndTheDeviceRefusalDistinctly() {
        final String shape = CategoryName.problemWith("Junk");
        final String device = CategoryName.problemWith("con");

        assertThat(shape).isNotNull();
        assertThat(device).isNotNull().isNotEqualTo(shape);
    }
}
