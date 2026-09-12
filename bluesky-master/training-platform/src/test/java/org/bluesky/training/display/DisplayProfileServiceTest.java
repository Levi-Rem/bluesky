package org.bluesky.training.display;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P18：屏幕方案与标牌布局（详细设计 2.2 §5.7/§8.7）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class DisplayProfileServiceTest {

    @Autowired
    private DisplayProfileService profileService;

    @Autowired
    private org.bluesky.training.persistence.DisplayProfileMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String newTerminal() {
        String terminalId = "DP-" + System.nanoTime();
        String groupId = "group-dp-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')",
                groupId, "显示组");
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                + "exercise_group_id) VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", terminalId, groupId);
        return terminalId;
    }

    @Test
    void givenFirstProfileWhenCreatedThenBecomesDefaultAndRenamedUnique() {
        String terminalId = newTerminal();

        Map<String, Object> first = profileService.create(terminalId, "标准态势",
                "{\"rangeNm\":100}");
        assertEquals(1, ((Number) first.get("is_default")).intValue(),
                "首个方案自动成为默认");

        // 同终端名称唯一（数据库约束兜底）
        assertThrows(DataIntegrityViolationException.class, () -> transactionTemplate.execute(
                status -> mapper.insert(UUID.randomUUID().toString(), terminalId, "标准态势",
                        "{}")));

        assertEquals(1, profileService.list(terminalId).size());
    }

    @Test
    void givenTwentyProfileLimitWhenExceededThenRejected() {
        String terminalId = newTerminal();
        for (int i = 1; i <= 20; i++) {
            profileService.create(terminalId, "方案" + i, "{}");
        }
        V2DomainException limit = assertThrows(V2DomainException.class,
                () -> profileService.create(terminalId, "方案21", "{}"));
        assertEquals("INVALID_INSTRUCTION", limit.code());
        assertTrue(limit.getMessage().contains("20"));
    }

    @Test
    void givenDefaultProfileWhenDeletedThenRejectedOthersDeleted() {
        String terminalId = newTerminal();
        Map<String, Object> first = profileService.create(terminalId, "默认", "{}");
        Map<String, Object> second = profileService.create(terminalId, "备用", "{}");

        V2DomainException rejected = assertThrows(V2DomainException.class,
                () -> profileService.delete(String.valueOf(first.get("id"))));
        assertEquals(409, rejected.httpStatus());
        assertTrue(rejected.getMessage().contains("默认"));

        profileService.delete(String.valueOf(second.get("id")));
        assertEquals(1, profileService.list(terminalId).size());

        // applyDefault 返回默认且不触碰业务状态
        Map<String, Object> applied = profileService.applyDefault(terminalId);
        assertEquals("默认", applied.get("name"));
    }

    @Test
    void givenLabelLayoutWhenSavedDeletedThenTerminalAircraftUniqueHolds() {
        String terminalId = newTerminal();
        String aircraftId = "ac-dp-" + UUID.randomUUID();

        profileService.saveLabelLayout(terminalId, aircraftId, 35.0, 20, "NORMAL", true);

        // 重复保存同终端+航空器 → 合并更新不重复（评审 F12：方言中立 UPDATE-then-INSERT）
        profileService.saveLabelLayout(terminalId, aircraftId, 90.0, 30, "EXTENDED", false);
        List<Map<String, Object>> layouts = mapper.labelLayoutsForTerminal(terminalId);
        assertEquals(1, layouts.size());
        assertEquals(90.0, ((Number) layouts.get(0).get("angle_deg")).doubleValue());

        // 删除恢复自动布局
        mapper.deleteLabelLayout(terminalId, aircraftId);
        assertEquals(0, mapper.labelLayoutsForTerminal(terminalId).size());
    }
}
