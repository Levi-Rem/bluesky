package org.bluesky.training.exercise;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exercise-groups")
public class ExerciseGroupController {
    private final ExerciseGroupService exerciseGroupService;

    public ExerciseGroupController(ExerciseGroupService exerciseGroupService) {
        this.exerciseGroupService = exerciseGroupService;
    }

    @PostMapping("/{groupId}/start")
    public ExerciseGroupResponse start(@PathVariable String groupId) {
        return exerciseGroupService.start(groupId);
    }

    @PostMapping("/{groupId}/pause")
    public ExerciseGroupResponse pause(@PathVariable String groupId) {
        return exerciseGroupService.pause(groupId);
    }

    @PostMapping("/{groupId}/resume")
    public ExerciseGroupResponse resume(@PathVariable String groupId) {
        return exerciseGroupService.resume(groupId);
    }
}
