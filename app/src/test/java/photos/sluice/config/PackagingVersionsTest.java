package photos.sluice.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// The pom and conveyor.conf each keep their own copy of these numbers, and neither file reads the
// other. A drift compiles, packages and installs. Surefire's working directory is app/, where both
// files sit.
class PackagingVersionsTest {

    private static final Pattern POM_VERSION =
            Pattern.compile("<artifactId>sluice</artifactId>\\s*+(?:<!--.*?-->\\s*+)*+<version>(.*?)</version>",
                    Pattern.DOTALL);

    private static final Pattern POM_JAVAFX = Pattern.compile("<javafx\\.version>(.*?)</javafx\\.version>");

    @Test
    void theInstallerAndTheBuildCallThemselvesTheSameVersion() throws IOException {
        final String version = pomValue(POM_VERSION);

        // The whole line, since conveyor.conf holds javafx.version and libheif.version too. A bare
        // substring is satisfied by either of those, and by a commented-out copy of this one.
        assertThat(packaging().lines()).contains("  version = " + version);
    }

    @Test
    void thePackagedJarIsTheOneTheBuildProduces() throws IOException {
        final String version = pomValue(POM_VERSION);

        assertThat(packaging().lines()).contains("  inputs += target/sluice-" + version + ".jar");
    }

    @Test
    void theLinkedJavaFxIsTheOneTheCodeWasCompiledAgainst() throws IOException {
        final String version = pomValue(POM_JAVAFX);

        assertThat(packaging().lines()).contains("javafx.version = " + version);
    }

    private static String pomValue(final Pattern pattern) throws IOException {
        final Matcher matcher = pattern.matcher(Files.readString(Path.of("pom.xml")));

        assertThat(matcher.find()).as("pom.xml matches %s", pattern).isTrue();
        return matcher.group(1).trim();
    }

    private static String packaging() throws IOException {
        return Files.readString(Path.of("conveyor.conf"));
    }
}
