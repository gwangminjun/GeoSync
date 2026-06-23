package geomex.sync.scheduler;

import geomex.sync.worker.KaisWorker;
import geomex.sync.worker.KrasWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final KrasWorker krasWorker;
    private final KaisWorker kaisWorker;

    // 중복 실행 방지 플래그
    private final AtomicBoolean krasRunning = new AtomicBoolean(false);
    private final AtomicBoolean kaisRunning = new AtomicBoolean(false);
    private final AtomicBoolean krasCollectRunning = new AtomicBoolean(false);
    private final AtomicBoolean krasLoadRunning = new AtomicBoolean(false);

    @Value("${sync.enabled:true}")
    private boolean syncEnabled;

    public SyncScheduler(KrasWorker krasWorker, KaisWorker kaisWorker) {
        this.krasWorker = krasWorker;
        this.kaisWorker = kaisWorker;
    }

    public void runKras() {
        if (!syncEnabled) return;
        if (!krasRunning.compareAndSet(false, true)) {
            log.warn("[KRAS] 이전 작업 진행 중 — 스킵");
            return;
        }
        try {
            krasWorker.run();
        } finally {
            krasRunning.set(false);
        }
    }

    public void runKais() {
        if (!syncEnabled) return;
        if (!kaisRunning.compareAndSet(false, true)) {
            log.warn("[KAIS] 이전 작업 진행 중 — 스킵");
            return;
        }
        try {
            kaisWorker.run();
        } finally {
            kaisRunning.set(false);
        }
    }

    public void runKrasCollect() {
        if (!syncEnabled) return;
        if (!krasCollectRunning.compareAndSet(false, true)) {
            log.warn("[KRAS] 수집 이미 진행 중 — 스킵");
            return;
        }
        try {
            krasWorker.runCollect();
        } finally {
            krasCollectRunning.set(false);
        }
    }

    public void runKrasLoad() {
        runKrasLoad(null, (String) null, null);
    }

    public void runKrasLoad(List<Integer> targetIndices, String schemaOverride) {
        runKrasLoad(targetIndices, schemaOverride, null);
    }

    public void runKrasLoad(List<Integer> targetIndices, String schemaOverride, java.util.Set<String> tableFilter) {
        if (!syncEnabled) return;
        if (!krasLoadRunning.compareAndSet(false, true)) {
            log.warn("[KRAS] 적재 이미 진행 중 — 스킵");
            return;
        }
        try {
            krasWorker.runLoad(targetIndices, schemaOverride, tableFilter);
        } finally {
            krasLoadRunning.set(false);
        }
    }

    public void runKrasLoad(List<Integer> targetIndices, Map<Integer, String> schemaMap, java.util.Set<String> fileFilter) {
        if (!syncEnabled) return;
        if (!krasLoadRunning.compareAndSet(false, true)) {
            log.warn("[KRAS] 적재 이미 진행 중 — 스킵");
            return;
        }
        try {
            krasWorker.runLoad(targetIndices, schemaMap, fileFilter);
        } finally {
            krasLoadRunning.set(false);
        }
    }

    // 웹 UI 수동 트리거용 (별도 스레드, AtomicBoolean 동일 적용)
    @Async
    public void triggerKrasAsync() {
        runKras();
    }

    @Async
    public void triggerKaisAsync() {
        runKais();
    }

    @Async
    public void triggerKrasCollectAsync() {
        runKrasCollect();
    }

    @Async
    public void triggerKrasLoadAsync() {
        runKrasLoad(null, (String) null, null);
    }

    @Async
    public void triggerKrasLoadAsync(List<Integer> targetIndices, String schemaOverride) {
        runKrasLoad(targetIndices, schemaOverride, null);
    }

    @Async
    public void triggerKrasLoadAsync(List<Integer> targetIndices, String schemaOverride, java.util.Set<String> tableFilter) {
        runKrasLoad(targetIndices, schemaOverride, tableFilter);
    }

    @Async
    public void triggerKrasLoadAsync(List<Integer> targetIndices, Map<Integer, String> schemaMap, java.util.Set<String> fileFilter) {
        runKrasLoad(targetIndices, schemaMap, fileFilter);
    }
}
