package geomex.sync.kras;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class KrasFileDownloadService {

    private static final Logger log = LoggerFactory.getLogger(KrasFileDownloadService.class);

    public record DownloadResult(String name, String filePath, long bytes, boolean success, String error) {}

    public static final class JobState {
        public volatile boolean running = false;
        public volatile boolean done = false;
        public volatile int total = 0;
        public volatile int completed = 0;
        public volatile String current = "";
        public final List<DownloadResult> results = Collections.synchronizedList(new ArrayList<>());
        public volatile String fatalError = null;
    }

    private final AtomicReference<JobState> currentJob = new AtomicReference<>(new JobState());
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final KrasApiClient apiClient;

    public KrasFileDownloadService(KrasApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public boolean isRunning() { return downloading.get(); }

    public JobState getStatus() { return currentJob.get(); }

    @Async
    public void startDownload(String outputDirStr,
                               boolean cbndShp, boolean usezoneShp,
                               boolean jigaTxt, boolean landTxt) {
        if (!downloading.compareAndSet(false, true)) return;

        JobState job = new JobState();
        job.running = true;
        currentJob.set(job);

        try {
            Path outputDir = Path.of(outputDirStr);
            Files.createDirectories(outputDir);
            log.info("[FileDL] 시작 outputDir={} cbnd={} usezone={} jiga={} land={}",
                    outputDir, cbndShp, usezoneShp, jigaTxt, landTxt);

            // USEZONE 레이어 수를 미리 조회해 total 확정
            List<String> uzoneLayers = List.of();
            if (usezoneShp) {
                job.current = "USEZONE 레이어 목록 조회 중...";
                try {
                    uzoneLayers = fetchUzoneLayers();
                } catch (Exception e) {
                    log.error("[FileDL] 레이어 목록 조회 실패: {}", e.getMessage());
                    job.results.add(new DownloadResult("USEZONE 레이어 목록", outputDir.toString(),
                            0, false, "레이어 목록 조회 실패: " + e.getMessage()));
                }
            }
            job.total = (cbndShp ? 1 : 0) + uzoneLayers.size() + (jigaTxt ? 1 : 0) + (landTxt ? 1 : 0);

            // 1. 연속지적도 SHP (lsmd_cont_ldreg.* 로 받은 뒤 lp_pa_cbnd.* 이름으로도 복사 저장)
            if (cbndShp) {
                String layerCd = "LSMD_CONT_LDREG";
                job.current = "연속지적도 SHP (" + layerCd + ")";
                try {
                    String baseName = apiClient.downloadLayer(layerCd, outputDir);
                    copyAs(outputDir, baseName, "lp_pa_cbnd");
                    long size = sizeOf(outputDir, baseName, ".shp")
                              + sizeOf(outputDir, baseName, ".dbf")
                              + sizeOf(outputDir, baseName, ".shx");
                    job.results.add(new DownloadResult(layerCd, outputDir.resolve(baseName + ".shp").toString(), size, true, null));
                    log.info("[FileDL] {} 완료 ({}B, lp_pa_cbnd.*로도 저장)", layerCd, size);
                } catch (Exception e) {
                    log.error("[FileDL] {} 실패: {}", layerCd, e.getMessage());
                    job.results.add(new DownloadResult(layerCd, outputDir.toString(), 0, false, e.getMessage()));
                }
                job.completed++;
            }

            // 2. USEZONE SHP (각 레이어)
            for (String layerName : uzoneLayers) {
                job.current = "USEZONE SHP: " + layerName;
                try {
                    String baseName = apiClient.downloadLayer(layerName, outputDir);
                    long size = sizeOf(outputDir, baseName, ".shp")
                              + sizeOf(outputDir, baseName, ".dbf")
                              + sizeOf(outputDir, baseName, ".shx");
                    job.results.add(new DownloadResult(layerName, outputDir.resolve(baseName + ".shp").toString(), size, true, null));
                    log.info("[FileDL] {} 완료 ({}B)", layerName, size);
                } catch (Exception e) {
                    log.error("[FileDL] {} 실패: {}", layerName, e.getMessage());
                    job.results.add(new DownloadResult(layerName, outputDir.toString(), 0, false, e.getMessage()));
                }
                job.completed++;
            }

            // 3. 공시지가 TXT
            if (jigaTxt) {
                job.current = "공시지가 TXT (KRAS000039)";
                Path filePath = outputDir.resolve("kras_jiga.txt");
                try {
                    byte[] data = apiClient.downloadJigaTxt();
                    Files.write(filePath, data);
                    job.results.add(new DownloadResult("공시지가 TXT", filePath.toString(), data.length, true, null));
                    log.info("[FileDL] 공시지가 TXT 완료 ({}B)", data.length);
                } catch (Exception e) {
                    log.error("[FileDL] 공시지가 TXT 실패: {}", e.getMessage());
                    job.results.add(new DownloadResult("공시지가 TXT", filePath.toString(), 0, false, e.getMessage()));
                }
                job.completed++;
            }

            // 4. 토지대장 TXT
            if (landTxt) {
                job.current = "토지대장 TXT (KRAS000040)";
                Path filePath = outputDir.resolve("kras_land.txt");
                try {
                    byte[] data = apiClient.downloadLandTxt();
                    Files.write(filePath, data);
                    job.results.add(new DownloadResult("토지대장 TXT", filePath.toString(), data.length, true, null));
                    log.info("[FileDL] 토지대장 TXT 완료 ({}B)", data.length);
                } catch (Exception e) {
                    log.error("[FileDL] 토지대장 TXT 실패: {}", e.getMessage());
                    job.results.add(new DownloadResult("토지대장 TXT", filePath.toString(), 0, false, e.getMessage()));
                }
                job.completed++;
            }

            job.current = "완료";
            log.info("[FileDL] 전체 완료 ({}/{})", job.completed, job.total);

        } catch (Exception e) {
            log.error("[FileDL] 오류: {}", e.getMessage(), e);
            job.fatalError = e.getMessage();
        } finally {
            job.running = false;
            job.done = true;
            downloading.set(false);
        }
    }

    private List<String> fetchUzoneLayers() throws Exception {
        JsonNode res = apiClient.layerList();
        List<String> names = new ArrayList<>();
        for (JsonNode layer : res.path("layers")) {
            String name = layer.path("layerName").asText("");
            if (!name.isBlank() && isUsezoneLayer(name)) names.add(name);
        }
        return names;
    }

    private static boolean isUsezoneLayer(String layerName) {
        int idx = layerName.indexOf("LSMD_CONT_U");
        if (idx < 0) return false;
        int codeStart = idx + "LSMD_CONT_U".length();
        return codeStart < layerName.length() && Character.isLetter(layerName.charAt(codeStart));
    }

    private static long sizeOf(Path dir, String baseName, String ext) {
        try { return Files.size(dir.resolve(baseName + ext)); } catch (IOException e) { return 0; }
    }

    /** srcBaseName.shp/.dbf/.shx 를 같은 디렉토리에 targetBaseName 이름으로 복사 저장 */
    private static void copyAs(Path dir, String srcBaseName, String targetBaseName) throws IOException {
        for (String ext : new String[]{".shp", ".dbf", ".shx"}) {
            Path src = dir.resolve(srcBaseName + ext);
            if (Files.exists(src)) {
                Files.copy(src, dir.resolve(targetBaseName + ext), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
