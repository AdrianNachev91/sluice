package photos.sluice.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code config.example.yml} names every key this app binds, so it stays a document rather
 * than an intention nothing checks. Nothing breaks when a new setting fails to appear in it, so
 * this is the one thing standing between the file and going stale.
 */
class ConfigExampleFileTest {

    // Every @ConfigurationProperties class this app binds, paired with its prefix, matching
    // AppConfig's own @EnableConfigurationProperties list.
    private static final List<Class<?>> PROPERTIES_CLASSES =
            List.of(PathsProperties.class, MontageProperties.class, CullConfig.class, ImagingConfig.class,
                    UiProperties.class);

    @Test
    void namesEveryConfigurationPropertyKey() {
        final String content = readExampleFile();
        final List<String> missing = PROPERTIES_CLASSES.stream()
                .flatMap(type -> keysOf(type).stream())
                .filter(key -> !content.contains(key + ":"))
                .toList();
        assertThat(missing).as("keys missing from config.example.yml").isEmpty();
    }

    /**
     * The leaf kebab-case key names a record's own components bind to. Recurses into a nested
     * record, so a property like {@code model} under {@code provider-settings} is found too.
     *
     * <p>YAML nests rather than dots its keys, so what the file actually shows is the leaf name
     * alone.
     *
     * <p>A {@link List}-typed component (the cull categories) is skipped. It names a set of
     * user-typed cards rather than a fixed key, and the example file documents it in prose instead.
     *
     * @param type {@link Class} the record type to read components from
     * @return a {@link List} of {@link String} every leaf kebab-case key this record and its nested
     *         records bind to
     */
    private static List<String> keysOf(final Class<?> type) {
        return Stream.of(type.getRecordComponents())
                .filter(component -> !List.class.isAssignableFrom(component.getType()))
                .flatMap(ConfigExampleFileTest::keysOfComponent)
                .toList();
    }

    private static Stream<String> keysOfComponent(final RecordComponent component) {
        return component.getType().isRecord()
                ? keysOf(component.getType()).stream()
                : Stream.of(kebabCase(component.getName()));
    }

    private static String kebabCase(final String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String readExampleFile() {
        try (final InputStream stream = ConfigExampleFileTest.class.getResourceAsStream("/config.example.yml")) {
            assertThat(stream).as("config.example.yml on the classpath").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
