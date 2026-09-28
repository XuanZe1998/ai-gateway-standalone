// 文件说明：FreeQuotaResult：负责计费与余额管理中的组件实现。
package org.unreal.modelrouter.billing.freequota;

public record FreeQuotaResult(
    boolean hitFreeQuota,
    long deductedTokens,
    long remainingBefore,
    long remainingAfter
) {
    public static FreeQuotaResult miss(long remainingBefore) {
        return new FreeQuotaResult(false, 0L, remainingBefore, remainingBefore);
    }

    public static FreeQuotaResult hit(long deducted, long remainingBefore, long remainingAfter) {
        return new FreeQuotaResult(true, deducted, remainingBefore, remainingAfter);
    }
}
