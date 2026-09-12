package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.persistence.InstructionV2Mapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

/** P09：每“航空器+冲突键”单一在途下发占位（详细设计 6.3.5）。 */
@Service
public class DispatchSlotService {

    private final InstructionV2Mapper mapper;

    public DispatchSlotService(InstructionV2Mapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void claim(String aircraftId, String conflictKey, String instructionId) {
        String holder = mapper.slotHolder(aircraftId, conflictKey);
        if (holder != null) {
            throw new V2DomainException("CHANNEL_DISPATCH_IN_PROGRESS", 409,
                    "同冲突键已有下发占位: " + holder, Arrays.asList("conflictKey"));
        }
        try {
            mapper.claimSlot(aircraftId, conflictKey, instructionId);
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            throw new V2DomainException("CHANNEL_DISPATCH_IN_PROGRESS", 409,
                    "同冲突键已有下发占位（并发）", Arrays.asList("conflictKey"));
        }
    }

    @Transactional
    public void release(String aircraftId, String conflictKey, String instructionId) {
        mapper.releaseSlot(aircraftId, conflictKey, instructionId);
    }

    @Transactional(readOnly = true)
    public boolean isOccupied(String aircraftId, String conflictKey) {
        return mapper.slotHolder(aircraftId, conflictKey) != null;
    }
}
