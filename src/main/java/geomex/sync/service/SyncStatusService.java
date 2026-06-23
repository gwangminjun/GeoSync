package geomex.sync.service;

import geomex.sync.model.SyncHistory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class SyncStatusService {

    private static final int MAX_HISTORY = 100;

    // RUNNING 항목은 별도 추적 (타입별 1개)
    private final java.util.Map<String, SyncHistory> running = new java.util.concurrent.ConcurrentHashMap<>();
    private final Deque<SyncHistory> history = new ConcurrentLinkedDeque<>();

    public void recordStart(String type) {
        SyncHistory h = new SyncHistory(type, LocalDateTime.now(), null, 0, 0, "RUNNING");
        running.put(type, h);
    }

    public void recordEnd(String type, int success, int error, boolean failed) {
        SyncHistory started = running.remove(type);
        LocalDateTime start = started != null ? started.startTime() : LocalDateTime.now();
        String status = failed ? "FAILED" : "SUCCESS";
        SyncHistory done = new SyncHistory(type, start, LocalDateTime.now(), success, error, status);

        history.addFirst(done);
        // 최대 100건 유지
        while (history.size() > MAX_HISTORY) history.removeLast();
    }

    public boolean isRunning(String type) {
        return running.containsKey(type);
    }

    public java.time.LocalDateTime getRunningStartTime(String type) {
        SyncHistory h = running.get(type);
        return h != null ? h.startTime() : null;
    }

    public List<SyncHistory> getRecentHistory(int limit) {
        Deque<SyncHistory> combined = new ArrayDeque<>();
        running.values().forEach(combined::addFirst); // 실행 중인 항목 상단에 표시
        history.stream().limit(limit).forEach(combined::addLast);
        return combined.stream().limit(limit).toList();
    }

    public Optional<SyncHistory> getLastRun(String type) {
        if (running.containsKey(type)) return Optional.of(running.get(type));
        return history.stream().filter(h -> h.type().equals(type)).findFirst();
    }

    public Optional<SyncHistory> getLastSuccess(String type) {
        return history.stream()
                .filter(h -> h.type().equals(type))
                .filter(h -> "SUCCESS".equals(h.status()))
                .findFirst();
    }

    public int todaySuccessCount() {
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        return history.stream()
                .filter(h -> h.startTime() != null && h.startTime().isAfter(todayStart))
                .mapToInt(SyncHistory::successCount)
                .sum();
    }

    public int todayErrorCount() {
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        return history.stream()
                .filter(h -> h.startTime() != null && h.startTime().isAfter(todayStart))
                .mapToInt(SyncHistory::errorCount)
                .sum();
    }
}
