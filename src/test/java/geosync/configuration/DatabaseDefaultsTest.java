package geosync.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseDefaultsTest {

    @Test
    void resolvesApprovedPostgresDefaultsWithoutEnvironmentOverrides() throws Exception {
        var propertySources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(propertySources::addLast);
        var resolver = new PropertySourcesPropertyResolver(propertySources);

        assertThat(resolver.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://112.216.98.130:31613/YG-Space_260714"
                        + "?autoReconnect=true&useUnicode=true&characterEncoding=utf8"
                        + "&allowMultiQueries=true&zeroDateTimeBehavior=convertToNull");
        assertThat(resolver.getProperty("spring.datasource.username")).isEqualTo("postgres");
        assertThat(resolver.getProperty("spring.datasource.password")).isEmpty();
    }
}
