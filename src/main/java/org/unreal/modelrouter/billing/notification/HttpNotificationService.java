package org.unreal.modelrouter.billing.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.unreal.modelrouter.billing.EnterpriseLookupService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 通过 HTTP 直接调用算力平台 system 模块 SMS REST 端点发送低余额预警短信。
 * 算力平台 /rpc-api/** 端点全部 permitAll，无需鉴权。
 *
 * <p>调用路径：POST /rpc-api/system/sms/send/send-single-member
 * <p>对应接口：SmsSendApi#sendSingleSmsToMember(SmsSendSingleToUserReqDTO)
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "jairouter.billing.notification.enabled", havingValue = "true", matchIfMissing = false)
public class HttpNotificationService implements NotificationService {

    private static final String ENTERPRISE_TEMPLATE_CODE = "template_enterprise_balance_warning";
    private static final String PERSONAL_TEMPLATE_CODE = "template_personal_balance_warning";
    private static final String SMS_URI = "/rpc-api/system/sms/send/send-single-member";

    private final WebClient webClient;

    public HttpNotificationService(NotificationProperties properties) {
        this.webClient = WebClient.builder()
                .baseUrl(properties.getComputePlatformBaseUrl())
                .build();
        log.info("通知服务已初始化(直接调 SMS), 目标: {}", properties.getComputePlatformBaseUrl());
    }

    @Override
    public void sendLowBalanceAlert(EnterpriseLookupService.EnterpriseInfo enterprise,
                                    BigDecimal currentBalance) {
        // userId 是必传参数，用于算力平台自动查找用户手机号
        String userId = enterprise.getUserId();
        if (userId == null || userId.isBlank()) {
            log.warn("userId 为空，跳过短信通知, accountId={}, accountType={}",
                    enterprise.getCompanyId(), enterprise.getAccountType());
            return;
        }

        // userId 必须能解析为 Long（算力平台 SMS API 要求）
        final long userIdLong;
        try {
            userIdLong = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            log.warn("userId 非数字格式，跳过短信通知, userId={}, accountId={}, accountType={}",
                    userId, enterprise.getCompanyId(), enterprise.getAccountType());
            return;
        }

        String templateCode = resolveTemplateCode(enterprise.getAccountType());

        // 余额四舍五入到2位小数
        String balanceStr = currentBalance.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String thresholdStr = enterprise.getWarnThreshold() != null
                ? enterprise.getWarnThreshold().setScale(2, RoundingMode.HALF_UP).toPlainString()
                : "0.00";

        // 构建 SmsSendSingleToUserReqDTO 格式的请求体。
        // 个人模板仅需 currentBalance/threshold；企业模板额外需要 companyName（贵司名称），
        // 企业名称由 EnterpriseLookupService 按 accountId(=company_id) 从 sldd_system_user_company 解析，确保非空。
        Map<String, Object> templateParams = new HashMap<>();
        if (Integer.valueOf(1).equals(enterprise.getAccountType())) {
            templateParams.put("companyName", enterprise.getEnterpriseName());
        }
        templateParams.put("currentBalance", balanceStr);
        templateParams.put("threshold", thresholdStr);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userIdLong);
        requestBody.put("mobile", enterprise.getMobile() != null ? enterprise.getMobile() : "");
        requestBody.put("templateCode", templateCode);
        requestBody.put("templateParams", templateParams);

        log.info("发送低余额预警短信, accountName={}, accountType={}, balance={}, userId={}, mobile={}, templateCode={}",
                enterprise.getEnterpriseName(), enterprise.getAccountType(), balanceStr, userId, enterprise.getMobile(), templateCode);

        webClient.post()
                .uri(SMS_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        resp -> log.info("低余额预警短信已发送, accountName={}, accountType={}, balance={}, 响应={}",
                                enterprise.getEnterpriseName(), enterprise.getAccountType(), balanceStr, resp),
                        e -> log.error("低余额预警短信发送失败, accountName={}, accountType={}, balance={}: {}",
                                enterprise.getEnterpriseName(), enterprise.getAccountType(), balanceStr, e.getMessage(), e)
                );
    }

    private String resolveTemplateCode(Integer accountType) {
        if (Integer.valueOf(2).equals(accountType)) {
            return PERSONAL_TEMPLATE_CODE;
        }
        // 默认（企业或未知类型）使用企业模板，保持向后兼容
        return ENTERPRISE_TEMPLATE_CODE;
    }
}
