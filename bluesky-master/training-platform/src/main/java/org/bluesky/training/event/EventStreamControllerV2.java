package org.bluesky.training.event;

import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.V2Api;
import org.bluesky.training.common.TerminalAccessPolicy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;

/** P06：v2 SSE（详细设计 9.5：Last-Event-ID 续传；越窗 409 要求重新 bootstrap）。 */
@V2Api
@RestController
@RequestMapping("/api/v2/events")
public class EventStreamControllerV2 {

    private final CallerContextResolver resolver;
    private final ReliableEventStreamService streamService;
    private final TerminalAccessPolicy terminalAccessPolicy;

    public EventStreamControllerV2(CallerContextResolver resolver,
                                   ReliableEventStreamService streamService,
                                   TerminalAccessPolicy terminalAccessPolicy) {
        this.resolver = resolver;
        this.streamService = streamService;
        this.terminalAccessPolicy = terminalAccessPolicy;
    }

    @GetMapping()
    public SseEmitter events(@RequestParam("exerciseGroupId") String exerciseGroupId,
                             @RequestParam("terminalId") String terminalId,
                             @RequestHeader(value = "Last-Event-ID", required = false)
                             String lastEventId,
                             HttpServletRequest request) {
        CallerContext caller = resolver.resolve(request);
        terminalAccessPolicy.requireTerminalWrite(caller);
        terminalAccessPolicy.requireSameGroup(caller, exerciseGroupId);
        if (!terminalId.equals(caller.terminalId())) {
            throw new org.bluesky.training.common.V2DomainException(
                    "TERMINAL_NOT_IN_GROUP", 403, "终端只能订阅自己的事件流");
        }
        long afterSequence = 0;
        if(lastEventId==null)lastEventId=request.getParameter("cursor");
        if (lastEventId != null && !lastEventId.trim().isEmpty()) {
            // 游标过期/格式错误/纪元变化在此抛出 409
            afterSequence = streamService.prepareCursor(terminalId, lastEventId.trim());
        }
        return streamService.connect(terminalId, afterSequence);
    }
}
