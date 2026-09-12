package org.bluesky.training.event;

import org.bluesky.training.persistence.BusinessEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** P06：流纪元（详细设计 9.5.3：重启不变，显式重建才更换）。 */
@Service
public class StreamEpochService {

    private final BusinessEventMapper mapper;

    public StreamEpochService(BusinessEventMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public String currentEpoch() {
        if (mapper.countEpochRow() == 0) {
            mapper.insertEpoch("stream-" + UUID.randomUUID());
        }
        return mapper.findEpoch();
    }

    /** 仅管理员执行不可兼容事件投影重建时调用（详细设计 9.5.3）。 */
    @Transactional
    public String rebuildProjection() {
        String next = "stream-" + UUID.randomUUID();
        if (mapper.countEpochRow() == 0) {
            mapper.insertEpoch(next);
        } else {
            mapper.updateEpoch(next);
        }
        return next;
    }
}
