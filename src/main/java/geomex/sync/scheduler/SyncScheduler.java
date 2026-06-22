package geomex.sync.scheduler;

import geomex.sync.worker.KaisWorker;
import geomex.sync.worker.KrasWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final KrasWorker krasWorker;
    private final KaisWorker kaisWorker;

    // 중복 실행 방지 플래그
    private final AtomicBoolean krasRunning = new AtomicBoolean(false);
    private final AtomicBoolean kaisRunning = new AtomicBoolean(false);

    @Value("${sync.enabled:true}")
    private boolean syncEnabled;

    public SyncScheduler(KrasWorker krasWorker, KaisWorker kaisWorker) {
        this.krasWorker = krasWorker;
        this.kaisWorker = kaisWorker;
    }

    // 매일 04:30 — KRAS 동기화
    @Scheduled(cron = "${kras.schedule:0 30 4 * * *}")
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

    // 매일 03:30 — KAIS 동기화
    @Scheduled(cron = "${kais.schedule:0 30 3 * * *}")
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
}
