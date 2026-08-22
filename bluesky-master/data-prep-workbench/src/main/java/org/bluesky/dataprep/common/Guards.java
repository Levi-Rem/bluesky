package org.bluesky.dataprep.common;

/** 业务守卫：BLUESKY 只读来源保护与公共校验。 */
public final class Guards {

    private Guards() {
    }

    /** BLUESKY 来源记录只读：禁止直接编辑/删除，需复制为人工副本后操作。 */
    public static void requireEditableSource(String sourceType, String action) {
        if ("BLUESKY".equals(sourceType)) {
            throw ApiException.badRequest(sourceType + " 只读来源记录不允许" + action + "，请先复制为人工副本");
        }
    }

    public static void requireCodeUnique(boolean exists, String code) {
        if (exists) {
            throw ApiException.conflict("业务编码已存在：" + code);
        }
    }

    public static void requireUpdated(int updatedRows, String id) {
        if (updatedRows == 0) {
            throw ApiException.conflict("记录已被他人修改或不存在（revision 冲突）：" + id);
        }
    }
}
