package org.bluesky.training.common;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/** P02：从过滤器注入的属性解析调用方上下文；拒绝身份缺失。 */
@Component
public class CallerContextResolver {

    public CallerContext resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object attributed = request.getAttribute(TrustedCallerFilter.CALLER_CONTEXT_ATTRIBUTE);
        return attributed instanceof CallerContext ? (CallerContext) attributed : null;
    }

    /** 终端身份；供 v2 控制器/服务获取当前终端调用方。 */
    public CallerContext resolveTerminal(HttpServletRequest request) {
        CallerContext caller = resolve(request);
        if (caller == null || caller.callerType() != CallerContext.CallerType.TERMINAL) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403, "需要受信终端身份");
        }
        return caller;
    }

    /** 服务身份（编排/运维）；终端身份不得冒充服务。 */
    public CallerContext resolveService(HttpServletRequest request) {
        CallerContext caller = resolve(request);
        if (caller == null || caller.callerType() == CallerContext.CallerType.TERMINAL) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403, "需要受信服务身份");
        }
        return caller;
    }

    public CallerContext resolveCurrent() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : resolve(attributes.getRequest());
    }
}
