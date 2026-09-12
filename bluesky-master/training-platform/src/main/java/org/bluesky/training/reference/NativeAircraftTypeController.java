package org.bluesky.training.reference;

import org.bluesky.training.adapter.AdapterActionSender;
import org.bluesky.training.common.CallerContextResolver;
import org.bluesky.training.common.TerminalAccessPolicy;
import org.bluesky.training.common.V2Api;
import org.bluesky.training.common.V2DomainException;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/** Query the current group's native performance catalog through its HELLO capability response. */
@V2Api
@RestController
public class NativeAircraftTypeController {
    private final CallerContextResolver resolver;
    private final TerminalAccessPolicy access;
    private final AdapterActionSender sender;

    public NativeAircraftTypeController(CallerContextResolver resolver, TerminalAccessPolicy access, AdapterActionSender sender) {
        this.resolver=resolver; this.access=access; this.sender=sender;
    }

    @GetMapping("/api/v2/exercise-groups/{groupId}/reference/aircraft-types")
    public Object search(@PathVariable String groupId, @RequestParam(defaultValue="") String query, HttpServletRequest request) {
        access.requireSameGroup(resolver.resolveTerminal(request),groupId);
        String id=UUID.randomUUID().toString();
        Map<String,Object> action=new LinkedHashMap<>();
        action.put("exerciseGroupId",groupId); action.put("outboxEventId",id);
        action.put("eventType","HELLO"); action.put("aircraftTypeQuery",query.trim().toUpperCase(Locale.ROOT));
        try {
            Map<?,?> payload=(Map<?,?>)sender.send(action).get("payload");
            if (payload==null || !(payload.get("aircraftTypes") instanceof List))
                throw new V2DomainException("FEATURE_NOT_SUPPORTED",422,"当前引擎未提供机型目录查询能力");
            return payload.get("aircraftTypes");
        } finally { sender.confirmed(id); }
    }
}
