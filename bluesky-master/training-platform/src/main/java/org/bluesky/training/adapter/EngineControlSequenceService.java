package org.bluesky.training.adapter;

import org.bluesky.training.persistence.EngineInstanceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commit sequence reservations independently so Java restarts never reuse a sequence. */
@Service
public class EngineControlSequenceService {
    private final EngineInstanceMapper mapper;
    public EngineControlSequenceService(EngineInstanceMapper mapper) { this.mapper = mapper; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long next(String instanceId) {
        if (mapper.incrementOutboundSequence(instanceId) != 1)
            throw new AdapterProtocolException("ENGINE_INSTANCE_UNAVAILABLE", "引擎实例不存在");
        return mapper.findById(instanceId).getLastOutboundSequence();
    }
}
