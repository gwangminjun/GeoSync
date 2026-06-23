package geomex.sync.web;

import geomex.sync.service.KrasGpkiService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
public class GpkiController {
    private final KrasGpkiService gpkiService;

    public GpkiController(KrasGpkiService gpkiService) {
        this.gpkiService = gpkiService;
    }

    @ResponseBody
    @GetMapping("/api/gpki/status")
    public Map<String, Object> status() {
        return gpkiService.status();
    }
}
