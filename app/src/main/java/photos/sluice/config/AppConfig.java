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

@Configuration
@EnableConfigurationProperties({PathsProperties.class, MontageConfig.class, CullConfig.class, ImagingConfig.class})
public class AppConfig {

    // Takes the concrete adapter types (not the shared DateSource port) so Spring resolves each
    // chain position unambiguously - four DateSource beans would otherwise be indistinguishable.
    @Bean
    public DateResolver dateResolver(TakeoutJsonSource sidecarSource, ExifSource exifSource,
            FilenameSource filenameSource, MtimeSource mtimeSource) {
        return new DateResolver(sidecarSource, exifSource, filenameSource, mtimeSource);
    }

    // Same reasoning as dateResolver() above: concrete adapter types so Spring can tell the two
    // DateSource positions apart.
    @Bean
    public RescueDateResolver rescueDateResolver(ExifSource exifSource, FilenameSource filenameSource) {
        return new RescueDateResolver(exifSource, filenameSource);
    }

    @Bean
    public CsvLibraryHashIndex csvLibraryHashIndex(PathsConfig pathsConfig) {
        return new CsvLibraryHashIndex(pathsConfig.logs().resolve("library-hashes.csv"));
    }

    @Bean
    public CliHeifDecoder cliHeifDecoder(ImagingConfig imagingConfig) {
        return new CliHeifDecoder(imagingConfig.heifDecoderCommand());
    }
}
