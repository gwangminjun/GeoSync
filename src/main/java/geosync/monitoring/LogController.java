package geosync.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Controller
@RequestMapping("/logs")
public class LogController {

    private static final int TAIL_LINES = 200;

    @Value("${LOG_DIR:./logs}")
    private String logDir;

    @GetMapping
    public String logsPage(Model model) {
        model.addAttribute("currentPage", "logs");
        return "logs";
    }

    @GetMapping(value = "/content", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    @ResponseBody
    public String logContent() {
        Path logFile = Path.of(logDir, "geosync.log");
        if (!Files.exists(logFile)) {
            return "(로그 파일 없음: " + logFile.toAbsolutePath() + ")";
        }
        try {
            return tailLines(logFile, TAIL_LINES);
        } catch (IOException e) {
            return "(로그 읽기 오류: " + e.getMessage() + ")";
        }
    }

    private String tailLines(Path path, int n) throws IOException {
        // UTF-8로 전체 라인 읽기 후 마지막 n줄만 반환
        List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int from = Math.max(0, allLines.size() - n);
        return String.join("\n", allLines.subList(from, allLines.size()));
    }
}
