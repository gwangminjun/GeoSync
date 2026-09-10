package geomex.sync;

import geomex.sync.configuration.KrasGpkiProperties;
import geomex.sync.configuration.TargetDbProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties({TargetDbProperties.class, KrasGpkiProperties.class})
public class SyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(SyncApplication.class, args);
    }
}
