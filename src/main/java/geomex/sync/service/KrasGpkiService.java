package geomex.sync.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import geomex.sync.config.KrasGpkiProperties;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class KrasGpkiService {
    private final KrasGpkiProperties properties;

    public KrasGpkiService(KrasGpkiProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled() && StringUtils.hasText(properties.getId());
    }

    public String gpkiId() {
        return properties.getId();
    }

    public void addAuthentication(ObjectNode body) {
        if (isEnabled()) {
            body.put("gpki_id", properties.getId());
        }
    }

    public void addAuthentication(Map<String, String> params) {
        if (isEnabled()) {
            params.put("gpki_id", properties.getId());
        }
    }

    public void assertReady() {
        if (!isEnabled()) {
            return;
        }
        requireText(properties.getHomeDir(), "kras.gpki.home-dir");
        requirePath(properties.getHomeDir(), "kras.gpki.home-dir");
        requirePath(properties.getLicensePath(), "kras.gpki.home-dir/conf");
        requirePath(properties.getCertPath(), "GPKI certificate file");
        requirePath(properties.getKeyPath(), "GPKI private key file");
        if (!StringUtils.hasText(readPassword())) {
            throw new IllegalStateException("Missing GPKI password. Set kras.gpki.password or create " + properties.getPasswordPath());
        }

        try {
            Class.forName("com.gpki.gpkiapi.GpkiApi");
        } catch (Throwable e) {
            throw new IllegalStateException("GPKI API load failed. Check libgpkiapi_jni.jar and native gpkiapi library path.", e);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", isEnabled());
        status.put("configured", properties.isEnabled());
        status.put("gpkiId", properties.getId());
        status.put("homeDir", properties.getHomeDir());
        status.put("decryptResponse", properties.isDecryptResponse());
        status.put("homeDirPresent", pathPresent(properties.getHomeDir()));
        status.put("licensePathPresent", pathPresent(properties.getLicensePath()));
        status.put("certPathPresent", pathPresent(properties.getCertPath()));
        status.put("keyPathPresent", pathPresent(properties.getKeyPath()));
        status.put("passwordPresent", StringUtils.hasText(readPassword()));
        try {
            Class.forName("com.gpki.gpkiapi.GpkiApi");
            status.put("apiLoadable", true);
        } catch (Throwable e) {
            status.put("apiLoadable", false);
            status.put("apiLoadError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return status;
    }

    public String decodeResponse(String responseText) {
        if (!isEnabled() || !properties.isDecryptResponse()
                || looksLikeJson(responseText) || looksLikeXml(responseText)) {
            return responseText;
        }
        try {
            byte[] encrypted = Base64.getDecoder().decode(responseText.replaceAll("\\s+", ""));
            byte[] decrypted = decrypt(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("GPKI response decrypt failed: " + e.getMessage(), e);
        }
    }

    private synchronized byte[] decrypt(byte[] encrypted) throws Exception {
        Class<?> gpkiApi = Class.forName("com.gpki.gpkiapi.GpkiApi");
        Class<?> disk = Class.forName("com.gpki.gpkiapi.storage.Disk");
        Class<?> envelopedData = Class.forName("com.gpki.gpkiapi.cms.EnvelopedData");

        gpkiApi.getMethod("init", String.class).invoke(null, properties.getLicensePath());
        Object cert = disk.getMethod("readCert", String.class).invoke(null, properties.getCertPath());
        Object privateKey = disk.getMethod("readPriKey", String.class, String.class)
                .invoke(null, properties.getKeyPath(), readPassword());

        Constructor<?> constructor = envelopedData.getConstructor(String.class);
        Object cms = constructor.newInstance("NEAT");
        Method process = findProcessMethod(envelopedData);
        return (byte[]) process.invoke(cms, encrypted, cert, privateKey);
    }

    private Method findProcessMethod(Class<?> envelopedData) {
        for (Method method : envelopedData.getMethods()) {
            if ("process".equals(method.getName()) && method.getParameterCount() == 3) {
                return method;
            }
        }
        throw new IllegalStateException("GPKI EnvelopedData.process(byte[], cert, key) method not found.");
    }

    private boolean looksLikeJson(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private boolean looksLikeXml(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        return trimmed.startsWith("<");
    }

    private void requireText(String value, String key) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Missing GPKI setting: " + key);
        }
    }

    private void requirePath(String value, String key) {
        if (!Files.exists(Path.of(value))) {
            throw new IllegalStateException("GPKI path not found: " + key + "=" + value);
        }
    }

    private boolean pathPresent(String value) {
        return StringUtils.hasText(value) && Files.exists(Path.of(value));
    }

    private String readPassword() {
        if (StringUtils.hasText(properties.getPassword())) {
            return properties.getPassword();
        }
        Path passwordPath = Path.of(properties.getPasswordPath());
        if (!Files.exists(passwordPath)) {
            return "";
        }
        try {
            return Files.readString(passwordPath, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read GPKI password file: " + passwordPath, e);
        }
    }
}
