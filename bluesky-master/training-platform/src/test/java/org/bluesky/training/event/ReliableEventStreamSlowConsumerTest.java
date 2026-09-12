package org.bluesky.training.event;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 评审 C6 行为测试：慢消费者断开策略（详细设计 9.5 规则 7）。
 * 累计事件数超过 SLOW_CONSUMER_EVENT_LIMIT 的连接必须收到携带
 * 最后成功序号的 stream.disconnected 通知并被主动断开——
 * 替换原 ReliableEventStreamServiceTest 中"只断言常量数值"的空心用例。
 */
@SpringBootTest(classes = TrainingPlatformApplication.class,
        properties = "bluesky.trusted-gateway.secret=test-gateway-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReliableEventStreamSlowConsumerTest {

    private static final String SECRET = "test-gateway-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessEventService eventService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void givenFastClientWhenMoreThanTwoThousandEventsAreReplayedThenRemainsConnected() throws Exception {
        String groupId = "group-slow-" + UUID.randomUUID();
        String terminalId = "PP-SLOW-" + System.nanoTime();
        String fingerprint = "digest-slow-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) "
                + "VALUES (?, ?, 'RUNNING')", groupId, "慢消费者组");
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                        + "exercise_group_id, frequency) VALUES (?, '机长席', 'PSEUDO_PILOT', ?, 118.100)",
                terminalId, groupId);
        jdbc.update("INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
                        + "certificate_fingerprint_digest) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), terminalId, groupId, fingerprint);
        int total = ReliableEventStreamService.SLOW_CONSUMER_EVENT_LIMIT + 5;
        for (int i = 0; i < total; i++) {
            final String payload = "{\"i\":" + i + "}";
            transactionTemplate.execute(status -> eventService.append(groupId,
                    "instruction.updated", payload,
                    Collections.singletonList(terminalId)));
        }

        MvcResult stream = mockMvc.perform(get("/api/v2/events")
                        .param("exerciseGroupId", groupId)
                        .param("terminalId", terminalId)
                        .header("X-Gateway-Secret", SECRET)
                        .header("X-Trusted-Caller-Type", "TERMINAL")
                        .header("X-Trusted-Caller-Id", terminalId)
                        .header("X-Trusted-Terminal-Id", terminalId)
                        .header("X-Trusted-Fingerprint-Digest", fingerprint)
                        .header("Accept", "text/event-stream"))
                .andReturn();

        // Wait for actual replay, not merely servlet async registration.
        for (int i = 0; i < 250 && !stream.getResponse().getContentAsString().contains("\"i\":"+(total-1)); i++) {
            Thread.sleep(20);
        }
        String body = stream.getResponse().getContentAsString();
        assertThat(body).contains("\"i\":"+(total-1));
        assertThat(body).doesNotContain("stream.disconnected","SLOW_CONSUMER");
    }
}
