package geomex.sync.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties
public class TargetDbProperties {
    private List<TargetDb> targets = new ArrayList<>();

    public List<TargetDb> getTargets() { return targets; }
    public void setTargets(List<TargetDb> targets) { this.targets = targets; }
}
