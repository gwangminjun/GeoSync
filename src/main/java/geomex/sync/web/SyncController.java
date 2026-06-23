package geomex.sync.web;

import geomex.sync.scheduler.SyncScheduler;
import geomex.sync.service.SyncStatusService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/sync")
public class SyncController {

    private final SyncScheduler scheduler;
    private final SyncStatusService statusService;

    public SyncController(SyncScheduler scheduler, SyncStatusService statusService) {
        this.scheduler = scheduler;
        this.statusService = statusService;
    }

    @PostMapping("/kras")
    public String triggerKras(RedirectAttributes ra) {
        if (statusService.isRunning("KRAS")) {
            ra.addFlashAttribute("message", "KRAS 동기화가 이미 실행 중입니다.");
        } else {
            scheduler.triggerKrasAsync();
            ra.addFlashAttribute("message", "KRAS 동기화를 시작했습니다.");
        }
        return "redirect:/";
    }

    @PostMapping("/kais")
    public String triggerKais(RedirectAttributes ra) {
        if (statusService.isRunning("KAIS")) {
            ra.addFlashAttribute("message", "KAIS 동기화가 이미 실행 중입니다.");
        } else {
            scheduler.triggerKaisAsync();
            ra.addFlashAttribute("message", "KAIS 동기화를 시작했습니다.");
        }
        return "redirect:/";
    }
}
