package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.paths.PathRole;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PathRoleLabelsTest {

    @Test
    void eachRootIsNamedTheWayTheFolderRowsNameIt() {
        assertThat(PathRoleLabels.of(PathRole.WORKING_ROOT)).isEqualTo("Working root");
        assertThat(PathRoleLabels.of(PathRole.LIBRARY_ROOT)).isEqualTo("Library root");
        assertThat(PathRoleLabels.of(PathRole.INBOX)).isEqualTo("Inbox");
    }

    @Test
    void noTwoRootsShareAName() {
        assertThat(Arrays.stream(PathRole.values()).map(PathRoleLabels::of).distinct())
                .hasSize(PathRole.values().length);
    }
}
