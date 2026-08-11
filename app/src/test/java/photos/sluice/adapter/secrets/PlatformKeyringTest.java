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

    // The JVM reports the one name on Linux, so the row that matters is the lower-cased echo of it.
    @ParameterizedTest
    @ValueSource(strings = {"Linux", "linux"})
    void recognisesLinux(final String osName) {
        assertThat(PlatformKeyring.isLinux(osName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Windows 10", "Mac OS X", "FreeBSD", "SunOS", "AIX"})
    void recognisesEveryOtherPlatformAsNotLinux(final String osName) {
        assertThat(PlatformKeyring.isLinux(osName)).isFalse();
    }

    // A platform with no binding yet gets no keyring tier, and the store above falls through to the
    // protected file. This is the answer every platform gives until its own binding lands.
    @Test
    void offersNoKeyringForAPlatformWithNoBinding() {
        assertThat(PlatformKeyring.forThisMachine("FreeBSD")).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void offersTheCredentialManagerOnWindows() {
        assertThat(PlatformKeyring.forThisMachine(System.getProperty("os.name")))
                .containsInstanceOf(WindowsCredentialTier.class);
    }

    // Runs where libsecret is installed, which every desktop with a Secret Service satisfies and
    // the CI runner arranges. A Linux machine without libsecret registers no tier at all and
    // keeps its credential in the protected file, which is an ordinary install rather than a
    // failure. This test would fail on such a machine, deliberately. A Linux box is expected to
    // carry libsecret, and finding out that it does not is worth a red build.
    @Test
    @EnabledOnOs(OS.LINUX)
    void offersTheSecretServiceOnLinux() {
        assertThat(PlatformKeyring.forThisMachine(System.getProperty("os.name")))
                .containsInstanceOf(LinuxSecretServiceTier.class);
    }

    // The OS name decides which binding is attempted, and the machine decides whether it loads.
    // Asking for Windows anywhere else must answer with nothing rather than fail the whole store.
    // A missing library thrown out of the factory would do exactly that.
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void answersWithNothingWhenTheNamedPlatformsLibraryIsNotOnThisMachine() {
        assertThat(PlatformKeyring.forThisMachine("Windows 11")).isEmpty();
    }

    // The same fact for the Linux binding: a machine without libsecret is an ordinary install
    // that keeps its credential in the file tier, not a failure.
    @Test
    @DisabledOnOs(OS.LINUX)
    void answersWithNothingWhenLibsecretIsNotOnThisMachine() {
        assertThat(PlatformKeyring.forThisMachine("Linux")).isEmpty();
    }
}
