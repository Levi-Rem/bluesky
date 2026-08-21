package org.bluesky.dataprep.common;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 全局修订号：每次业务写操作成功后 +1，前端页头「修订 N」轮询展示。
 */
@Service
public class RevisionService {

    private final WorkbenchStateMapper workbenchStateMapper;

    public RevisionService(WorkbenchStateMapper workbenchStateMapper) {
        this.workbenchStateMapper = workbenchStateMapper;
    }

    public long current() {
        Long revision = workbenchStateMapper.selectRevision();
        return revision == null ? 0L : revision;
    }

    @Transactional
    public void increment() {
        workbenchStateMapper.incrementRevision();
    }
}
