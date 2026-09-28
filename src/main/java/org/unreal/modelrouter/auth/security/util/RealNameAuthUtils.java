package org.unreal.modelrouter.auth.security.util;

/**
 * 实名认证状态判断工具。
 *
 * <p>算力平台 sldd_system_users 表规则：
 * <ul>
 *     <li>user_type=1（企业用户）且 verify_status=4（企业已实名认证）</li>
 *     <li>user_type=2（个人用户）且 verify_status=2（个人已实名认证）</li>
 * </ul>
 * 仅上述严格成对的组合视为已实名认证。
 */
public final class RealNameAuthUtils {

    private RealNameAuthUtils() {
        // 工具类禁止实例化
    }

    /**
     * 判断用户是否已完成实名认证。
     *
     * @param userType     用户类型：1=企业，2=个人
     * @param verifyStatus 实名认证状态
     * @return true=已实名认证，false=未实名或参数不匹配
     */
    public static boolean isRealNameAuthenticated(Integer userType, Integer verifyStatus) {
        if (userType == null || verifyStatus == null) {
            return false;
        }
        return (userType == 1 && verifyStatus == 4)
                || (userType == 2 && verifyStatus == 2);
    }
}
