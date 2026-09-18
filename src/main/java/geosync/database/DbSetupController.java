package geosync.database;

import geosync.database.DbSetupService.CreateResult;
import geosync.database.DbSetupService.TargetDbStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/db")
public class DbSetupController {

    private final DbSetupService dbSetupService;

    public DbSetupController(DbSetupService dbSetupService) {
        this.dbSetupService = dbSetupService;
    }

    /** 모든 대상 DB의 필수 테이블 존재 여부 확인 */
    @GetMapping("/status")
    public ResponseEntity<List<Map<String, Object>>> status() {
        List<TargetDbStatus> statuses = dbSetupService.checkAll();
        List<Map<String, Object>> result = statuses.stream().map(s -> {
            List<Map<String, Object>> tables = s.tables().stream().map(t -> Map.<String, Object>of(
                "name",   t.name(),
                "schema", t.schema(),
                "exists", t.exists()
            )).toList();
            return Map.<String, Object>of(
                "idx",    s.idx(),
                "name",   s.name(),
                "host",   s.host(),
                "dbname", s.dbname(),
                "tables", tables,
                "error",  s.error() != null ? s.error() : ""
            );
        }).toList();
        return ResponseEntity.ok(result);
    }

    /** 지정 대상 DB에 특정 테이블 생성 */
    @PostMapping("/create-table")
    public ResponseEntity<Map<String, Object>> createTable(
            @RequestParam int targetIdx,
            @RequestParam String tableName) {
        CreateResult r = dbSetupService.createTable(targetIdx, tableName);
        return ResponseEntity.ok(Map.of("success", r.success(), "message", r.message()));
    }
}
