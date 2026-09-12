package org.bluesky.training.workstation;

import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.TerminalAccessPolicy;
import org.bluesky.training.common.V2Api;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** P06：v2 bootstrap（详细设计 9.2/9.5.4）。 */
@V2Api
@RestController
@RequestMapping("/api/v2/workstations")
public class WorkstationControllerV2 {

    private final CallerContextResolver resolver;
    private final TerminalAccessPolicy terminalAccessPolicy;
    private final WorkstationSnapshotService snapshotService;

    public WorkstationControllerV2(CallerContextResolver resolver,
                                   TerminalAccessPolicy terminalAccessPolicy,
                                   WorkstationSnapshotService snapshotService) {
        this.resolver = resolver;
        this.terminalAccessPolicy = terminalAccessPolicy;
        this.snapshotService = snapshotService;
    }

    @GetMapping("/{terminalId}/bootstrap")
    public Map<String, Object> bootstrap(@PathVariable("terminalId") String terminalId,
                                         HttpServletRequest request) {
        CallerContext caller = resolver.resolve(request);
        terminalAccessPolicy.requireTerminalWrite(caller);
        terminalAccessPolicy.requireSameGroup(caller, caller.exerciseGroupId());
        if (!terminalId.equals(caller.terminalId())) {
            throw new org.bluesky.training.common.V2DomainException(
                    "TERMINAL_NOT_IN_GROUP", 403, "终端只能引导自己的工作台");
        }
        return snapshotService.bootstrap(terminalId, caller.exerciseGroupId());
    }

    @GetMapping("/current/bootstrap")
    public Map<String,Object> current(HttpServletRequest request) {
        CallerContext caller=resolver.resolveTerminal(request);
        return snapshotService.bootstrap(caller.terminalId(),caller.exerciseGroupId());
    }
}
