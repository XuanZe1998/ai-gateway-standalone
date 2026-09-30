// 文件说明：BillingService：负责计费与余额管理中的组件实现。
package org.unreal.modelrouter.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.rule.PricingRuleEngine;
import org.unreal.modelrouter.billing.rule.UsageContext;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingRecordRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class BillingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BillingService.class);
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final ModelPricingService pricingService;
    private final BillingRecordRepository billingRepository;
    private final BalanceDeductionService balanceDeductionService;
    private final EnterpriseLookupService enterpriseLookupService;
    private final org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository systemUserRepository;
    private final DiscountCalculationService discountCalculationService;
    private final PricingRuleEngine ruleEngine;
    private final ObjectMapper objectMapper;

    public BillingService(ModelPricingService pricingService,
                          BillingRecordRepository billingRepository,
                          BalanceDeductionService balanceDeductionService,
                          EnterpriseLookupService enterpriseLookupService,
                          org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository systemUserRepository,
                          DiscountCalculationService discountCalculationService,
                          PricingRuleEngine ruleEngine,
                          ObjectMapper objectMapper) {
        this.pricingService = pricingService;
        this.billingRepository = billingRepository;
        this.balanceDeductionService = balanceDeductionService;
        this.enterpriseLookupService = enterpriseLookupService;
        this.systemUserRepository = systemUserRepository;
        this.discountCalculationService = discountCalculationService;
        this.ruleEngine = ruleEngine;
        this.objectMapper = objectMapper;
    }

    @Async("billingTaskExecutor")
    public void recordBilling(BillingContext ctx) {
        try {
            doRecordBilling(ctx);
        } catch (Exception e) {
            LOGGER.error("Failed to record billing for model={}, user={}: {}",
                    ctx.getModelName(), ctx.getUserId(), e.getMessage(), e);
        }
    }

    /**
     * 同步计费落账 + 余额扣减（视频生成查询链路 succeeded 延迟结算专用）。
     *
     * 与 {@link #recordBilling} 共享同一套 buildRecord/扣减主体，但不走
     * billingTaskExecutor 异步线程池：调用方（视频任务结算器）需要拿到
     * 计费记录主键回写 ai_video_task.billing_record_id 做对账关联，
     * 且自身已在 boundedElastic 上执行。
     *
     * @return 计费记录主键（ai_billing_record.id）；失败返回 null，
     *         由调用方 ERROR 日志留待人工对账
     */
    public Long recordBillingSync(BillingContext ctx) {
        try {
            BillingRecordEntity record = doRecordBilling(ctx);
            return record != null ? record.getId() : null;
        } catch (Exception e) {
            LOGGER.error("Failed to record billing (sync) for model={}, user={}: {}",
                    ctx.getModelName(), ctx.getUserId(), e.getMessage(), e);
            return null;
        }
    }

    /** recordBilling / recordBillingSync 共享主体：落账 + 扣减 + 预警 */
    private BillingRecordEntity doRecordBilling(BillingContext ctx) {
        BillingRecordEntity record = buildRecord(ctx);
        billingRepository.save(record);
        LOGGER.info("计费记录已保存: model={}, user={}, enterpriseId={}, promptTokens={}, completionTokens={}, cost={}",
                ctx.getModelName(), ctx.getUserId(), ctx.getEnterpriseId(),
                ctx.getPromptTokens(), ctx.getCompletionTokens(), record.getTotalCost());

        // 计费成功后扣减企业余额 + 预警检查
        // 免费额度命中或费用为零/负时跳过余额扣减
        if (Boolean.TRUE.equals(ctx.getIsFreeQuota()) ||
            record.getTotalCost().compareTo(BigDecimal.ZERO) <= 0) {
            LOGGER.info("跳过余额扣减: isFreeQuota={}, cost={}", ctx.getIsFreeQuota(), record.getTotalCost());
            return record;
        }
        String accountId = ctx.getAccountId();
        Integer accountType = ctx.getAccountType();
        if (accountId == null || accountId.isBlank() || accountType == null) {
            LOGGER.warn("跳过余额扣减: 账户标识为空, user={}, enterpriseId={}",
                    ctx.getUserId(), ctx.getEnterpriseId());
            return record;
        }
        enterpriseLookupService.lookupByAccount(accountId, accountType)
                .ifPresentOrElse(
                    info -> {
                        try {
                            // 设置请求用户 ID 和手机号，用于后续短信通知
                            info.setUserId(ctx.getUserId());
                            systemUserRepository.findByUserId(ctx.getUserId())
                                    .ifPresent(u -> info.setMobile(u.getMobile()));
                            balanceDeductionService.deductAndAlert(
                                    accountId, accountType, record.getTotalCost(), info);
                        } catch (Exception e) {
                            LOGGER.error("余额扣减失败, accountId={}, accountType={}: {}",
                                    accountId, accountType, e.getMessage(), e);
                        }
                    },
                    () -> LOGGER.warn("跳过余额扣减: 账户查询为空, accountId={}, accountType={}",
                            accountId, accountType)
                );
        return record;
    }

    private BillingRecordEntity buildRecord(BillingContext ctx) {
        ModelPricingService.ModelPricing pricing =
                pricingService.getPrice(ctx.getModelName(), ctx.getChannelId());
        if (pricing == null) {
            // 按需同步该模型的定价
            pricingService.syncPricingFromPlatform(ctx.getModelName());
            pricing = pricingService.getPrice(ctx.getModelName(), ctx.getChannelId());
        }

        // 视频模型计费分支：按价格模式 + 计费单位 + 分辨率规则计价（不走 token 六维逻辑）；
        // 仅视频结算链路（VideoTaskSettler）会设置 videoUsage，其余链路 null 走原六维逻辑零影响。
        // 资损兜底：videoUsage 非 null（视频结算链路）且无规则快照、实时定价未命中/未配置视频规则 → 拒计费，
        // 绝不回落六维逻辑按 0 元落账（0 元落账会回写 billing_record_id，对账口径无法发现漏计费）
        if (ctx.getVideoUsage() != null) {
            // 快照优先：任务创建时已锁定提交时刻计费规则的（VideoTaskSettler 传入），结算不依赖实时定价
            // （平台侧后续改价/删规则不影响已提交任务账单）；快照缺失时才要求实时定价命中
            if (ctx.getVideoPricingSnapshot() == null && (pricing == null || pricing.getPriceMode() == null)) {
                LOGGER.error("视频计费定价未命中，拒计费: model={}, channelId={}, pricingConfigured={}",
                        ctx.getModelName(), ctx.getChannelId(), pricing != null);
                throw new IllegalStateException("视频计费定价未命中: model=" + ctx.getModelName());
            }
            return buildVideoRecord(ctx, pricing);
        }

        org.unreal.modelrouter.billing.usage.TokenUsage usage = ctx.getTokenUsage();
        long promptTokens;
        long completionTokens;
        long totalTokens;
        // 6 维归一化用量（无 tokenUsage 时从旧字段兜底：视为普通输入/输出，无缓存/思考）
        long normalInput;
        long cacheHit;
        long cacheCreateExplicit;
        long cacheHitExplicit;
        long normalOutput;
        long thinking;
        if (usage != null) {
            normalInput = usage.normalInput();
            cacheHit = usage.cacheHit();
            cacheCreateExplicit = usage.cacheCreateExplicit();
            cacheHitExplicit = usage.cacheHitExplicit();
            normalOutput = usage.normalOutput();
            thinking = usage.thinking();
            promptTokens = usage.legacyPromptTokens();
            completionTokens = usage.legacyCompletionTokens();
            totalTokens = usage.rawTotalTokens() > 0 ? usage.rawTotalTokens() : promptTokens + completionTokens;
        } else {
            promptTokens = ctx.getPromptTokens() != null ? ctx.getPromptTokens() : 0L;
            completionTokens = ctx.getCompletionTokens() != null ? ctx.getCompletionTokens() : 0L;
            totalTokens = ctx.getTotalTokens() != null ? ctx.getTotalTokens() : 0L;
            normalInput = promptTokens;
            cacheHit = 0;
            cacheCreateExplicit = 0;
            cacheHitExplicit = 0;
            normalOutput = completionTokens;
            thinking = 0;
        }

        // ===== 计费单价（整体 or 阶梯；阶梯按上下文=输入 token 总量匹配档位）=====
        // 思考 token 计费模式先判定（1=并入输出 2=单独计费 3=不计费），影响阶梯档位的思考价取值
        int thinkingMode = pricing != null && pricing.getThinkingBillingMode() != null
                ? pricing.getThinkingBillingMode() : 1;
        BigDecimal inputPrice = BigDecimal.ZERO;
        BigDecimal outputPrice = BigDecimal.ZERO;
        BigDecimal cacheHitInputPrice = BigDecimal.ZERO;
        BigDecimal cacheCreateInputPrice = BigDecimal.ZERO;
        BigDecimal cacheHitExplicitInputPrice = BigDecimal.ZERO;
        BigDecimal thinkingPrice = pricing != null ? pricing.getThinkingPrice() : BigDecimal.ZERO;
        if (pricing != null) {
            if (Integer.valueOf(3).equals(pricing.getBillingMode())) {
                // ===== 规则计费（billingMode=3，数据驱动条件树）：价格单位 元/M token → 元/token =====
                UsageContext usageCtx = new UsageContext(
                        promptTokens, completionTokens,
                        false, false, promptTokens,
                        ctx.getStartedAt() != null ? ctx.getStartedAt() : LocalDateTime.now(),
                        ctx.getServiceType(), ctx.getVendor(),
                        cacheHit > 0, thinkingMode != 1);
                PricingRuleEngine.MatchedPricing matched = ruleEngine.match(pricing.getRules(), usageCtx);
                if (matched == null) {
                    // 资损兜底：未命中任何规则拒计费，绝不按 0 元落账（与视频拒计费同策略）
                    throw new IllegalStateException("规则计费未命中任何规则: model=" + ctx.getModelName()
                            + ", ruleCount=" + pricing.getRules().size());
                }
                inputPrice = perToken(matched.prices().getOrDefault("normalPrice", BigDecimal.ZERO));
                outputPrice = perToken(matched.prices().getOrDefault("output", BigDecimal.ZERO));
                cacheHitInputPrice = perToken(matched.prices().getOrDefault("cacheHit", BigDecimal.ZERO));
                cacheCreateInputPrice = perToken(matched.prices().getOrDefault("cacheCreate", BigDecimal.ZERO));
                cacheHitExplicitInputPrice = perToken(matched.prices().getOrDefault("cacheHitExplicit", BigDecimal.ZERO));
                // 思考单独计费（thinkingMode=2）时取命中规则的价格，其余模式置 0 由下方思考合并逻辑处理
                if (thinkingMode == 2) {
                    thinkingPrice = perToken(matched.prices().getOrDefault("thinking", BigDecimal.ZERO));
                }
            } else {
                inputPrice = pricing.getInputPrice();
                outputPrice = pricing.getOutputPrice();
                cacheHitInputPrice = pricing.getCacheHitInputPrice();
                cacheCreateInputPrice = pricing.getCacheCreateInputPrice();
                cacheHitExplicitInputPrice = pricing.getCacheHitExplicitInputPrice();

                if (Integer.valueOf(2).equals(pricing.getBillingMode()) && !pricing.getTiers().isEmpty()) {
                    ModelPricingService.ModelPricing.PriceTier tier = matchTier(pricing.getTiers(), promptTokens);
                    if (tier != null) {
                        inputPrice = tier.inputPrice();
                        outputPrice = tier.outputPrice();
                        cacheHitInputPrice = tier.cacheHitInputPrice();
                        cacheCreateInputPrice = tier.cacheCreateInputPrice();
                        cacheHitExplicitInputPrice = tier.cacheHitExplicitInputPrice();
                        // thinkingBillingMode=2 且阶梯计费时，思考价取命中档位的 thinkingPrice
                        if (thinkingMode == 2) {
                            thinkingPrice = tier.thinkingPrice();
                        }
                    }
                }
            }
        }

        // ===== enable 标志位：未启用的维度 token 归并到已启用的同类维度（防资损）=====
        // 原则：上游返回了 token 就必须计费，未启用的维度归并到已启用的维度，不直接置 0
        if (pricing != null) {
            // 输入侧：未启用的缓存类维度归并到 normalInput（按普通输入价计费）
            if (!pricing.isEnableCacheHitInput()) {
                normalInput += cacheHit;
                cacheHit = 0;
            }
            if (!pricing.isEnableCacheCreateInput()) {
                normalInput += cacheCreateExplicit;
                cacheCreateExplicit = 0;
            }
            if (!pricing.isEnableCacheHitExplicitInput()) {
                normalInput += cacheHitExplicit;
                cacheHitExplicit = 0;
            }
            // 输入总开关关闭：所有输入维度置 0（此时 normalInput 已包含归并值）
            if (!pricing.isEnableInputToken()) {
                normalInput = 0;
                cacheHit = 0;
                cacheCreateExplicit = 0;
                cacheHitExplicit = 0;
            }
            // 输出侧：输出总开关关闭时 normalOutput + thinking 一起置 0
            // thinkingMode=1 已在下方把 thinking 并入 normalOutput，此处只需处理 enableOutputToken=false 的情况
            if (!pricing.isEnableOutputToken()) {
                normalOutput = 0;
                thinking = 0;
            }
        }

        // ===== 思考 token 计费模式：1=并入输出 2=单独计费 3=不计费 =====
        if (thinkingMode == 1) {
            // 并入输出按 outputPrice 计费
            normalOutput += thinking;
            thinking = 0;
        } else if (thinkingMode == 3) {
            thinking = 0;
        }

        // 多级折扣计算：模型折扣 × 用户折扣 × 企业补贴折扣
        UserIdentity identity = new UserIdentity(
                ctx.getUserId(), ctx.getUserAccount(),
                ctx.getApiKeyId(), ctx.getApiKeyName(),
                ctx.getEnterpriseId(), ctx.getEnterpriseName(), ctx.getCompanyId(),
                Boolean.TRUE.equals(ctx.getPlatformUser()),
                ctx.getUserType(), ctx.getSystemUserId(), null);
        DiscountBreakdown discounts = discountCalculationService.calculate(
                identity, ctx.getModelName(), ctx.getChannelId());

        // 支付比例 = finalDiscountRate（折后应付比例：8 折=0.8、无折扣=1.0 即收全额）；
        // ⚠ 不能写成 1−finalRate——那会把「无折扣(1.0)」误算成全免（历史资损：qwen3.8-max 0.22 元实收 0 元）
        BigDecimal payRatio = discounts.finalDiscountRate();

        // ===== 先算各维度账单：token × 单价 × 支付比例，向上取整保留2位小数 =====
        BigDecimal inputBill = new BigDecimal(normalInput).multiply(inputPrice).multiply(payRatio).setScale(2, RoundingMode.CEILING);
        BigDecimal cacheHitBill = new BigDecimal(cacheHit).multiply(cacheHitInputPrice).multiply(payRatio).setScale(2, RoundingMode.CEILING);
        BigDecimal cacheCreateBill = new BigDecimal(cacheCreateExplicit).multiply(cacheCreateInputPrice).multiply(payRatio).setScale(2, RoundingMode.CEILING);
        BigDecimal cacheHitExplicitBill = new BigDecimal(cacheHitExplicit).multiply(cacheHitExplicitInputPrice).multiply(payRatio).setScale(2, RoundingMode.CEILING);
        BigDecimal outputBill = new BigDecimal(normalOutput).multiply(outputPrice).multiply(payRatio).setScale(2, RoundingMode.CEILING);
        BigDecimal thinkingBill = new BigDecimal(thinking).multiply(thinkingPrice).multiply(payRatio).setScale(2, RoundingMode.CEILING);
        // 总账单 = 各维度账单之和
        BigDecimal totalCost = inputBill.add(cacheHitBill).add(cacheCreateBill)
                .add(cacheHitExplicitBill).add(outputBill).add(thinkingBill);

        // ===== 再算各维度费用：token × 单价，向上取整保留2位小数 =====
        BigDecimal inputCost = new BigDecimal(normalInput).multiply(inputPrice).setScale(2, RoundingMode.CEILING);
        BigDecimal cacheHitCost = new BigDecimal(cacheHit).multiply(cacheHitInputPrice).setScale(2, RoundingMode.CEILING);
        BigDecimal cacheCreateCost = new BigDecimal(cacheCreateExplicit).multiply(cacheCreateInputPrice).setScale(2, RoundingMode.CEILING);
        BigDecimal cacheHitExplicitCost = new BigDecimal(cacheHitExplicit).multiply(cacheHitExplicitInputPrice).setScale(2, RoundingMode.CEILING);
        BigDecimal outputCost = new BigDecimal(normalOutput).multiply(outputPrice).setScale(2, RoundingMode.CEILING);
        BigDecimal thinkingCost = new BigDecimal(thinking).multiply(thinkingPrice).setScale(2, RoundingMode.CEILING);
        // 总费用 = 各维度费用之和
        BigDecimal originalCost = inputCost.add(cacheHitCost).add(cacheCreateCost)
                .add(cacheHitExplicitCost).add(outputCost).add(thinkingCost);

        // 总折扣 = 总费用 - 总账单
        BigDecimal discountAmount = originalCost.subtract(totalCost);

        if (Boolean.TRUE.equals(ctx.getIsFreeQuota())) {
            originalCost = BigDecimal.ZERO;
            discountAmount = BigDecimal.ZERO;
            totalCost = BigDecimal.ZERO;
            inputBill = BigDecimal.ZERO;
            cacheHitBill = BigDecimal.ZERO;
            cacheCreateBill = BigDecimal.ZERO;
            cacheHitExplicitBill = BigDecimal.ZERO;
            outputBill = BigDecimal.ZERO;
            thinkingBill = BigDecimal.ZERO;
        }

        return BillingRecordEntity.builder()
                .userId(ctx.getUserId())
                .userAccount(ctx.getUserAccount())
                .apiKeyId(ctx.getApiKeyId())
                .apiKeyName(ctx.getApiKeyName())
                .modelName(ctx.getModelName())
                .modelId(pricing != null ? pricing.getModelId() : null)
                .serviceType(ctx.getServiceType())
                .channelId(ctx.getChannelId())
                .channelName(ctx.getChannelName())
                .provider(ctx.getProvider())
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                // 6 维计费 token（记录 enable 归并 + 思考合并后的实际计费口径，与分项费用一致）
                .normalInputTokens(normalInput)
                .cacheHitTokens(cacheHit)
                .cacheCreateExplicitTokens(cacheCreateExplicit)
                .cacheHitExplicitTokens(cacheHitExplicit)
                .normalOutputTokens(normalOutput)
                .thinkingTokens(thinking)
                // 价格快照（阶梯计费时为命中档位的价格）
                .inputUnitPrice(inputPrice)
                .outputUnitPrice(outputPrice)
                .cacheHitInputUnitPrice(cacheHitInputPrice)
                .cacheCreateInputUnitPrice(cacheCreateInputPrice)
                .cacheHitExplicitInputUnitPrice(cacheHitExplicitInputPrice)
                .thinkingUnitPrice(thinkingPrice)
                // 计费元数据快照
                .billingMode(pricing != null ? pricing.getBillingMode() : null)
                .thinkingBillingMode(thinkingMode)
                .vendor(ctx.getVendor())
                .responseSnapshot(ctx.getResponseSnapshot())
                // 分项费用（对账用，折扣前）
                .inputCost(inputCost)
                .cacheHitCost(cacheHitCost)
                .cacheCreateCost(cacheCreateCost)
                .cacheHitExplicitCost(cacheHitExplicitCost)
                .outputCost(outputCost)
                .thinkingCost(thinkingCost)
                // 分项账单（折扣后，用于导出）
                .inputBill(inputBill)
                .cacheHitBill(cacheHitBill)
                .cacheCreateBill(cacheCreateBill)
                .cacheHitExplicitBill(cacheHitExplicitBill)
                .outputBill(outputBill)
                .thinkingBill(thinkingBill)
                .discountRate(discounts.modelDiscountRate())
                .userDiscountRate(discounts.userDiscountRate())
                .enterpriseDiscountRate(discounts.enterpriseDiscountRate())
                .finalDiscountRate(discounts.finalDiscountRate())
                .userType(ctx.getUserType())
                .originalCost(originalCost)
                .discountAmount(discountAmount)
                .totalCost(totalCost)
                .isFreeQuota(ctx.getIsFreeQuota())
                .freeQuotaConsumed(ctx.getFreeQuotaConsumed())
                .isSuccess(ctx.getIsSuccess())
                .errorCode(ctx.getErrorCode())
                .errorMessage(ctx.getErrorMessage())
                .responseTimeMs(ctx.getResponseTimeMs())
                .traceId(ctx.getTraceId())
                .clientIp(ctx.getClientIp())
                .startedAt(ctx.getStartedAt() != null ? ctx.getStartedAt() : LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .createdBy(ctx.getUserId())
                .updatedBy(ctx.getUserId())
                .build();
    }

    /** 阶梯匹配：按上下文（输入 token 总量）落在哪个档位区间 */
    private ModelPricingService.ModelPricing.PriceTier matchTier(
            java.util.List<ModelPricingService.ModelPricing.PriceTier> tiers, long contextTokens) {
        ModelPricingService.ModelPricing.PriceTier matched = null;
        for (ModelPricingService.ModelPricing.PriceTier tier : tiers) {
            long lower = tier.lowerLimit() != null ? tier.lowerLimit().longValue() : 0L;
            boolean upperOk = tier.unlimited()
                    || tier.upperLimit() == null
                    || contextTokens < tier.upperLimit().longValue();
            if (contextTokens >= lower && upperOk) {
                matched = tier; // 档位按 tierOrder 升序，取最后一个命中的
            }
        }
        return matched;
    }

    /** 规则计费单价换算：元/M token → 元/token（规则 price_json 为管理员填写口径） */
    private BigDecimal perToken(BigDecimal pricePerMillion) {
        return pricePerMillion != null
                ? pricePerMillion.divide(MILLION, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    // ==================== 视频模型计费（价格模式 + 计费单位 + 分辨率规则） ====================

    /**
     * 视频模型计费落账（serviceType=vidGen，不再按 token 字段计价）。
     *
     * <p>规则匹配：
     * <ul>
     *   <li>统一价格（priceMode=1）：按输出分辨率匹配启用规则行，不区分视频输入；</li>
     *   <li>按条件定价（priceMode=2）：按「输出分辨率 × 本次调用是否有视频输入」匹配启用规则行。</li>
     * </ul>
     * 计量：billingUnit=second 按视频时长（秒）× 元/秒；billingUnit=token 按 token × 元/token。
     *
     * <p>资损兜底：无匹配启用行或用量缺失（seconds<=0 / tokens<=0）时抛 {@link IllegalStateException}
     * 拒计费——不落账单、不扣余额，由 {@link #recordBillingSync} 返回 null，
     * 留档表 billing_record_id 置 NULL 走人工对账。
     *
     * <p>快照：usage_detail JSONB 回写实际消耗明细（billingUnit + items 分项），
     * cost/discountAmount/amount 与账单 original_cost/discount_amount/total_cost 一致。
     */
    private BillingRecordEntity buildVideoRecord(BillingContext ctx, ModelPricingService.ModelPricing pricing) {
        VideoUsage vu = ctx.getVideoUsage();
        // 计费口径来源：优先任务创建时快照的规则（提交时刻锁价），快照缺失回退实时定价——
        // 平台侧后续改价/删规则不影响已提交任务的账单生成（V7 billing_rule_snapshot）
        ModelPricingService.VideoPricingSnapshot snap = ctx.getVideoPricingSnapshot();
        int priceMode;
        String billingUnit;
        Long modelId;
        List<ModelPricingService.ModelPricing.VideoPriceRule> rules;
        if (snap != null) {
            priceMode = snap.priceMode() != null ? snap.priceMode() : 1;
            billingUnit = snap.billingUnit() != null && !snap.billingUnit().isBlank() ? snap.billingUnit() : "second";
            modelId = snap.modelId();
            rules = snap.rules();
        } else {
            priceMode = pricing.getPriceMode() != null ? pricing.getPriceMode() : 1;
            billingUnit = pricing.getBillingUnit() != null && !pricing.getBillingUnit().isBlank() ? pricing.getBillingUnit() : "second";
            modelId = pricing.getModelId();
            rules = pricing.getVideoPriceRules();
        }
        String resolution = vu.resolution();

        // 规则匹配：按输出分辨率精确匹配启用行；条件定价追加视频输入有无条件（统一价格不区分）。
        // 条件定价下规则行 has_video_input 必须非 NULL（null 行视为平台配置错误，不参与匹配，
        // 防「null 任务」命中 null 规则行而 true/false 任务反而被拒计费的不一致行为）
        ModelPricingService.ModelPricing.VideoPriceRule matched = null;
        for (ModelPricingService.ModelPricing.VideoPriceRule rule : rules) {
            if (rule.outputResolution() != null && rule.outputResolution().equals(resolution)
                    && (priceMode != 2
                        || (rule.hasVideoInput() != null && Objects.equals(rule.hasVideoInput(), vu.hasVideoInput())))) {
                matched = rule;
                break;
            }
        }
        if (matched == null) {
            LOGGER.error("视频计费规则匹配失败，拒计费（配置缺失或未启用）: model={}, channelId={}, priceMode={}, "
                            + "resolution={}, hasVideoInput={}, billingUnit={}",
                    ctx.getModelName(), ctx.getChannelId(), priceMode,
                    resolution, vu.hasVideoInput(), billingUnit);
            throw new IllegalStateException("视频计费规则匹配失败: model=" + ctx.getModelName()
                    + ", resolution=" + resolution + ", hasVideoInput=" + vu.hasVideoInput());
        }
        // 计费侧兜底：命中规则行价格为空或非正数（平台配置疏忽）时拒计费，
        // 防止按 0 元落账且回写 billing_record_id 导致对账口径无法发现漏计费
        if (matched.price() == null || matched.price().compareTo(BigDecimal.ZERO) <= 0) {
            LOGGER.error("视频计费规则价格无效，拒计费: model={}, resolution={}, hasVideoInput={}, price={}",
                    ctx.getModelName(), resolution, vu.hasVideoInput(), matched.price());
            throw new IllegalStateException("视频计费规则价格无效: model=" + ctx.getModelName()
                    + ", resolution=" + resolution);
        }

        // 计量：second 按秒 × 元/秒（frames 折算场景为小数秒）；token 按 token × 元/token
        BigDecimal cost;
        if ("token".equals(billingUnit)) {
            long tokens = vu.tokens() != null ? vu.tokens() : 0L;
            if (tokens <= 0) {
                LOGGER.error("视频计费用量缺失，拒计费: model={}, taskTokens<=0, resolution={}",
                        ctx.getModelName(), resolution);
                throw new IllegalStateException("视频计费 token 用量缺失: model=" + ctx.getModelName());
            }
            cost = BigDecimal.valueOf(tokens).multiply(matched.price());
        } else {
            BigDecimal seconds = vu.seconds();
            if (seconds == null || seconds.compareTo(BigDecimal.ZERO) <= 0) {
                LOGGER.error("视频计费用量缺失，拒计费: model={}, seconds={}, resolution={}",
                        ctx.getModelName(), seconds, resolution);
                throw new IllegalStateException("视频计费时长用量缺失: model=" + ctx.getModelName()
                        + ", seconds=" + seconds);
            }
            cost = seconds.multiply(matched.price());
        }

        // 多级折扣：模型折扣 × 用户折扣 × 企业补贴折扣
        UserIdentity identity = new UserIdentity(
                ctx.getUserId(), ctx.getUserAccount(),
                ctx.getApiKeyId(), ctx.getApiKeyName(),
                ctx.getEnterpriseId(), ctx.getEnterpriseName(), ctx.getCompanyId(),
                Boolean.TRUE.equals(ctx.getPlatformUser()),
                ctx.getUserType(), ctx.getSystemUserId(), null);
        DiscountBreakdown discounts = discountCalculationService.calculate(
                identity, ctx.getModelName(), ctx.getChannelId());

        BigDecimal originalCost = cost.setScale(2, RoundingMode.CEILING);
        BigDecimal discountAmount = cost
                .multiply(BigDecimal.ONE.subtract(discounts.finalDiscountRate()))
                .setScale(2, RoundingMode.CEILING);
        BigDecimal totalCost = originalCost.subtract(discountAmount);

        if (Boolean.TRUE.equals(ctx.getIsFreeQuota())) {
            originalCost = BigDecimal.ZERO;
            discountAmount = BigDecimal.ZERO;
            totalCost = BigDecimal.ZERO;
        }

        long completionTokens = vu.tokens() != null ? vu.tokens() : 0L;
        // second 计费单位（元/秒）时 token 字段与计费无关，置 NULL 不落库：
        // token 用量统计（SUM(total_tokens) 等聚合）天然忽略 NULL，按秒计费的视频账单被自动排除，
        // 避免上游 completion_tokens 残留值被误计入 token 消耗；与 6 维 token 字段（视频分支恒 NULL）口径一致。
        // token 计费单位（元/token）时 completion/total 即计费依据，必须落库。
        boolean tokenBilled = "token".equals(billingUnit);
        Long billedCompletionTokens = tokenBilled ? completionTokens : null;

        return BillingRecordEntity.builder()
                .userId(ctx.getUserId())
                .userAccount(ctx.getUserAccount())
                .apiKeyId(ctx.getApiKeyId())
                .apiKeyName(ctx.getApiKeyName())
                .modelName(ctx.getModelName())
                .modelId(modelId)
                .serviceType(ctx.getServiceType())
                .channelId(ctx.getChannelId())
                .channelName(ctx.getChannelName())
                .provider(ctx.getProvider())
                // 上游原始 token：token 计费单位时落库（计费依据）；second 计费单位置 NULL——
                // 统计 SUM 聚合忽略 NULL 天然排除按秒计费记录（对账留痕走 usage_detail/response_snapshot）
                .promptTokens(tokenBilled ? 0L : null)
                .completionTokens(billedCompletionTokens)
                .totalTokens(billedCompletionTokens)
                // 分项费用：视频唯一分项记 output_cost（折扣前），保持 original_cost = 分项和口径
                .outputCost(originalCost)
                // 计费元数据快照：vendor 留痕；billingMode（token 阶梯口径）不适用于视频，置 null
                .vendor(ctx.getVendor())
                .responseSnapshot(ctx.getResponseSnapshot())
                .discountRate(discounts.modelDiscountRate())
                .userDiscountRate(discounts.userDiscountRate())
                .enterpriseDiscountRate(discounts.enterpriseDiscountRate())
                .finalDiscountRate(discounts.finalDiscountRate())
                .userType(ctx.getUserType())
                .originalCost(originalCost)
                .discountAmount(discountAmount)
                .totalCost(totalCost)
                .isFreeQuota(ctx.getIsFreeQuota())
                .freeQuotaConsumed(ctx.getFreeQuotaConsumed())
                .isSuccess(ctx.getIsSuccess())
                .errorCode(ctx.getErrorCode())
                .errorMessage(ctx.getErrorMessage())
                .responseTimeMs(ctx.getResponseTimeMs())
                .traceId(ctx.getTraceId())
                .clientIp(ctx.getClientIp())
                .startedAt(ctx.getStartedAt() != null ? ctx.getStartedAt() : LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .createdBy(ctx.getUserId())
                .updatedBy(ctx.getUserId())
                .usageDetail(buildVideoUsageDetail(billingUnit, vu, matched,
                        originalCost, discountAmount, totalCost))
                .build();
    }

    /**
     * 构造 usage_detail JSONB 快照：billingUnit + items 分项明细。
     * second 单位时 seconds 为实际计量秒数（frames 折算可为小数）、tokens 为 null；
     * token 单位时 seconds 为 null、tokens 为 token 数。
     */
    private String buildVideoUsageDetail(String billingUnit, VideoUsage vu,
                                         ModelPricingService.ModelPricing.VideoPriceRule matched,
                                         BigDecimal cost, BigDecimal discountAmount, BigDecimal amount) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("billingUnit", billingUnit);
        ArrayNode items = root.putArray("items");
        ObjectNode item = items.addObject();
        item.put("resolution", matched.outputResolution());
        item.put("hasVideoInput", vu.hasVideoInput());
        if ("token".equals(billingUnit)) {
            item.putNull("seconds");
            item.put("tokens", vu.tokens() != null ? vu.tokens() : 0L);
        } else {
            item.put("seconds", vu.seconds());
            item.putNull("tokens");
        }
        item.put("cost", cost);
        item.put("discountAmount", discountAmount);
        item.put("amount", amount);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            LOGGER.error("视频计费 usage_detail 序列化失败，按 null 落库: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 视频计费用量（仅视频结算链路设置）。
     *
     * @param resolution    实际输出分辨率（已大写归一化，如 720P）；缺失由计费侧拒计费
     * @param hasVideoInput 本次调用是否有视频输入（price_mode=2 参与规则匹配）
     * @param seconds       视频时长（秒，可小数）；duration/frames 折算后的计量值
     * @param tokens        上游 usage.completion_tokens（对账留痕；billing_unit=token 时参与计量）
     */
    public record VideoUsage(String resolution, Boolean hasVideoInput, BigDecimal seconds, Long tokens) {
    }

    public static class BillingContext {
        private String userId;
        private String userAccount;
        private String apiKeyId;
        private String apiKeyName;
        private String modelName;
        private String serviceType;
        private String channelId;
        private String channelName;
        private String provider;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private Boolean isSuccess = true;
        private String errorCode;
        private String errorMessage;
        private Long responseTimeMs;
        private String traceId;
        private String clientIp;
        private LocalDateTime startedAt;
        // 企业信息（用于余额扣减 + 预警）
        private Long enterpriseId;
        private String enterpriseName;
        private String companyId;
        private Integer userType;
        private Long systemUserId;
        private Boolean platformUser;
        private Boolean isFreeQuota = false;
        private Long freeQuotaConsumed = 0L;
        // 账户信息（用于从 ai_account_balance 扣减余额）
        private String accountId;
        private Integer accountType;
        // ===== 6 维计费扩展（P4）=====
        private org.unreal.modelrouter.billing.usage.TokenUsage tokenUsage; // 6 维归一化用量（优先于 promptTokens 等旧字段）
        private String vendor;   // ai_model.vendor（用于计费日志/快照）
        private String baseUrl;  // 实例 baseUrl（用于计费日志/快照）
        // ===== 视频计费扩展（serviceType=vidGen，非 null 时走视频计费分支）=====
        private VideoUsage videoUsage; // 视频实际用量（分辨率/有无视频输入/秒数/token）
        // 任务创建时快照的计费规则（V7：提交时刻锁价，优先于实时定价；null 时回退实时定价）
        private ModelPricingService.VideoPricingSnapshot videoPricingSnapshot;
        // ===== 模型返回响应快照（V5；截断至 8KB、base64 脱敏；构建失败为 null）=====
        private String responseSnapshot;

        public static BillingContext create() { return new BillingContext(); }

        public BillingContext userId(String v) { this.userId = v; return this; }
        public BillingContext userAccount(String v) { this.userAccount = v; return this; }
        public BillingContext apiKeyId(String v) { this.apiKeyId = v; return this; }
        public BillingContext apiKeyName(String v) { this.apiKeyName = v; return this; }
        public BillingContext modelName(String v) { this.modelName = v; return this; }
        public BillingContext serviceType(String v) { this.serviceType = v; return this; }
        public BillingContext channelId(String v) { this.channelId = v; return this; }
        public BillingContext channelName(String v) { this.channelName = v; return this; }
        public BillingContext provider(String v) { this.provider = v; return this; }
        public BillingContext promptTokens(Long v) { this.promptTokens = v; return this; }
        public BillingContext completionTokens(Long v) { this.completionTokens = v; return this; }
        public BillingContext totalTokens(Long v) { this.totalTokens = v; return this; }
        public BillingContext isSuccess(Boolean v) { this.isSuccess = v; return this; }
        public BillingContext errorCode(String v) { this.errorCode = v; return this; }
        public BillingContext errorMessage(String v) { this.errorMessage = v; return this; }
        public BillingContext responseTimeMs(Long v) { this.responseTimeMs = v; return this; }
        public BillingContext traceId(String v) { this.traceId = v; return this; }
        public BillingContext clientIp(String v) { this.clientIp = v; return this; }
        public BillingContext startedAt(LocalDateTime v) { this.startedAt = v; return this; }
        public BillingContext enterpriseId(Long v) { this.enterpriseId = v; return this; }
        public BillingContext enterpriseName(String v) { this.enterpriseName = v; return this; }
        public BillingContext companyId(String v) { this.companyId = v; return this; }
        public BillingContext userType(Integer v) { this.userType = v; return this; }
        public BillingContext systemUserId(Long v) { this.systemUserId = v; return this; }
        public BillingContext platformUser(Boolean v) { this.platformUser = v; return this; }
        public BillingContext isFreeQuota(Boolean v) { this.isFreeQuota = v; return this; }
        public BillingContext freeQuotaConsumed(Long v) { this.freeQuotaConsumed = v; return this; }
        public BillingContext accountId(String v) { this.accountId = v; return this; }
        public BillingContext accountType(Integer v) { this.accountType = v; return this; }
        public BillingContext tokenUsage(org.unreal.modelrouter.billing.usage.TokenUsage v) { this.tokenUsage = v; return this; }
        public BillingContext vendor(String v) { this.vendor = v; return this; }
        public BillingContext baseUrl(String v) { this.baseUrl = v; return this; }
        public BillingContext videoUsage(VideoUsage v) { this.videoUsage = v; return this; }
        public BillingContext videoPricingSnapshot(ModelPricingService.VideoPricingSnapshot v) { this.videoPricingSnapshot = v; return this; }
        public BillingContext responseSnapshot(String v) { this.responseSnapshot = v; return this; }

        public String getUserId() { return userId; }
        public String getUserAccount() { return userAccount; }
        public String getApiKeyId() { return apiKeyId; }
        public String getApiKeyName() { return apiKeyName; }
        public String getModelName() { return modelName; }
        public String getServiceType() { return serviceType; }
        public String getChannelId() { return channelId; }
        public String getChannelName() { return channelName; }
        public String getProvider() { return provider; }
        public Long getPromptTokens() { return promptTokens; }
        public Long getCompletionTokens() { return completionTokens; }
        public Long getTotalTokens() { return totalTokens; }
        public Boolean getIsSuccess() { return isSuccess; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }
        public Long getResponseTimeMs() { return responseTimeMs; }
        public String getTraceId() { return traceId; }
        public String getClientIp() { return clientIp; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public Long getEnterpriseId() { return enterpriseId; }
        public String getEnterpriseName() { return enterpriseName; }
        public String getCompanyId() { return companyId; }
        public Integer getUserType() { return userType; }
        public Long getSystemUserId() { return systemUserId; }
        public Boolean getPlatformUser() { return platformUser; }
        public Boolean getIsFreeQuota() { return isFreeQuota; }
        public Long getFreeQuotaConsumed() { return freeQuotaConsumed; }
        public String getAccountId() { return accountId; }
        public Integer getAccountType() { return accountType; }
        public org.unreal.modelrouter.billing.usage.TokenUsage getTokenUsage() { return tokenUsage; }
        public String getVendor() { return vendor; }
        public String getBaseUrl() { return baseUrl; }
        public VideoUsage getVideoUsage() { return videoUsage; }
        public ModelPricingService.VideoPricingSnapshot getVideoPricingSnapshot() { return videoPricingSnapshot; }
        public String getResponseSnapshot() { return responseSnapshot; }
    }
}
