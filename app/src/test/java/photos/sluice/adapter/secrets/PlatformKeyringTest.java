package photos.sluice.adapter.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformKeyringTest {

    // The real strings the JVM reports, across the releases this ships to. The check is a substring
    // rather than a list, so what needs proving is that every one of these lands on the same side.
    @ParameterizedTest
    @ValueSource(strings = {"Windows 10", "Windows 11", "Windows Server 2022", "windows 10",
            "Windows Server 2025"})
    void recognisesEveryWindowsRelease(final String osName) {
        assertThat(PlatformKeyring.isWindows(osName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Linux", "Mac OS X", "FreeBSD", "SunOS", "AIX"})
    void recognisesEveryOtherPlatformAsNotWindows(final String osName) {
        assertThat(PlatformKeyring.isWindows(osName)).isFalse();
    }

    // A platform with no binding yet gets no keyring tier, and the store above falls through to the
    // protected file. This is the answer every platform gives until its own binding lands.
    @Test
    void offersNoKeyringForAPlatformWithNoBinding() {
        assertThat(PlatformKeyring.forThisMachine("Linux")).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void offersTheCredentialManagerOnWindows() {
        assertThat(PlatformKeyring.forThisMachine(System.getProperty("os.name")))
                .containsInstanceOf(WindowsCredentialTier.class);
    }

    // The OS name decides which binding is attempted, and the machine decides whether it loads.
    // Asking for Windows anywhere else must answer with nothing rather than fail the whole store.
    // A missing library thrown out of the factory would do exactly that.
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void answersWithNothingWhenTheNamedPlatformsLibraryIsNotOnThisMachine() {
        assertThat(PlatformKeyring.forThisMachine("Windows 11")).isEmpty();
    }
}
