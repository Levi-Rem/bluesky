package org.bluesky.training.instruction;

import org.springframework.stereotype.Service;

/**
 * P09：完成判据（详细设计 2.2 §6.3.3/6.3.4 + 默认业务预算表）。
 * 纯时间推进逻辑：目标容差 + 仿真稳定窗 + 最大执行预算；暂停冻结预算。
 */
@Service
public class InstructionCompletionService {

    /** 稳定窗：连续满足容差的仿真时长达到窗口即完成（抖动重置）。 */
    public static class StableWindow {
        private final double requiredSeconds;
        private Double satisfiedSince;

        public StableWindow(double requiredSeconds) {
            this.requiredSeconds = requiredSeconds;
        }

        /** 返回 true 表示本次评估后达到稳定完成。 */
        public boolean evaluate(boolean withinTolerance, double simulationTimeSeconds) {
            if (!withinTolerance) {
                satisfiedSince = null;
                return false;
            }
            if (satisfiedSince == null) {
                satisfiedSince = simulationTimeSeconds;
                return requiredSeconds <= 0;
            }
            return simulationTimeSeconds - satisfiedSince >= requiredSeconds;
        }

        public void reset() {
            satisfiedSince = null;
        }
    }

    /** 业务预算到期检查；paused 时预算不推进（冻结）。 */
    public static boolean budgetExceeded(double budgetSeconds, double elapsedBusySeconds) {
        return elapsedBusySeconds >= budgetSeconds;
    }

    /** 默认业务预算表（详细设计 6.3；单位：仿真秒）。 */
    public static double defaultBudgetSeconds(String type) {
        switch (type == null ? "" : type) {
            case "HDG":
            case "LEFT":
            case "RIGHT":
                return 180.0;
            case "ALT":
            case "VS":
                return 300.0;
            case "SPD":
            case "MACH":
            case "NSPEED":
                return 600.0;
            case "RTE":
            case "P_LEVEL":
            case "P_TIME":
            case "SIDSTAR":
                return 30.0;
            // 直飞/恢复/等待/径向截获需真实飞行时间（受风与航路影响）
            case "DCT":
            case "RESUME":
                return 1800.0;
            case "HOLD":
            case "VOR":
                return 900.0;
            case "ORBIT":
                return 3600.0;
            case "OFFSET":
                return 30.0;
            case "TAKEOFF":
                return 600.0;
            case "MISSED":
                return 900.0;
            case "ILS":
                return 1800.0;
            default:
                return 30.0;
        }
    }

    /** AFTER_COMPLETION 排队预算。 */
    public static final double QUEUE_WAIT_BUDGET_SECONDS = 1800.0;
}
