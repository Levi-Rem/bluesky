package org.bluesky.training.instruction;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * v1 指令写入口已停用（评审 E7；详细设计 5.4 枚举封闭 / 8.5）：
 * v1 持续写 PENDING/APPEND 语义会破坏 v2 状态机（V11 已把存量 PENDING 迁走）。
 * 写路径统一走 POST /api/v2/aircraft/{id}/instructions；列表保留只读兼容。
 */
@RestController
@RequestMapping("/api/v1/aircraft/{aircraftId}/instructions")
public class InstructionController {
    private final InstructionService instructionService;

    public InstructionController(InstructionService instructionService) {
        this.instructionService = instructionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.GONE)
    public void create(@PathVariable String aircraftId) {
        // 410 GONE：调用方必须迁移到 v2 指令入口
    }

    @GetMapping
    public List<InstructionResponse> list(@PathVariable String aircraftId) {
        return instructionService.list(aircraftId);
    }
}
