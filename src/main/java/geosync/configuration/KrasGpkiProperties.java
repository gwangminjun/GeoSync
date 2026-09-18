package geosync.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "kras.gpki")
public class KrasGpkiProperties {
    private boolean enabled;
    private String id = "";
    private String homeDir = "./lib";
    private String passwordFile = "password.txt";
    private String password = "";
    private boolean decryptResponse = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHomeDir() {
        return homeDir;
    }

    public void setHomeDir(String homeDir) {
        this.homeDir = homeDir;
    }

    public String getPasswordFile() {
        return passwordFile;
    }

    public void setPasswordFile(String passwordFile) {
        this.passwordFile = passwordFile;
    }

    public String getLicensePath() {
        return Path.of(homeDir, "conf").toString();
    }

    public String getCertPath() {
        return Path.of(homeDir, "Certificate", "class1", id + "_env.cer").toString();
    }

    public String getKeyPath() {
        return Path.of(homeDir, "Certificate", "class1", id + "_env.key").toString();
    }

    public String getPasswordPath() {
        return Path.of(homeDir, passwordFile).toString();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isDecryptResponse() {
        return decryptResponse;
    }

    public void setDecryptResponse(boolean decryptResponse) {
        this.decryptResponse = decryptResponse;
    }
}
