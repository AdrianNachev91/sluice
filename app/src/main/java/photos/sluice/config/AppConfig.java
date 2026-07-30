package photos.sluice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.imaging.CliHeifDecoder;
import photos.sluice.adapter.metadata.ExifSource;
import photos.sluice.adapter.metadata.FilenameSource;
import photos.sluice.adapter.metadata.MtimeSource;
import photos.sluice.adapter.metadata.TakeoutJsonSource;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.dating.RescueDateResolver;

/**
 * The Spring configuration class that wires beans needing constructor arguments Spring cannot
 * resolve through component scanning alone. That covers ambiguous {@code DateSource} chains, and
 * adapters that need a config-bound value at construction time.
 *
 * <p>It also enables the {@code @ConfigurationProperties} records that carry Sluice's bound
 * settings: {@link PathsProperties}, {@link MontageConfig}, {@link CullConfig}, and
 * {@link ImagingConfig}.
 */
@Configuration
@EnableConfigurationProperties({PathsProperties.class, MontageConfig.class, CullConfig.class, ImagingConfig.class})
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
     * Builds the CSV-backed library hash index at the configured logs path.
     *
     * @param pathsConfig {@link PathsConfig} resolved application paths
     * @return {@link CsvLibraryHashIndex} the CSV library hash index bean
     */
    @Bean
    public CsvLibraryHashIndex csvLibraryHashIndex(final PathsConfig pathsConfig) {
        return new CsvLibraryHashIndex(pathsConfig.logs().resolve("library-hashes.csv"));
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
     * The domain grid record the montage pipeline consumes, built from the bound properties
     * record. Mapped by accessor name, so a reordering of either record's fields cannot
     * silently swap the two ints.
     *
     * @param properties {@link MontageConfig} bound montage configuration properties
     * @return {@link photos.sluice.domain.cull.MontageConfig} the domain montage config
     */
    @Bean
    public photos.sluice.domain.cull.MontageConfig montageConfig(final MontageConfig properties) {
        return new photos.sluice.domain.cull.MontageConfig(properties.tileSize(), properties.tilesPerRow());
    }
}
