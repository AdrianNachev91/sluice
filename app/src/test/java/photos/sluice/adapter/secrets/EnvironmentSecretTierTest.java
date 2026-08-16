package photos.sluice.adapter.secrets;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// The environment arrives as an injected lookup rather than through a static call. That is what
// makes the override testable at all, so every case here supplies its own lookup.
class EnvironmentSecretTierTest {

    private static final SecretId ANTHROPIC = new SecretId("anthropic", "ANTHROPIC_API_KEY");

    @Test
    void readsTheCredentialTheNamedVariableCarries() {
        final var tier = tierOver(Map.of("ANTHROPIC_API_KEY", "from-environment"));

        assertThat(tier.read(ANTHROPIC)).contains("from-environment");
    }

    // The environment is not empty here. The empty result therefore comes from this id's own
    // variable being absent, not from the tier having nothing at all to read.
    @Test
    void readsNoCredentialWhenTheNamedVariableIsNotSet() {
        final var tier = tierOver(Map.of("SOME_OTHER_VARIABLE", "from-environment"));

        assertThat(tier.read(ANTHROPIC)).isEmpty();
    }

    // Whichever name the id carries is the one asked for. A tier that resolved a name of its own
    // would still pass every other case here, since they all use the same variable.
    @Test
    void asksTheEnvironmentForTheVariableTheIdNames() {
        final var asked = new ArrayList<String>();
        final var tier = new EnvironmentSecretTier(name -> {
            asked.add(name);
            return null;
        });

        tier.read(new SecretId("other", "OTHER_PROVIDER_API_KEY"));

        assertThat(asked).containsExactly("OTHER_PROVIDER_API_KEY");
    }

    // Someone clearing a variable in a shell without unsetting it means to turn the override off,
    // not to hand the app an empty credential.
    @Test
    void treatsAnEmptyVariableAsUnset() {
        final var tier = tierOver(Map.of("ANTHROPIC_API_KEY", ""));

        assertThat(tier.read(ANTHROPIC)).isEmpty();
    }

    // A shell that quotes a cleared value leaves spaces behind rather than an empty string, and the
    // person typing it meant the same thing either way.
    @Test
    void treatsAWhitespaceOnlyVariableAsUnset() {
        final var tier = tierOver(Map.of("ANTHROPIC_API_KEY", "   "));

        assertThat(tier.read(ANTHROPIC)).isEmpty();
    }

    // A value pasted into a shell profile carries whatever whitespace came with it, and that would
    // otherwise reach the provider as part of the key.
    @Test
    void stripsWhitespaceAroundTheCredential() {
        final var tier = tierOver(Map.of("ANTHROPIC_API_KEY", "  sk-synthetic-0001\n"));

        assertThat(tier.read(ANTHROPIC)).contains("sk-synthetic-0001");
    }

    @Test
    void reportsHoldingACredentialOnlyWhenTheVariableCarriesOne() {
        assertThat(tierOver(Map.of("ANTHROPIC_API_KEY", "from-environment")).holds(ANTHROPIC)).isTrue();
        assertThat(tierOver(Map.of("ANTHROPIC_API_KEY", " ")).holds(ANTHROPIC)).isFalse();
        assertThat(tierOver(Map.of()).holds(ANTHROPIC)).isFalse();
    }

    // A settings screen quotes the variable name back to the user, and it differs per provider. Two
    // ids through one tier is what shows the name comes from the id rather than from the tier.
    @Test
    void namesTheVariableCarriedByTheIdItWasAskedAbout() {
        final var tier = tierOver(Map.of());

        assertThat(tier.location(ANTHROPIC))
                .isEqualTo(new SecretStatus.InEnvironment("ANTHROPIC_API_KEY"));
        assertThat(tier.location(new SecretId("other", "OTHER_PROVIDER_API_KEY")))
                .isEqualTo(new SecretStatus.InEnvironment("OTHER_PROVIDER_API_KEY"));
    }

    private static EnvironmentSecretTier tierOver(final Map<String, String> environment) {
        return new EnvironmentSecretTier(environment::get);
    }
}
