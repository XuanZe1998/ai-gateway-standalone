package org.unreal.modelrouter.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.auth.security.util.RealNameAuthUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;

/**
 * 请求前余额校验服务。
 * 在 BaseAdapter.processRequest() 中调用，余额 ≤ 0 时拒绝请求。
 * 余额 > 0 时允许请求通过（扣减后可能变为负数，由下次请求前校验拦截）。
 * 企业用户和个人用户统一按 ai_account_balance 余额校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceCheckService {

    private final EnterpriseLookupService enterpriseLookupService;
    private final ModelPricingService pricingService;
    private final UserUsagePolicyService usagePolicyService;
    private DepartmentBudgetService departmentBudgets;

    @org.springframework.beans.factory.annotation.Autowired
    public void setDepartmentBudgets(DepartmentBudgetService service) { this.departmentBudgets=service; }

    public Mono<UserIdentity> prepare(UserIdentity identity,String service,String model,String channel,Object request) {
        return Mono.fromCallable(()->departmentBudgets==null?identity:departmentBudgets.admit(identity,service,model,channel,request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(prepared->checkBalance(prepared,service).thenReturn(prepared)
                        .onErrorResume(error->release(prepared).then(Mono.error(error))));
    }
    public Mono<Void> release(UserIdentity identity) {
        return Mono.fromRunnable(()->{if(departmentBudgets!=null&&identity!=null)departmentBudgets.release(identity.usageSnapshot());})
                .subscribeOn(Schedulers.boundedElastic()).then();
    }
    public Mono<Void> uncertain(UserIdentity identity,String reason) {
        return Mono.fromRunnable(()->{if(departmentBudgets!=null&&identity!=null)departmentBudgets.unconfirmed(identity.usageSnapshot(),null,reason);})
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * 校验调用资格与余额。
     * - 本地 API Key / JWT / SYSTEM 用户（platformUser=false）：跳过
     * - 校园用户：统一认证通过即可；其他平台用户仍须实名认证，否则 403
     * - 管理员授权的免费使用豁免余额门槛；月度免费 Token 不豁免
     * - 企业用户（userType=1）：accountId=companyId，检查 ai_account_balance 余额
     * - 个人用户（userType=2）：accountId=userId，检查 ai_account_balance 余额
     * - 余额 ≤ 0 返回 402
     */
    public Mono<Void> checkBalance(UserIdentity identity, String serviceType) {
        if (identity == null) {
            log.warn("余额校验跳过: identity 为 null");
            return Mono.empty();
        }

        // Reject saved sessions issued by the retired CAS balance-bypass switch.
        if (!identity.platformUser() && identity.systemUserId() != null) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "校园会话已过期，请重新登录以加载计费身份。"));
        }
        // 非平台内部用户跳过余额校验
        if (!identity.platformUser()) {
            log.info("余额校验跳过: 非平台用户, user={}", identity.userAccount());
            return Mono.empty();
        }

        // 校园认证赋予调用资格；所有平台用户仍经过下面的余额校验。
        if (!identity.campusAuthenticated()
                && !RealNameAuthUtils.isRealNameAuthenticated(identity.userType(), identity.verifyStatus())) {
            log.warn("实名认证校验失败: user={}, userType={}, verifyStatus={}",
                    identity.userAccount(), identity.userType(), identity.verifyStatus());
            return Mono.error(new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "用户未实名认证，请先完成实名认证。"
            ));
        }

        if(identity.usageSnapshot()!=null&&identity.usageSnapshot().departmentFunded())return Mono.empty();
        return Mono.fromCallable(() -> identity.usageSnapshot()!=null?identity.usageSnapshot().freeUsage():usagePolicyService.isFreeUsage(identity.userId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(free -> free ? Mono.empty() : checkPaidBalance(identity));
    }

    private Mono<Void> checkPaidBalance(UserIdentity identity) {
        AccountRef accountRef;
        if(identity.usageSnapshot()!=null) {
            var snapshot=identity.usageSnapshot();
            accountRef=snapshot.accountId()==null||snapshot.accountId().isBlank()||snapshot.accountType()==null?null:
                    new AccountRef(snapshot.accountId(),snapshot.accountType());
        } else accountRef=resolveAccountRef(identity);
        if (accountRef == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "账户信息不完整，请联系管理员。"));
        }
        // 免费 Token 只影响本次计费，不豁免请求前的可用余额门槛。
        return Mono.fromCallable(() -> enterpriseLookupService.lookupByAccount(
                        accountRef.accountId(), accountRef.accountType()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(info -> {
                    BigDecimal balance = info.map(EnterpriseLookupService.EnterpriseInfo::getBalance)
                            .orElse(BigDecimal.ZERO);
                    if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                        return Mono.error(new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                                "账户余额不足，可用余额必须大于 0，请联系管理员分配额度或充值。"));
                    }
                    return Mono.empty();
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

    /**
     * 模型计费规则校验：未配置可用计费的模型拒绝调用（防止 0 元计费资损）。
     * 调用方在选定模型实例后调用。
     */
    public void requirePricingConfigured(String modelName, String channelId) {
        if (!pricingService.hasUsablePricing(modelName, channelId)) {
            log.warn("模型未配置可用计费规则，拒绝调用: model={}, channelId={}", modelName, channelId);
            throw new ResponseStatusException(
                    HttpStatus.PAYMENT_REQUIRED,
                    "该模型未配置计费规则，暂不可调用。"
            );
        }
    }

}
