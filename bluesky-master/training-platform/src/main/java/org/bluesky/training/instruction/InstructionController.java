package org.bluesky.training.instruction;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/aircraft/{aircraftId}/instructions")
public class InstructionController {
    private final InstructionService instructionService;

    public InstructionController(InstructionService instructionService) {
        this.instructionService = instructionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InstructionResponse create(@PathVariable String aircraftId,
                                      @RequestBody CreateInstructionRequest request) {
        return instructionService.create(aircraftId, request);
    }

    @GetMapping
    public List<InstructionResponse> list(@PathVariable String aircraftId) {
        return instructionService.list(aircraftId);
    }
}
