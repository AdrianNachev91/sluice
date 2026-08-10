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
import photos.sluice.adapter.secrets.TieredSecretStore;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SettingsStore;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.dating.RescueDateResolver;

/**
 * The Spring configuration class that wires beans needing constructor arguments Spring cannot
 * resolve through component scanning alone. That covers ambiguous {@code DateSource} chains, and
 * adapters that need a config-computed value at construction time.
 *
 * <p>It also enables the {@code @ConfigurationProperties} records that carry Sluice's bound
 * settings: {@link PathsProperties}, {@link MontageProperties}, {@link CullConfig}, and
 * {@link ImagingConfig}.
 */
@Configuration
@EnableConfigurationProperties({PathsProperties.class, MontageProperties.class, CullConfig.class,
        ImagingConfig.class})
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
     * Same reasoning as dateResolver() above: concrete adapter types so Spring can tell the two
     * DateSource positions apart.
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
     * Builds the HEIF decoder bean that shells out to the configured CLI command.
     *
     * @param imagingConfig {@link ImagingConfig} imaging configuration properties
     * @return {@link CliHeifDecoder} the CLI HEIF decoder bean
     */
    @Bean
    public CliHeifDecoder cliHeifDecoder(final ImagingConfig imagingConfig) {
        return new CliHeifDecoder(imagingConfig.heifDecoderCommand());
    }

    /**
     * Writes settings back to the user's config file. Its location is worked out here the same way
     * it is at launch, through one method, so neither side can name a file the other does not.
     *
     * <p>An explicit config-import argument on the command line is read but not written back to. A
     * launcher passes none, so only someone starting the app by hand reaches that.
     *
     * @return {@link SettingsStore} the settings store bean
     */
    @Bean
    public SettingsStore settingsStore() {
        return new YamlSettingsStore(ConfigDirLocator.configFile(System.getProperty("os.name"), System.getenv()));
    }

    /**
     * Builds the credential store over the tiers this machine offers. The store picks its own
     * tiers. What this supplies is the environment and the directory they need, which is why the
     * store is an explicit bean rather than a scanned component.
     *
     * @return {@link SecretStore} the credential store bean
     */
    @Bean
    public SecretStore secretStore() {
        return TieredSecretStore.forMachine(System::getenv,
                ConfigDirLocator.secretsDir(System.getProperty("os.name"), System.getenv()));
    }
}
