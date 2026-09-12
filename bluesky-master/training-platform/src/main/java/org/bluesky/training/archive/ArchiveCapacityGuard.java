package org.bluesky.training.archive;

/**
 * P19：归档容量与恢复容差（详细设计 2.2 §12.3/§13.2.4）。
 * 70% 告警、85% 禁止开始新训练组、95% 紧急 PAUSE；
 * 恢复容差 0.1 NM / 100 ft / 5 kt。
 */
public class ArchiveCapacityGuard {

    public static final double WARN_THRESHOLD = 0.70;
    public static final double BLOCK_START_THRESHOLD = 0.85;
    public static final double EMERGENCY_PAUSE_THRESHOLD = 0.95;

    /** 容量分级动作。 */
    public enum Action {
        NONE, WARN, BLOCK_NEW_START, EMERGENCY_PAUSE
    }

    public static Action actionForUsage(double usedFraction) {
        if (usedFraction >= EMERGENCY_PAUSE_THRESHOLD) {
            return Action.EMERGENCY_PAUSE;
        }
        if (usedFraction >= BLOCK_START_THRESHOLD) {
            return Action.BLOCK_NEW_START;
        }
        if (usedFraction >= WARN_THRESHOLD) {
            return Action.WARN;
        }
        return Action.NONE;
    }

    /** 95% 紧急 PAUSE 确认顺序：先向仍运行实例发送 PAUSE，确认停止运动后才进 RECOVERING。 */
    public static boolean pauseConfirmedForEmergency(boolean pauseRequestSent,
                                                     boolean adapterConfirmedPaused) {
        return pauseRequestSent && adapterConfirmedPaused;
    }

    /** 恢复容差校验（详细设计 13.2.4：0.1 NM / 100 ft / 5 kt，航路和程序阶段必须完全一致）。 */
    public static boolean withinRecoveryTolerance(double positionErrorNm,
                                                  double altitudeErrorFt,
                                                  double iasErrorKt) {
        return positionErrorNm <= 0.1 && altitudeErrorFt <= 100.0 && iasErrorKt <= 5.0;
    }
}
