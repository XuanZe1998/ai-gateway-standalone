<template>
  <div class="pricing-schemes">
    <section v-for="scheme in schemes" :key="scheme.label" class="pricing-scheme">
      <div class="scheme-heading"><h3>{{ scheme.label }}</h3><el-tag effect="plain">{{ scheme.mode }}</el-tag></div>
      <div v-if="scheme.discounts" class="discount-strip">
        <span>模型支付比例 <b>{{ percent(scheme.discounts.modelDiscountRate) }}</b></span>
        <span>用户支付比例 <b>{{ percent(scheme.discounts.userDiscountRate) }}</b></span>
        <span>企业支付比例 <b>{{ percent(scheme.discounts.enterpriseDiscountRate) }}</b></span>
        <span class="effective">最终支付比例 <b>{{ percent(scheme.discounts.finalDiscountRate) }}</b></span>
      </div>
      <el-alert v-if="!scheme.configured" title="部分价格未配置或暂不可确认，不代表免费。" type="warning" :closable="false" show-icon />
      <el-table v-if="scheme.lines.length" :data="scheme.lines" class="price-table" stripe>
        <el-table-column prop="item" label="计费项目" min-width="125" />
        <el-table-column prop="condition" label="适用条件" min-width="220" />
        <el-table-column label="标准单价" min-width="110"><template #default="{ row }">{{ row.status === 'CHARGED' ? '¥ ' + formatPrice(row.standardPrice) : status(row.status) }}</template></el-table-column>
        <el-table-column label="账户折后单价" min-width="130"><template #default="{ row }"><b>{{ priceText(row) }}</b></template></el-table-column>
        <el-table-column prop="unit" label="单位" min-width="150" />
      </el-table>
      <ul class="scheme-notes"><li v-for="note in scheme.notes" :key="note">{{ note }}</li></ul>
    </section>
  </div>
</template>
<script setup lang="ts">
import type { PricingScheme } from '@/api/modelSquare'
import { formatPrice, priceText } from '@/utils/modelSquare'
defineProps<{ schemes: PricingScheme[] }>()
const percent = (n: number) => new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 4 }).format(n)
const status = (s: string) => ({ NOT_CHARGED: '不计费', IN_OUTPUT: '并入输出', UNKNOWN: '未配置' }[s] || '—')
</script>
<style scoped>
.pricing-scheme{padding:18px 0;border-bottom:1px solid #e5edf3}.scheme-heading{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.scheme-heading h3{margin:0;font-size:16px}.discount-strip{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:16px 0;padding:14px;background:#f2f7fb;border-radius:12px;font-size:12px;color:#5a7186}.discount-strip b{margin-left:6px;color:#193c55}.effective b{color:#078184}.price-table{margin-top:14px}.scheme-notes{padding-left:18px;font-size:12px;color:#698094;line-height:1.8}
@media(max-width:480px){.discount-strip{grid-template-columns:1fr}}
</style>
