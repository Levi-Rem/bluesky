package org.bluesky.training.aircraft;

import org.bluesky.training.persistence.AircraftV2Mapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * P0-8（评审）：IDENT 应答机识别到期清除。
 * 以组仿真时钟为准（暂停冻结语义）：expires_at ≤ 组当前仿真时刻即清除；
 * 帧推进接线（C7）后该扫描自然跟随仿真时间。
 */
@Component
public class TransponderIdentExpiryWatcher {

    private final AircraftV2Mapper aircraftMapper;

    public TransponderIdentExpiryWatcher(AircraftV2Mapper aircraftMapper) {
        this.aircraftMapper = aircraftMapper;
    }

    @Scheduled(fixedDelay = 1000)
    public void expireDueIdentifications() {
        aircraftMapper.expireDueTransponderIdent();
    }
}
