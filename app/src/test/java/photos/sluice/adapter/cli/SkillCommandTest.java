package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCommandTest {

    @Test
    void theSkillNamesEveryVerbTheSurfaceCarries() {
        final String skill = SkillCommand.skillText();

        assertThat(CliHarness.parser().getSubcommands().keySet())
                .isNotEmpty()
                .allSatisfy(verb -> assertThat(skill).contains("| `" + verb + "`"));
    }

    @Test
    void theSkillIsTheAnswerRatherThanANoteBesideIt() {
        final CliHarness.Result result = this.run("skill");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out()).contains("# ");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void theSkillOpensWithAHeaderCarryingItsNameAndWhenToUseIt() {
        final String skill = SkillCommand.skillText();

        assertThat(skill.lines().findFirst()).hasValue("---");
        assertThat(skill).containsPattern("(?m)^name: ").containsPattern("(?m)^description: ");
    }

    @Test
    void aCallerAskingForADocumentGetsTheSkillWholeRatherThanAsLines() {
        final CliHarness.Result result = this.run("skill", "--json");

        assertThat(result.out().lines()).hasSize(1);
        assertThat(result.out()).contains("\"status\":\"DONE\"").contains("\"skill\":");
    }

    @Test
    void theBuildBeingDescribedIsNamedRatherThanLeftAsItsPlaceholder() {
        final String skill = SkillCommand.skillText();

        assertThat(skill).doesNotContain("{{");
    }

    private CliHarness.Result run(final String... args) {
        final var command = new SkillCommand(new CommandReports(new RefusalClassifier(new NoSecrets())));
        return CliHarness.run(CliHarness.parser(command), args);
    }
}
