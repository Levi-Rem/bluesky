package org.bluesky.training.common;

import org.bluesky.training.persistence.GroupSequenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** P01：训练组内严格递增序号（详细设计 5.0：组序号由数据库行锁保证）。 */
@Service
public class GroupSequenceAllocator {

    private final GroupSequenceMapper mapper;

    public GroupSequenceAllocator(GroupSequenceMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public long next(String groupId) {
        mapper.insertIfAbsent(groupId);
        Long current = mapper.lockCurrentValue(groupId);
        if (current == null) {
            throw new IllegalStateException("组序号行缺失: " + groupId);
        }
        long next = current + 1;
        mapper.updateValue(groupId, next);
        return next;
    }
}
