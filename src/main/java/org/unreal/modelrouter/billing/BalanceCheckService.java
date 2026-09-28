package org.unreal.modelrouter.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.auth.security.util.RealNameAuthUtils;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;

/**
 * 请求前余额校验服务。
 * 在 BaseAdapter.processRequest() 中调用，余额 < 0 时拒绝请求。
 * 余额 ≥ 0 时允许请求通过（扣减后可能变为负数，由下次请求前校验拦截）。
 * 企业用户和个人用户统一按 ai_account_balance 余额校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceCheckService {

    private final EnterpriseLookupService enterpriseLookupService;
    private final FreeQuotaService freeQuotaService;

    /**
     * 校验实名认证状态与余额。
     * - 本地 API Key / JWT / SYSTEM 用户（platformUser=false）：跳过
     * - 平台用户：必须严格成对实名认证（user_type=1/verify_status=4 或 user_type=2/verify_status=2），否则 403
     * - 文本类服务优先检查剩余免费额度，有则放行
     * - 企业用户（userType=1）：accountId=companyId，检查 ai_account_balance 余额
     * - 个人用户（userType=2）：accountId=userId，检查 ai_account_balance 余额
     * - 余额 < 0 返回 402
     */
    public Mono<Void> checkBalance(UserIdentity identity, String serviceType) {
        if (identity == null) {
            log.warn("余额校验跳过: identity 为 null");
            return Mono.empty();
        }

        // 非平台用户跳过余额校验
        if (!identity.platformUser()) {
            log.info("余额校验跳过: 非平台用户, user={}", identity.userAccount());
            return Mono.empty();
        }

        // 平台用户：实名认证兜底校验
        if (!RealNameAuthUtils.isRealNameAuthenticated(identity.userType(), identity.verifyStatus())) {
            log.warn("实名认证校验失败: user={}, userType={}, verifyStatus={}",
                    identity.userAccount(), identity.userType(), identity.verifyStatus());
            return Mono.error(new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "用户未实名认证，请先完成实名认证。"
            ));
        }

        // 平台用户且属于文本类服务：优先检查免费额度（在 boundedElastic 上执行 JPA，避免阻塞 Netty）
        return Mono.fromCallable(() -> {
            if (!freeQuotaService.isEnabledFor(identity.userId(), serviceType)) {
                return false;
            }
            return freeQuotaService.getRemainingQuota(identity.userId(), serviceType) > 0;
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(hasFreeQuota -> {
                    if (hasFreeQuota) {
                        log.info("免费额度校验通过, user={}, serviceType={}", identity.userId(), serviceType);
                        return Mono.empty();
                    }

                    // 确定账户标识
                    AccountRef accountRef = resolveAccountRef(identity);
                    if (accountRef == null) {
                        log.warn("账户标识解析失败, user={}, userType={}, companyId={}, userId={}",
                                identity.userAccount(), identity.userType(), identity.companyId(), identity.userId());
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "账户信息不完整，请联系管理员。"
                        ));
                    }

                    log.info("余额校验: user={}, accountId={}, accountType={}",
                            identity.userAccount(), accountRef.accountId(), accountRef.accountType());

                    return Mono.fromCallable(() -> enterpriseLookupService.lookupByAccount(
                            accountRef.accountId(), accountRef.accountType()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(optInfo -> {
                                EnterpriseLookupService.EnterpriseInfo info = optInfo.orElse(null);

                                // 账户不存在视为余额 0，按余额不足处理
                                BigDecimal balance = info != null ? info.getBalance() : BigDecimal.ZERO;

                                if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                                    log.info("账户余额不足, user={}, accountId={}, balance={}",
                                            identity.userAccount(), accountRef.accountId(), balance);
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.PAYMENT_REQUIRED,
                                            "账户余额不足，请充值。"
                                    ));
                                }

                                log.debug("余额校验通过, user={}, accountId={}, balance={}",
                                        identity.userAccount(), accountRef.accountId(), balance);
                                return Mono.empty();
                            });
                });
    }

    private AccountRef resolveAccountRef(UserIdentity identity) {
        Integer userType = identity.userType();
        if (Integer.valueOf(1).equals(userType)) {
            String companyId = identity.companyId();
            if (companyId == null || companyId.isBlank()) {
                return null;
            }
            return new AccountRef(companyId, 1);
        }
        if (Integer.valueOf(2).equals(userType)) {
            String userId = identity.userId();
            if (userId == null || userId.isBlank()) {
                return null;
            }
            return new AccountRef(userId, 2);
        }
        log.warn("未知用户类型, user={}, userType={}", identity.userAccount(), userType);
        return null;
    }

    private record AccountRef(String accountId, Integer accountType) {}
}
