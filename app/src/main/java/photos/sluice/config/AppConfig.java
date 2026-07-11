package photos.sluice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PathsProperties.class, MontageConfig.class, CullConfig.class})
public class AppConfig {
}
