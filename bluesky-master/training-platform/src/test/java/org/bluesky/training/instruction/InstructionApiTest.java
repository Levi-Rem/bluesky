package org.bluesky.training.instruction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 评审 E7：v1 指令写入口停用（410）——v1 持续写 PENDING/APPEND 语义会破坏
 * v2 状态机（V11 已把存量 PENDING 迁走，详细设计 5.4 枚举封闭/8.5）。
 * 原本经由该入口的 18 个 v1 写路径用例随入口下线移除；
 * v1 读写语义的单元覆盖保留在 InstructionService/ProgressService 各自测试中。
 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstructionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v1InstructionWritePathIsGone() throws Exception {
        String aircraftId = seedAircraft();
        mockMvc.perform(post("/api/v1/aircraft/{aircraftId}/instructions", aircraftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"HDG 090\",\"insertion\":\"IMMEDIATE\"}"))
                .andExpect(status().isGone());
        // 停用必须真实生效：不得写入任何 v1 语义指令行
        org.junit.jupiter.api.Assertions.assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_instruction WHERE exercise_aircraft_id = ?",
                Long.class, aircraftId));
    }

    @Test
    void v1InstructionListStaysReadOnlyCompatible() throws Exception {
        String aircraftId = seedAircraft();
        mockMvc.perform(get("/api/v1/aircraft/{aircraftId}/instructions", aircraftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    private String seedAircraft() throws Exception {
        String groupId = "group-v1ins-" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                + "VALUES (?, ?, 'RUNNING', 600)", groupId, "v1 指令兼容组");
        String terminalId = "V1T-" + System.nanoTime();
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                + "exercise_group_id) VALUES (?, '机长席', 'PSEUDO_PILOT', ?)",
                terminalId, groupId);
        String aircraftId = "ac-v1ins-" + java.util.UUID.randomUUID();
        String callsign = "V1" + System.nanoTime() % 100000;
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key) VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', "
                        + "20, 8000, 250, 'ACTIVE', ?)",
                aircraftId, groupId, terminalId, callsign, callsign);
        return aircraftId;
    }
}
