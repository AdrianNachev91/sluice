package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CommandStatusTest {

    @Test
    void theConventionalThreeAreWhateverTheParserAlreadyUses() {
        assertThat(CommandStatus.DONE.exitCode()).isEqualTo(CommandLine.ExitCode.OK).isZero();
        assertThat(CommandStatus.FAILED.exitCode()).isEqualTo(CommandLine.ExitCode.SOFTWARE).isEqualTo(1);
        assertThat(CommandLine.ExitCode.USAGE).isEqualTo(2);
    }

    @Test
    void noStatusTakesTheCodeAUsageErrorAlreadyHas() {
        assertThat(Arrays.stream(CommandStatus.values()).map(CommandStatus::exitCode))
                .doesNotContain(CommandLine.ExitCode.USAGE);
    }

    @Test
    void everyStatusHasACodeOfItsOwn() {
        assertThat(Arrays.stream(CommandStatus.values()).map(CommandStatus::exitCode).distinct())
                .hasSize(CommandStatus.values().length);
    }

    @Test
    void theFourSluiceOwnCodesSitAboveTheParsersThree() {
        assertThat(CommandStatus.REFUSED.exitCode()).isEqualTo(3);
        assertThat(CommandStatus.WAITING.exitCode()).isEqualTo(4);
        assertThat(CommandStatus.BLOCKED.exitCode()).isEqualTo(5);
        assertThat(CommandStatus.CANCELLED.exitCode()).isEqualTo(6);
    }
}
