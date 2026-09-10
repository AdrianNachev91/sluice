package photos.sluice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import photos.sluice.adapter.fs.YamlSettingsStore;
import photos.sluice.adapter.imaging.CliHeifDecoder;
import photos.sluice.adapter.metadata.ExifSource;
import photos.sluice.adapter.metadata.FilenameSource;
import photos.sluice.adapter.metadata.MtimeSource;
import photos.sluice.adapter.metadata.TakeoutJsonSource;
import photos.sluice.application.port.out.SettingsStore;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.secrets.SecretStore;

import java.nio.file.Path;

/**
 * The Spring configuration class that wires beans needing constructor arguments Spring cannot
 * resolve through component scanning alone. That covers ambiguous {@code DateSource} chains, and
 * adapters that need a config-computed value at construction time.
 *
 * <p>It also enables the {@code @ConfigurationProperties} records that carry Sluice's bound
 * settings: {@link PathsProperties}, {@link MontageProperties}, {@link CullConfig},
 * {@link ImagingConfig}, and {@link UiProperties}.
 */
@Configuration
@EnableConfigurationProperties({PathsProperties.class, MontageProperties.class, CullConfig.class,
        ImagingConfig.class, UiProperties.class})
public class AppConfig {

    /**
     * Takes the concrete adapter types (not the shared DateSource port) so Spring resolves each
     * chain position unambiguously - four DateSource beans would otherwise be indistinguishable.
     *
     * @param sidecarSource {@link TakeoutJsonSource} Takeout JSON sidecar date source
     * @param exifSource {@link ExifSource} EXIF metadata date source
     * @param filenameSource {@link FilenameSource} filename-pattern date source
     * @param mtimeSource {@link MtimeSource} file modified-time date source
     * @return {@link DateResolver} the composed date resolver
     */
    @Bean
    public DateResolver dateResolver(final TakeoutJsonSource sidecarSource, final ExifSource exifSource,
                                     final FilenameSource filenameSource, final MtimeSource mtimeSource) {
        return new DateResolver(sidecarSource, exifSource, filenameSource, mtimeSource);
    }

    /**
     * Concrete adapter types so Spring can tell the two DateSource positions apart.
     *
     * @param exifSource {@link ExifSource} EXIF metadata date source
     * @param filenameSource {@link FilenameSource} filename-pattern date source
     * @return {@link RescueDateResolver} the composed rescue date resolver
     */
    @Bean
    public RescueDateResolver rescueDateResolver(final ExifSource exifSource, final FilenameSource filenameSource) {
        return new RescueDateResolver(exifSource, filenameSource);
    }

    /**
     * Builds the HEIF decoder bean that shells out to a CLI decoder.
     *
     * @param imagingConfig {@link ImagingConfig} imaging configuration properties
     * @return {@link CliHeifDecoder} the CLI HEIF decoder bean
     */
    @Bean
    public CliHeifDecoder cliHeifDecoder(final ImagingConfig imagingConfig) {
        return new CliHeifDecoder(heifCommand(imagingConfig));
    }

    /**
     * Which decoder command the bean above is built on.
     *
     * <p>Conveyor's launcher sets {@code app.dir}, and a build run has it unset.
     *
     * @param imagingConfig {@link ImagingConfig} imaging configuration properties
     * @return {@link String} the command to run
     */
    static String heifCommand(final ImagingConfig imagingConfig) {
        return HeifDecoderLocator.command(imagingConfig.heifDecoderCommand(), System.getProperty("app.dir"));
    }

    /**
     * Writes settings back to the user's config file. Its location is worked out here the same way
     * it is at launch, through one method, so neither side can name a file the other does not.
     *
     * <p>An explicit config-import argument on the command line is read but not written back to.
     *
     * @return {@link SettingsStore} the settings store bean
     */
    @Bean
    public SettingsStore settingsStore() {
        return new YamlSettingsStore(ConfigDirLocator.configFile(System.getProperty("os.name"), System.getenv()));
    }

    /**
     * Builds the credential store over the tiers this machine offers.
     *
     * <p>The OS name is read once and passed to both. The credential store and the directory it
     * falls back to can then never be resolved against two different answers.
     *
     * @return {@link SecretStore} the credential store bean
     */
    @Bean
    public SecretStore secretStore() {
        final String osName = System.getProperty("os.name");
        return credentialStore(osName, ConfigDirLocator.secretsDir(osName, System.getenv()));
    }

    /**
     * Composes the store over an OS name and a fallback directory supplied by the caller.
     *
     * @param osName {@link String} the operating system's name, which picks the keyring
     * @param secretsDir {@link Path} where the fallback writes, on a machine offering no keyring
     * @return {@link SecretStore} a store over the tiers those two answers allow
     */
    static SecretStore credentialStore(final String osName, final Path secretsDir) {
        // A credential already saved on a user's machine sits under keys built from "Sluice" and
        // "photos.sluice". The first is the Windows target prefix and the macOS service attribute,
        // the second the Secret Service schema. Changing either leaves entries on the platforms it
        // reaches behind, reported absent and still on disk.
        return SecretStore.forApplication("Sluice")
                .inNamespace("photos.sluice")
                .withEnvironmentOverride()
                .withCredentialFilesIn(secretsDir)
                .onOperatingSystem(osName)
                .open();
    }
}
