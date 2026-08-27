package photos.sluice;

import org.junit.jupiter.api.Test;
import photos.sluice.adapter.cli.AppCommand;
import picocli.CommandLine.Command;

import static org.assertj.core.api.Assertions.assertThat;

class SluiceApplicationTest {

    @Test
    void theAppVerbOpensTheWindow() {
        assertThat(SluiceApplication.opensTheWindow(new String[]{"app"})).isTrue();
    }

    @Test
    void nothingToDoRunsAsACommandRatherThanOpeningTheWindow() {
        assertThat(SluiceApplication.opensTheWindow(new String[0])).isFalse();
    }

    @Test
    void nothingToDoAsksForHelp() {
        assertThat(SluiceApplication.commandIn(new String[0])).containsExactly("--help");
    }

    @Test
    void aVerbRunsAsACommand() {
        assertThat(SluiceApplication.opensTheWindow(new String[]{"sort", "2019"})).isFalse();
        assertThat(SluiceApplication.commandIn(new String[]{"sort", "2019"})).containsExactly("sort", "2019");
    }

    // A settings override carries no verb, and still does not open the window. The window would
    // have taken it before this branch existed, so the choice is deliberate rather than incidental.
    @Test
    void aSettingsOverrideAloneRunsAsACommandToo() {
        assertThat(SluiceApplication.opensTheWindow(new String[]{"--sluice.paths.inbox=/photos"})).isFalse();
    }

    @Test
    void theAppVerbCarryingAnythingElseDoesNotOpenTheWindow() {
        assertThat(SluiceApplication.opensTheWindow(new String[]{"app", "--json"})).isFalse();
    }

    @Test
    void theVerbThisBranchesOnIsTheOneTheParserRegisters() {
        assertThat(AppCommand.class.getAnnotation(Command.class).name()).isEqualTo(SluiceApplication.APP);
    }
}
