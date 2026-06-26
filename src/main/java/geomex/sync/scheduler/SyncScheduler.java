package geomex.sync.scheduler;

import geomex.sync.service.RuntimeSettingsService;
import geomex.sync.worker.KrasWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final KrasWorker krasWorker;
    private final RuntimeSettingsService settings;

    private final AtomicBoolean krasRunning = new AtomicBoolean(false);
    private final AtomicBoolean krasCollectRunning = new AtomicBoolean(false);
    private final AtomicBoolean krasLoadRunning = new AtomicBoolean(false);

    public SyncScheduler(KrasWorker krasWorker, RuntimeSettingsService settings) {
        this.krasWorker = krasWorker;
        this.settings = settings;
    }

    @Deprecated
    public void runKras() {
        if (!settings.syncEnabled()) return;
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

    @Deprecated
    public void runKrasCollect() {
        if (!settings.syncEnabled()) return;
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

    public void runKrasScheduledLoad() {
        if (!settings.syncEnabled()) return;
        if (!krasLoadRunning.compareAndSet(false, true)) {
            log.warn("[KRAS] 적재 이미 진행 중 — 스킵");
            return;
        }
        try {
            krasWorker.runScheduledLoad();
        } finally {
            krasLoadRunning.set(false);
        }
    }

    @Deprecated
    public void runKrasLoad() {
        runKrasLoad(null, (String) null, null);
    }

    @Deprecated
    public void runKrasLoad(List<Integer> targetIndices, String schemaOverride) {
        runKrasLoad(targetIndices, schemaOverride, null);
    }

    @Deprecated
    public void runKrasLoad(List<Integer> targetIndices, String schemaOverride, java.util.Set<String> tableFilter) {
        if (!settings.syncEnabled()) return;
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

    @Deprecated
    public void runKrasLoad(List<Integer> targetIndices, Map<Integer, String> schemaMap, java.util.Set<String> fileFilter) {
        if (!settings.syncEnabled()) return;
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

    public void runKrasDirectLoad(List<Integer> targetIndices, Map<Integer, String> schemaMap) {
        runKrasDirectLoad(targetIndices, schemaMap, null);
    }

    public void runKrasDirectLoad(List<Integer> targetIndices, Map<Integer, String> schemaMap,
                                  java.util.Set<String> tableFilter) {
        if (!settings.syncEnabled()) return;
        if (!krasLoadRunning.compareAndSet(false, true)) {
            log.warn("[KRAS] 적재 이미 진행 중 — 스킵");
            return;
        }
        try {
            krasWorker.runDirectLoad(targetIndices, schemaMap, "MANUAL", tableFilter);
        } finally {
            krasLoadRunning.set(false);
        }
    }

    @Async
    @Deprecated
    public void triggerKrasAsync() {
        runKras();
    }

    @Async
    @Deprecated
    public void triggerKrasCollectAsync() {
        runKrasCollect();
    }

    @Async
    @Deprecated
    public void triggerKrasLoadAsync() {
        runKrasLoad(null, (String) null, null);
    }

    @Async
    @Deprecated
    public void triggerKrasLoadAsync(List<Integer> targetIndices, String schemaOverride) {
        runKrasLoad(targetIndices, schemaOverride, null);
    }

    @Async
    @Deprecated
    public void triggerKrasLoadAsync(List<Integer> targetIndices, String schemaOverride, java.util.Set<String> tableFilter) {
        runKrasLoad(targetIndices, schemaOverride, tableFilter);
    }

    @Async
    @Deprecated
    public void triggerKrasLoadAsync(List<Integer> targetIndices, Map<Integer, String> schemaMap, java.util.Set<String> fileFilter) {
        runKrasLoad(targetIndices, schemaMap, fileFilter);
    }

    @Async
    public void triggerKrasDirectLoadAsync(List<Integer> targetIndices, Map<Integer, String> schemaMap) {
        runKrasDirectLoad(targetIndices, schemaMap, null);
    }

    @Async
    public void triggerKrasDirectLoadAsync(List<Integer> targetIndices, Map<Integer, String> schemaMap,
                                           java.util.Set<String> tableFilter) {
        runKrasDirectLoad(targetIndices, schemaMap, tableFilter);
    }
}
