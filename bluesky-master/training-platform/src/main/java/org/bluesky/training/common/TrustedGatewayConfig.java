package org.bluesky.training.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** P02：受信网关过滤器装配；secret 未配置时默认拒绝一切身份属性（fail-safe）。 */
@Configuration
public class TrustedGatewayConfig {

    @Bean
    public FilterRegistrationBean<TrustedCallerFilter> trustedCallerFilter(
            @Value("${bluesky.trusted-gateway.secret:}") String gatewaySecret,
            @Value("${bluesky.trusted-gateway.dev-terminal-id:}") String devTerminalId,
            org.bluesky.training.persistence.TerminalAdminMapper terminalMapper,
            TrustedCallerBindingService bindingService) {
        FilterRegistrationBean<TrustedCallerFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TrustedCallerFilter(gatewaySecret, bindingService,
                devTerminalId, terminalMapper));
        registration.setOrder(10);
        // v1 门面端点（如工作台删除按钮）同样需要终端身份解析；对不读身份的
        // v1 端点仅多设一个请求属性，行为不变
        registration.addUrlPatterns("/api/v2/*", "/api/v1/*");
        return registration;
    }
}
