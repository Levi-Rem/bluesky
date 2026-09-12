package org.bluesky.training.assignment;

/** P08：指令所有权策略（详细设计 5.3）：查看对全组开放，覆盖/取消只归当前责任席。 */
public final class InstructionOwnershipPolicy {

    private InstructionOwnershipPolicy() {
    }

    /** 全组态势查看：组内终端都能看（非本席只读）。 */
    public static boolean canView(String viewerTerminalId, String groupOfViewer,
                                  String currentTerminalId, String groupOfAircraft) {
        return groupOfViewer != null && groupOfViewer.equals(groupOfAircraft);
    }

    /** 覆盖（REPLACE）只能由当前责任席提交；来源审计保留 sourceTerminalId。 */
    public static boolean canOverride(String viewerTerminalId, String currentTerminalId) {
        return viewerTerminalId != null && viewerTerminalId.equals(currentTerminalId);
    }

    /** 取消等待或执行中的指令同样只归当前责任席。 */
    public static boolean canCancel(String viewerTerminalId, String currentTerminalId) {
        return canOverride(viewerTerminalId, currentTerminalId);
    }
}
