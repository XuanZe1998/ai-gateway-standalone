<template>
  <section class="page-shell governance">
    <div class="page-heading"><div><p class="eyebrow">DEPARTMENT ADMINISTRATION</p><h1>部门与用量管理</h1><p>查看部门及个人消耗，分配额度，管理共享预算与使用规则。</p></div><el-button :loading="exporting" @click="download">导出当前报表</el-button></div>
    <el-radio-group v-model="view" class="gap" @change="changeView"><el-radio-button value="departments">部门汇总</el-radio-button><el-radio-button value="members">按用户与身份管理</el-radio-button></el-radio-group>
    <el-alert class="gap" type="info" :closable="false" title="免费但限量：切换到按用户与身份管理，筛选身份并选择成员，点击「批量免费并限量」，可设置每人每月使用金额、Token 上限；至少设置一项，任一预算耗尽后停止新的相关调用，不转为付费。" />
    <el-card class="gap">
      <div class="filters">
        <el-radio-group v-model="preset" @change="changePreset"><el-radio-button value="month">本月</el-radio-button><el-radio-button value="week">近 7 天</el-radio-button><el-radio-button value="today">今日</el-radio-button><el-radio-button value="custom">自定义</el-radio-button></el-radio-group>
        <el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" @change="preset = 'custom'" />
        <el-input v-if="!department" v-model="departmentSearch" placeholder="部门名称或编码" maxlength="128" clearable @keyup.enter="reload" />
        <el-input v-model="search" placeholder="姓名或账号" maxlength="128" clearable @keyup.enter="reload" />
        <el-select v-model="identityType" clearable filterable placeholder="全部身份类型"><el-option v-for="item in directoryOptions.identities" :key="item.value" :value="item.value" :label="`${item.label}（${item.value}）`" /></el-select>
        <el-select v-model="role" clearable placeholder="全部角色"><el-option v-for="item in directoryOptions.roles" :key="item" :value="item" :label="item" /></el-select>
        <el-select v-model="accountType" clearable placeholder="全部计费账户"><el-option :value="2" label="个人计费账户" /><el-option :value="1" label="企业共享账户" /></el-select>
        <el-select v-model="identityState" placeholder="全部校园身份状态"><el-option value="all" label="全部校园身份状态" /><el-option value="enabled" label="校园身份已启用" /><el-option value="disabled" label="校园身份已停用" /></el-select>
        <el-select v-model="currentMode" placeholder="当前全部免费资格"><el-option value="all" label="当前全部免费资格" /><el-option value="free" label="当前免费使用" /><el-option value="paid" label="当前正常计费" /></el-select>
        <el-input v-model="model" placeholder="模型名称（精确匹配）" maxlength="255" clearable @keyup.enter="reload" />
        <el-select v-model="service" clearable placeholder="全部服务"><el-option v-for="(label, value) in serviceLabels" :key="value" :value="value" :label="label" /></el-select>
        <el-select v-model="mode" placeholder="全部账单方式"><el-option value="all" label="全部账单方式" /><el-option value="free" label="免费账单" /><el-option value="paid" label="正常计费账单" /></el-select>
        <el-button type="primary" :loading="loading" @click="reload">查询</el-button><el-button @click="resetFilters">重置筛选</el-button>
      </div><p class="muted">北京时间 · 费用统计按账单归属，部门调动不会搬移历史消耗。月度免费额度已用量与实际总消耗分开显示。</p>
    </el-card>
    <el-alert v-if="error" :title="error" type="error" show-icon class="gap" :closable="false" />
    <div class="metric-grid">
      <el-card class="metric"><span>使用金额（预算计量）</span><strong>¥ {{ money(report?.summary.usage_value) }}</strong><small>非实际扣费 · 未计价 {{ fmt(report?.summary.value_unknown) }} 条</small></el-card>
      <el-card class="metric"><span>实际总 Token</span><strong>{{ fmt(report?.summary.tokens) }}</strong><small>{{ dates[0] }} 至 {{ dates[1] }}</small></el-card>
      <el-card class="metric"><span>账单费用</span><strong>¥ {{ money(report?.summary.cost) }}</strong><small>免费使用账单金额为零</small></el-card>
      <el-card class="metric"><span>已确认扣费</span><strong>¥ {{ money(report?.summary.deducted) }}</strong><small>待结算／核对 {{ fmt(report?.summary.pending) }} 条</small></el-card>
      <el-card class="metric"><span>活跃用户 / 当前成员</span><strong>{{ fmt(report?.summary.active) }} / {{ fmt(report?.summary.members) }}</strong><small>当前未使用 {{ fmt(report?.summary.unused) }} 人 · 账单 {{ fmt(report?.summary.requests) }} 条</small></el-card>
    </div>
    <el-card v-if="alerts.length" class="gap"><template #header><b>本月预算提醒</b></template><div class="alert-list"><el-alert v-for="a in alerts" :key="`${a.department_id}-${a.metric}-${a.threshold}`" :title="`${a.department_id} · ${a.message}`" :type="a.threshold >= 100 ? 'error' : 'warning'" :closable="false" show-icon /></div></el-card>
    <template v-if="department && detail">
      <el-card class="gap"><template #header><div class="section-title"><div><b>{{ department.name }}</b><span class="muted"> · 部门规则与共享额度</span></div><div class="actions"><el-button :disabled="historical" @click="openBatch('DEPARTMENT_POLICY')">设置部门规则</el-button><el-button :disabled="historical" type="primary" plain @click="openBatch('POOL_TOPUP')">追加共享金额</el-button></div></div></template>
        <div class="pool-grid"><div><span>默认使用方式</span><strong>{{ detail.policy.free_usage ? '免费使用' : '正常计费' }}</strong><small>新成员自动继承，保留个人例外</small></div><div><span>默认额度来源</span><strong>{{ funding(detail.policy.funding_source || 'PERSONAL') }}</strong><small>共享额度耗尽后停止调用</small></div><div><span>共享池可用金额</span><strong>¥ {{ money(detail.account.available) }}</strong><small>余额 ¥ {{ money(detail.account.balance) }} · 预占 ¥ {{ money(detail.account.reserved_amount) }}</small></div><div><span>部门本月 Token 预算</span><strong>{{ detail.policy.monthly_tokens == null ? '未设上限' : fmt(detail.policy.monthly_tokens) }}</strong><small>已使用及预占 {{ fmt(detail.monthlyUsage.tokens) }}</small></div></div>
        <el-progress v-if="detail.policy.monthly_tokens != null" :percentage="budgetPercent" :status="budgetPercent >= 100 ? 'exception' : undefined" />
      </el-card>
    </template>
    <el-card v-loading="loading" class="gap">
      <template #header><div class="section-title"><div><el-button v-if="department" link @click="back">← 全部部门</el-button><b>{{ department ? `${department.name} · 成员消耗` : showMembers ? '跨部门用户与身份管理' : '部门用量排行' }}</b></div><el-select v-model="sort" class="sort" @change="reload"><el-option value="cost" label="按费用排序" /><el-option value="tokens" label="按 Token 排序" /><el-option value="requests" label="按账单数排序" /><el-option value="name" label="按名称排序" /></el-select></div></template>
      <div v-if="showMembers && !historical" class="bulk-bar"><el-radio-group v-model="selectionMode"><el-radio value="selected">勾选成员（{{ selected.length }}）</el-radio><el-radio value="all">全部符合筛选的当前成员（跨页）</el-radio></el-radio-group><div class="actions"><el-button @click="openBatch('ALLOCATE')">批量分配额度</el-button><el-button type="primary" @click="openBatch('FREE_LIMITED')">批量免费并限量</el-button><el-button @click="openBatch('POLICY')">批量设置使用规则</el-button><el-button @click="openBatch('RESET_POLICY')">恢复部门默认</el-button></div></div>
      <p v-if="showMembers" class="muted">管理对象按当前部门、身份、角色、计费账户、校园身份状态、当前免费资格及姓名账号筛选。日期、模型、服务和账单方式仅影响统计。跨部门操作固定预览中的名单，新加入用户不自动获授权；个人例外转部门后失效。</p>
      <AppTable v-if="!showMembers" :data="report?.records || []" :paginated="false" row-key="id" empty-text="没有匹配的部门">
        <el-table-column prop="name" label="部门" min-width="180"><template #default="{ row }"><el-button link type="primary" @click="openDepartment(row)">{{ row.name }}</el-button><p class="muted">{{ row.id }}</p></template></el-table-column>
        <el-table-column label="当前人数 / 免费" min-width="140"><template #default="{ row }">{{ fmt(row.user_count) }} / {{ fmt(row.free_user_count) }}</template></el-table-column>
        <el-table-column label="活跃 / 当前未使用" min-width="150"><template #default="{ row }">{{ fmt(row.active) }} / {{ fmt(row.unused_count) }}</template></el-table-column>
        <el-table-column label="总 Token" min-width="140"><template #default="{ row }">{{ fmt(row.tokens) }}</template></el-table-column>
        <el-table-column label="使用金额 ¥" min-width="160"><template #default="{ row }">{{ money(row.usage_value) }}<p v-if="row.value_unknown" class="muted">未计价 {{ fmt(row.value_unknown) }} 条</p></template></el-table-column>
        <el-table-column label="账单费用 ¥" min-width="150"><template #default="{ row }">{{ money(row.cost) }}</template></el-table-column>
        <el-table-column label="已确认扣费 ¥" min-width="150"><template #default="{ row }">{{ money(row.deducted) }}</template></el-table-column>
        <el-table-column label="共享池可用 ¥" min-width="150"><template #default="{ row }">{{ money(row.account?.available) }}</template></el-table-column>
        <el-table-column label="预算与提醒" min-width="180"><template #default="{ row }"><el-tag v-if="row.alerts?.length" type="warning">{{ row.alerts.length }} 条提醒</el-tag><span v-else>{{ row.policy?.monthly_tokens == null ? '未设 Token 上限' : `${fmt(row.monthlyUsage?.tokens)} / ${fmt(row.policy.monthly_tokens)}` }}</span></template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openDepartment(row)">查看详情 →</el-button></template></el-table-column>
      </AppTable>
      <AppTable v-else ref="memberTable" :data="report?.records || []" :paginated="false" :row-key="(row: UsageRow) => `${row.id}:${row.user_id}`" empty-text="此部门没有匹配成员或消耗记录" @selection-change="selectionChanged">
        <el-table-column v-if="!historical" type="selection" :reserve-selection="true" :selectable="(row: UsageRow) => !!row.current_member && !!row.owner_id" width="45" />
        <el-table-column type="expand"><template #default="{ row }"><div class="breakdown"><span>普通输入 {{ fmt(row.input_tokens) }}</span><span>普通输出 {{ fmt(row.output_tokens) }}</span><span>缓存 {{ fmt(row.cache_tokens) }}</span><span>账单 {{ fmt(row.requests) }} 条</span><span>待核对 {{ fmt(row.pending) }} 条</span><small>分项仅展示已记录维度，Token 总量直接使用账单总量。非 Token 计量见个人账单明细。</small></div></template></el-table-column>
        <el-table-column label="用户" min-width="170"><template #default="{ row }"><b>{{ row.display_name }}</b><p class="muted">{{ row.account }}</p><el-tag v-if="!row.current_member" size="small" type="info">历史归属成员</el-tag></template></el-table-column>
        <el-table-column label="部门 / 身份" min-width="190"><template #default="{ row }"><b>{{ row.name }}</b><p class="muted">{{ row.identity_name || '历史身份未知' }} · {{ row.roles || '未记录角色' }}</p></template></el-table-column>
        <el-table-column label="每月使用金额预算 ¥" min-width="210"><template #default="{ row }"><template v-if="row.current_member"><b>{{ row.monthly_cost == null ? '未设上限' : money(row.monthly_cost) }}</b><p class="muted">已用及预占 {{ money(row.budgetUsage?.value) }}</p><p v-if="row.monthly_cost != null" class="muted">剩余 {{ money(Math.max(0, Number(row.monthly_cost) - Number(row.budgetUsage?.value || 0))) }}</p><p v-if="row.budgetUsage?.unknown_value" class="muted">未计价 {{ fmt(row.budgetUsage.unknown_value) }} 笔</p></template><span v-else>—</span></template></el-table-column>
        <el-table-column label="每月 Token 硬上限" min-width="150"><template #default="{ row }">{{ row.current_member ? row.monthly_tokens == null ? '未设上限' : fmt(row.monthly_tokens) : '—' }}</template></el-table-column>
        <el-table-column label="当前方式 / 来源" min-width="180"><template #default="{ row }"><template v-if="row.current_member"><el-tag :type="row.free_usage ? 'success' : 'info'">{{ row.free_usage ? '免费使用' : '正常计费' }}</el-tag><p class="muted">{{ source(row.policy_source) }} · {{ funding(row.funding_source) }}</p></template><span v-else>以历史账单为准</span></template></el-table-column>
        <el-table-column label="实际总 Token" min-width="135"><template #default="{ row }">{{ fmt(row.tokens) }}</template></el-table-column>
        <el-table-column label="使用金额 ¥" min-width="160"><template #default="{ row }">{{ money(row.usage_value) }}<p v-if="row.value_unknown" class="muted">未计价 {{ fmt(row.value_unknown) }} 条</p></template></el-table-column>
        <el-table-column label="账单费用 ¥" min-width="145"><template #default="{ row }">{{ money(row.cost) }}</template></el-table-column>
        <el-table-column label="已确认扣费 ¥" min-width="145"><template #default="{ row }">{{ money(row.deducted) }}</template></el-table-column>
        <el-table-column label="操作" width="220" fixed="right"><template #default="{ row }"><el-button v-if="row.owner_id" link type="primary" @click="inspect(row)">个人详情</el-button><template v-if="row.current_member"><el-button link @click="openBatch('POLICY', row)">设置使用规则</el-button><el-button link @click="openBatch('ALLOCATE', row)">分配</el-button></template></template></el-table-column>
      </AppTable>
      <AppPagination v-model:current-page="page" v-model:page-size="pageSize" :total="report?.total || 0" @current-change="load" @size-change="reload" />
      <p class="muted">活跃以期间内有账单记录为准；账单数不包含尚未形成账单的请求。历史归属未知单列，不回填到用户当前部门。</p>
    </el-card>
    <el-card v-if="detail?.reconciliation.length" class="gap"><template #header><b>预占与待核对记录</b></template><p class="muted">调用或异步任务进行时保留预占。缺失真实用量的记录需要核对上游；已知账单因共享余额不足而待结算的记录，可追加金额后重试。</p><AppTable :data="detail.reconciliation" pagination-key="department-reconciliation"><el-table-column prop="created_at" label="时间" min-width="175" /><el-table-column prop="user_id" label="用户" min-width="170" /><el-table-column prop="department_name" label="部门" min-width="160" /><el-table-column label="使用金额预占 ¥" min-width="160"><template #default="{ row }">{{ row.reserved_value == null ? '计价未知' : money(row.reserved_value) }}</template></el-table-column><el-table-column label="余额预占 / Token" min-width="200"><template #default="{ row }">¥ {{ money(row.reserved_cost) }} / {{ fmt(row.reserved_tokens) }}</template></el-table-column><el-table-column label="状态" min-width="130"><template #default="{ row }">{{ row.state === 'RESERVED' ? '进行中／预占保留' : '待核对' }}</template></el-table-column><el-table-column prop="error_message" label="说明" min-width="220" /><el-table-column label="操作" width="140"><template #default="{ row }"><el-button v-if="row.funding_source === 'DEPARTMENT' && row.billing_record_id && (row.state === 'RECONCILE' || row.settlement_status === 'PENDING') && !row.error_message" link type="primary" :disabled="saving" @click="reconcile(row.request_id)">重试共享扣费</el-button></template></el-table-column></AppTable></el-card>
    <el-card v-if="detail" class="gap"><template #header><b>{{ department ? '部门管理记录' : '跨部门管理记录' }}</b><span class="muted"> · 最近 100 批</span></template><AppTable :data="detail.operations" pagination-key="department-operations"><el-table-column prop="created_at" label="时间" min-width="180" /><el-table-column label="操作" min-width="150"><template #default="{ row }">{{ operationLabel(row.kind) }}</template></el-table-column><el-table-column prop="operator" label="操作人" min-width="120" /><el-table-column prop="reason" label="原因" min-width="200" /><el-table-column label="结果" width="140"><template #default="{ row }"><el-button link type="primary" @click="showOperation(row)">查看变更</el-button></template></el-table-column></AppTable></el-card>
    <DepartmentUserDetail v-model="userOpen" :owner="inspected?.owner_id" :filters="filters" />
    <el-dialog v-model="batchOpen" :title="operationLabel(form.kind)" width="min(980px, 96vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <p><b>{{ department?.name || '跨部门当前成员' }}</b> · {{ single ? `仅 ${single.display_name}` : selectionMode === 'all' ? '全部符合筛选的当前成员，包含其他页' : `已勾选 ${selected.length} 位成员` }}</p>
      <el-alert v-if="form.kind === 'ALLOCATE'" title="金额按实际到账账户追加：同一企业共享账户只追加一次。月度免费 Token 总额逐人设置，保留当月已用量。" type="info" :closable="false" />
      <el-alert v-if="form.kind === 'POOL_TOPUP'" title="资金进入部门共享池，跨月保留。重复提交同一批次不会重复充值。" type="info" :closable="false" />
      <el-form label-position="top" class="batch-form" :disabled="saving || !!result">
        <template v-if="['DEPARTMENT_POLICY','POLICY','FREE_LIMITED'].includes(form.kind)">
          <el-alert v-if="form.kind === 'FREE_LIMITED'" class="full" title="本次授予免费资格，并设置每人每月使用金额或 Token 上限。可只限金额、只限 Token，或同时限制；任一预算耗尽后停止新的相关调用。不是充值，不会清零已记录的当月预算用量。" type="success" :closable="false" />
          <el-form-item label="使用方式"><el-radio-group :disabled="form.kind === 'FREE_LIMITED'" v-model="form.freeUsage"><el-radio :value="false">正常计费</el-radio><el-radio :value="true">免费使用</el-radio></el-radio-group></el-form-item>
          <el-form-item label="额度来源"><el-radio-group v-model="form.fundingSource"><el-radio value="PERSONAL">个人／企业账户</el-radio><el-radio value="DEPARTMENT">部门共享池</el-radio></el-radio-group></el-form-item>
          <p class="muted full">使用金额按正常计费规则在免费减免前计算，用于预算计量；免费期间账单与实际扣费仍为 0，不代表上游成本。金额预算涵盖已配置计价规则的 Token、图片、音视频用量；Token 上限只限制 Token。历史免费记录未计价，不追算。调用预占也占用预算。个人留空的上限继承部门默认，无默认才不限制；部门总预算仍适用。</p>
          <el-form-item v-if="form.kind === 'DEPARTMENT_POLICY'" label="部门每月 Token 预算（清空为不限制）"><el-input-number v-model="form.monthlyTokens" :min="0" :max="1000000000000" :precision="0" /></el-form-item>
          <el-form-item :label="form.kind === 'DEPARTMENT_POLICY' ? '每人每月使用金额上限（元，清空不限制）' : '每人每月使用金额上限（元，清空继承部门）'"><el-input-number v-model="form.monthlyCost" :min="0" :max="100000000" :precision="6" /></el-form-item>
          <el-form-item :label="form.kind === 'DEPARTMENT_POLICY' ? '每人每月 Token 上限（清空不限制）' : '每人每月 Token 上限（清空继承部门）'"><el-input-number v-model="form.memberTokens" :min="0" :max="1000000000000" :precision="0" /></el-form-item>
          <template v-if="form.kind === 'DEPARTMENT_POLICY'"><el-form-item label="本月提醒阈值（%）"><el-select v-model="form.alertThresholds" multiple><el-option v-for="n in [50,60,70,80,90,95,100]" :key="n" :value="n" :label="`${n}%`" /></el-select></el-form-item><el-form-item class="full"><el-checkbox v-model="form.overwriteExceptions">同时移除部门现有个人例外，让所有成员继承本次部门规则</el-checkbox></el-form-item></template>
        </template>
        <el-form-item v-if="form.kind === 'ALLOCATE' || form.kind === 'POOL_TOPUP'" :label="form.kind === 'POOL_TOPUP' ? '追加部门共享金额（元）' : '每个实际账户追加金额（元）'"><el-input-number v-model="form.amount" :min="0" :max="100000000" :precision="6" /></el-form-item>
        <template v-if="form.kind === 'ALLOCATE'"><el-form-item><el-checkbox v-model="setFreeTokens">设置个人月度免费 Token 总额</el-checkbox></el-form-item><el-form-item v-if="setFreeTokens" label="每人月度免费 Token 总额（保留已用量）"><el-input-number v-model="form.monthlyTokens" :min="0" :max="1000000000000" :precision="0" /></el-form-item></template>
        <el-form-item label="操作原因（必填）" class="full"><el-input v-model="form.reason" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <el-alert v-if="batchError" :title="batchError" type="error" :closable="false" class="gap" />
      <template v-if="preview"><div class="preview-heading"><b>影响预览</b><span>{{ preview.count }} 位成员<template v-if="form.kind === 'ALLOCATE'"> · {{ preview.accounts.length }} 个实际账户 · 总追加 ¥ {{ money(preview.totalAmount) }}</template><template v-else-if="form.kind === 'POOL_TOPUP'"> · 部门共享池追加 ¥ {{ money(preview.totalAmount) }}</template></span></div><p v-if="form.kind === 'FREE_LIMITED'"><b>免费使用 · 每人每月金额上限 {{ preview.payload.monthlyCost == null ? '继承部门' : '¥ ' + money(preview.payload.monthlyCost) }} · Token 上限 {{ preview.payload.memberTokens == null ? '继承部门' : fmt(preview.payload.memberTokens) }}；任一预算耗尽后停止新的相关调用。</b></p><p class="muted">预览有效期 30 分钟。成员、到账账户或规则变化时，需要重新预览。</p>
        <AppTable v-if="preview.accounts.length" :data="preview.accounts" pagination-key="department-preview-accounts"><el-table-column prop="accountId" label="实际到账账户" min-width="220" /><el-table-column label="类型"><template #default="{ row }">{{ row.accountType === 3 ? '部门共享池' : row.shared ? '企业共享（已去重）' : '个人' }}</template></el-table-column><el-table-column label="当前余额 ¥"><template #default="{ row }">{{ money(row.balance) }}</template></el-table-column></AppTable>
        <AppTable :data="preview.targets" pagination-key="department-preview-targets"><el-table-column prop="display_name" label="受影响成员" /><el-table-column prop="account" label="账号" /><el-table-column prop="department_name" label="部门" /><el-table-column v-if="['FREE_LIMITED','POLICY'].includes(form.kind)" label="生效月金额上限 ¥" min-width="170"><template #default="{ row }">{{ preview.payload.monthlyCost == null && row.inherited_cost == null ? '不限制' : money(preview.payload.monthlyCost ?? row.inherited_cost) }}</template></el-table-column><el-table-column v-if="['FREE_LIMITED','POLICY'].includes(form.kind)" label="生效月 Token 上限" min-width="170"><template #default="{ row }">{{ preview.payload.memberTokens == null && row.inherited_tokens == null ? '不限制' : fmt(preview.payload.memberTokens ?? row.inherited_tokens) }}</template></el-table-column><el-table-column label="原个人例外"><template #default="{ row }">{{ row.exception_free == null ? '无' : row.exception_free ? '免费使用' : '正常计费' }}</template></el-table-column></AppTable>
      </template>
      <el-alert v-if="result" :title="form.kind === 'POOL_TOPUP' ? `共享池已追加 ¥ ${money(form.amount)}。批次 ${result.id}` : `操作完成：${result.success} 位成员，失败 ${result.failed} 位。批次 ${result.id}`" type="success" :closable="false" />
      <template #footer><el-button :disabled="saving" @click="batchOpen = false">{{ result ? '关闭' : '取消' }}</el-button><el-button v-if="!result" :loading="saving" @click="prepareBatch">预览影响</el-button><el-button v-if="preview && !result" type="primary" :loading="saving" @click="submitBatch">确认提交本次预览</el-button></template>
    </el-dialog>
    <el-dialog v-model="operationOpen" title="变更记录" width="min(1000px, 96vw)"><p>{{ operation?.operator }} · {{ operationLabel(operation?.kind || '') }} · {{ operation?.created_at }}</p><p>{{ operation?.reason }}</p><el-descriptions :column="1" border><el-descriptions-item label="批次">{{ operation?.id }}</el-descriptions-item><el-descriptions-item label="提交内容">{{ operationSummary }}</el-descriptions-item></el-descriptions><p v-if="operationDepartmentChanges">{{ operationDepartmentChanges }}</p><AppTable :data="operationChanges"><el-table-column prop="name" label="成员" /><el-table-column prop="account" label="实际账户" min-width="200" /><el-table-column prop="balance" label="余额变化 ¥" min-width="170" /><el-table-column prop="quota" label="免费 Token 总额变化" min-width="170" /><el-table-column prop="policy" label="个人例外变化" min-width="160" /></AppTable></el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { usePageSizePreference } from '@/composables/usePageSizePreference'
import DepartmentUserDetail from './components/DepartmentUserDetail.vue'
import { limitedBudgetError, batchSelection, commitBatch, dateRange, exportReport, formatMoney as money, formatTokens as fmt, fundingSource as funding, getDirectoryOptions, getAlerts, getDepartment, getReport, policySource as source, previewBatch, retrySettlement, type Alert, type BatchPreview, type BatchRequest, type DepartmentDetail, type DirectoryOptions, type Operation, type UsageFilters, type UsageReport, type UsageRow } from '@/api/departmentAdmin'
const dates = ref<[string,string]>(dateRange('month')), preset = ref('month'), department = ref<UsageRow>(), detail = ref<DepartmentDetail>(), report = ref<UsageReport>()
const view = ref('departments'), showMembers = computed(() => view.value === 'members' || !!department.value)
const identityType = ref(''), role = ref(''), accountType = ref<number>(), identityState = ref('all'), currentMode = ref('all'), directoryOptions = ref<DirectoryOptions>({ identities: [], roles: [] })
const search = ref(''), departmentSearch = ref(''), model = ref(''), service = ref(''), mode = ref('all'), sort = ref('cost')
const page = ref(1), pageSize = usePageSizePreference('department-usage'), loading = ref(false), exporting = ref(false), error = ref(''), alerts = ref<Alert[]>([])
const selected = ref<UsageRow[]>([]), selectionMode = ref('selected'), memberTable = ref<{ clearSelection: () => void }>(), single = ref<UsageRow>()
const userOpen = ref(false), inspected = ref<UsageRow>(), batchOpen = ref(false), saving = ref(false), batchError = ref(''), setFreeTokens = ref(false), preview = ref<BatchPreview>(), result = ref<{ id:string;success:number;failed:number }>()
const form = reactive<BatchRequest>({ departmentId: '', kind: 'ALLOCATE', amount: 0, freeUsage: false, fundingSource: 'PERSONAL', reason: '', alertThresholds: [80,90,100] })
const serviceLabels = { chat: '对话', embedding: '向量', rerank: '重排序', imgGen: '图片生成', imgEdit: '图片编辑', vidGen: '视频生成', tts: '语音合成', stt: '语音识别' }
const historical = computed(() => department.value?.id === 'historical:unknown')
const filters = computed<UsageFilters>(() => ({ start: dates.value[0], end: dates.value[1], department: department.value?.id, search: search.value || undefined, departmentSearch: department.value ? undefined : departmentSearch.value || undefined, model: model.value || undefined, service: service.value || undefined, freeUsage: mode.value === 'all' ? undefined : mode.value === 'free', sort: sort.value, identityType: identityType.value || undefined, role: role.value || undefined, accountType: accountType.value, enabled: identityState.value === 'all' ? undefined : identityState.value === 'enabled', currentFreeUsage: currentMode.value === 'all' ? undefined : currentMode.value === 'free' }))
const budgetPercent = computed(() => !detail.value?.policy.monthly_tokens ? (Number(detail.value?.monthlyUsage.tokens) > 0 ? 100 : 0) : Math.min(100, Math.round(Number(detail.value.monthlyUsage.tokens) / detail.value.policy.monthly_tokens * 100)))
let revision = 0
const errorMessage = (e: any) => e.response?.data?.message || e.response?.data?.error || e.message || '操作失败'
async function load() {
  const current = ++revision; loading.value = true; error.value = ''
  const f = { ...filters.value }, id = department.value?.id
  try {
    const [data, departmentDetail, notifications] = await Promise.all([getReport(f, showMembers.value, page.value - 1, pageSize.value), id ? getDepartment(id) : showMembers.value ? getDepartment('all:members') : Promise.resolve(undefined), getAlerts(id)])
    if (current !== revision) return
    report.value = data; detail.value = departmentDetail; alerts.value = notifications
  } catch (e) { if (current === revision) { error.value = errorMessage(e); report.value = undefined; detail.value = undefined; alerts.value = [] } }
  finally { if (current === revision) loading.value = false }
}
function reload() { page.value = 1; selected.value = []; memberTable.value?.clearSelection(); void load() }
function changePreset() { if (preset.value !== 'custom') { dates.value = dateRange(preset.value as 'today'|'week'|'month'); reload() } }
function resetFilters() { dates.value = dateRange('month'); preset.value = 'month'; search.value = ''; departmentSearch.value = ''; model.value = ''; service.value = ''; mode.value = 'all'; identityType.value = ''; role.value = ''; accountType.value = undefined; identityState.value = 'all'; currentMode.value = 'all'; sort.value = 'cost'; reload() }
function changeView() { department.value = undefined; selectionMode.value = 'selected'; reload() }
function openDepartment(row: UsageRow) { department.value = row; selectionMode.value = 'selected'; reload() }
function back() { view.value = 'departments'; department.value = undefined; detail.value = undefined; reload() }
function selectionChanged(rows: UsageRow[]) { selected.value = rows }
function inspect(row: UsageRow) { inspected.value = row; userOpen.value = true }
async function download() { exporting.value = true; try { await exportReport(filters.value, showMembers.value) } catch (e) { ElMessage.error(errorMessage(e)) } finally { exporting.value = false } }
const operationLabel = (kind: string) => ({ ALLOCATE: '批量分配额度', POLICY: '批量设置使用规则', FREE_LIMITED: '批量免费并限量', RESET_POLICY: '恢复部门默认', DEPARTMENT_POLICY: '设置部门默认规则', POOL_TOPUP: '追加部门共享金额' }[kind] || kind)
async function reconcile(id: string) {
  try { const { value } = await ElMessageBox.prompt('填写核对说明。重试只扣部门共享池，不会扣个人余额。', '重试共享扣费', { inputValidator: value => !!value?.trim() || '请填写原因', inputPattern: /^.{1,500}$/, inputErrorMessage: '请填写 1-500 字说明' }); saving.value = true; const response = await retrySettlement(id, value); if (response.settled) ElMessage.success('共享扣费已确认'); else ElMessage.warning('共享池可用余额仍不足，继续保留待结算'); await load() } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(errorMessage(e)) } finally { saving.value = false }
}
function openBatch(kind: string, row?: UsageRow) {
  if (historical.value || (!department.value && !showMembers.value)) return
  if (!row && !['DEPARTMENT_POLICY','POOL_TOPUP'].includes(kind) && selectionMode.value === 'selected' && !selected.value.length) { ElMessage.warning('请勾选成员，或选择全部符合筛选的当前成员'); return }
  single.value = row; preview.value = undefined; result.value = undefined; batchError.value = ''; setFreeTokens.value = false
  const p = detail.value?.policy
  Object.assign(form, { departmentId: department.value?.id || 'all:members', kind, ownerIds: undefined, search: undefined, amount: 0, reason: '', freeUsage: kind === 'FREE_LIMITED' ? true : row?.free_usage ?? p?.free_usage ?? false, fundingSource: row?.funding_source ?? p?.funding_source ?? 'PERSONAL', monthlyTokens: kind === 'DEPARTMENT_POLICY' ? p?.monthly_tokens ?? undefined : undefined, monthlyCost: row?.monthly_cost ?? p?.member_cost_limit ?? undefined, memberTokens: row?.monthly_tokens ?? p?.member_token_limit ?? (kind === 'FREE_LIMITED' ? 1000000 : undefined), alertThresholds: p?.alert_thresholds ?? [80,90,100], overwriteExceptions: false })
  batchOpen.value = true
}
watch([form, setFreeTokens], () => { preview.value = undefined }, { deep: true })
function batchPayload(): BatchRequest {
  const scope = ['DEPARTMENT_POLICY','POOL_TOPUP'].includes(form.kind)
  const selection = scope ? {} : single.value ? { ownerIds: [single.value.owner_id!] } : batchSelection(filters.value, selectionMode.value === 'selected' ? selected.value.map(r => r.owner_id!) : undefined)
  return { ...form, ...selection, monthlyTokens: form.kind === 'ALLOCATE' && !setFreeTokens.value ? null : form.monthlyTokens ?? null, monthlyCost: form.monthlyCost ?? null, memberTokens: form.memberTokens ?? null }
}
async function prepareBatch() { if (limitedBudgetError(form)) { batchError.value = limitedBudgetError(form); return } saving.value = true; batchError.value = ''; try { preview.value = await previewBatch(batchPayload()) } catch (e) { batchError.value = errorMessage(e) } finally { saving.value = false } }
async function submitBatch() { if (!preview.value || saving.value) return; saving.value = true; batchError.value = ''; try { result.value = await commitBatch(preview.value.id); selected.value = []; memberTable.value?.clearSelection(); await load() } catch (e) { batchError.value = errorMessage(e) } finally { saving.value = false } }
const operationOpen = ref(false), operation = ref<Operation>(), operationChanges = ref<Record<string,string>[]>([]), operationSummary = ref(''), operationDepartmentChanges = ref('')
function showOperation(row: Operation) {
  operation.value = row; operationChanges.value = []; operationSummary.value = ''; operationDepartmentChanges.value = ''; operationOpen.value = true
  try {
    const data = JSON.parse(row.result), before = data.before || [], after = data.after || [], p = data.payload || {}; if (data.error) { operationSummary.value = `失败，整批已回滚：${data.error}`; return }
    if (row.kind === 'POOL_TOPUP') operationDepartmentChanges.value = `共享池余额：¥ ${money(data.departmentBefore?.account?.balance)} → ¥ ${money(data.departmentAfter?.account?.balance)}`
    if (row.kind === 'DEPARTMENT_POLICY') { const b = data.departmentBefore?.policy || {}, a = data.departmentAfter?.policy || {}; operationDepartmentChanges.value = `使用方式：${b.free_usage ? '免费' : '正常计费'} → ${a.free_usage ? '免费' : '正常计费'}；额度来源：${funding(b.funding_source || 'PERSONAL')} → ${funding(a.funding_source || 'PERSONAL')}；部门 Token 上限：${b.monthly_tokens == null ? '不限制' : fmt(b.monthly_tokens)} → ${a.monthly_tokens == null ? '不限制' : fmt(a.monthly_tokens)}` }
    operationSummary.value = `${operationLabel(row.kind)}；${p.freeUsage == null ? '' : p.freeUsage ? '免费使用；' : '正常计费；'}${p.fundingSource ? funding(p.fundingSource) + '；' : ''}${p.amount ? '追加 ¥ ' + money(p.amount) + '；' : ''}${p.monthlyTokens == null ? '' : '月度 Token 总额 ' + fmt(p.monthlyTokens) + '；'}${p.memberTokens == null ? '' : '每人每月 Token 硬上限 ' + fmt(p.memberTokens) + '；'}${p.monthlyCost == null ? '' : '每人每月使用金额上限 ¥ ' + money(p.monthlyCost) + '；'}`
    operationChanges.value = after.map((a: any) => { const b = before.find((v: any) => v.owner_id === a.owner_id) || {}; const policy = (v: any) => v == null ? '继承部门' : v ? '免费使用' : '正常计费'; return { name: a.display_name, account: a.allocation_account, balance: `${money(b.balance)} → ${money(a.balance)}`, quota: `${fmt(b.total_quota)} → ${fmt(a.total_quota)}`, policy: `${policy(b.exception_free)} → ${policy(a.exception_free)}` } })
  } catch { operationSummary.value = '此记录没有可展示的变更明细' }
}
onMounted(async () => { await load(); try { directoryOptions.value = await getDirectoryOptions() } catch (e) { ElMessage.error(errorMessage(e)) } })
</script>

<style scoped>
.filters,.actions,.section-title,.bulk-bar,.preview-heading{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.filters>.el-input{width:200px}.filters>.el-select{width:160px}.filters>.el-date-editor{max-width:310px}.section-title,.preview-heading{justify-content:space-between}.section-title>div{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.sort{width:160px}.bulk-bar{justify-content:space-between;padding:0 0 18px}.pool-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:24px;margin-bottom:18px}.pool-grid>div{display:flex;flex-direction:column;gap:10px}.pool-grid span,.pool-grid small{color:var(--el-text-color-secondary)}.pool-grid strong{font-size:20px}.alert-list{display:grid;gap:10px}.breakdown{display:flex;gap:22px;flex-wrap:wrap;padding:16px 32px}.breakdown small{width:100%;color:var(--el-text-color-secondary)}.batch-form{display:grid;grid-template-columns:1fr 1fr;gap:0 28px;margin-top:24px}.batch-form .full{grid-column:1/-1}.batch-form .el-input-number,.batch-form .el-select{width:100%}.preview-heading{margin-top:20px}.muted{font-size:12px;color:var(--el-text-color-secondary)}.governance :deep(.el-pagination){margin-top:20px}@media(max-width:1000px){.pool-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:720px){.governance :deep(.el-table__cell.is-fixed-column--right){position:static!important;right:auto!important;box-shadow:none!important}}@media(max-width:600px){.pool-grid,.batch-form{grid-template-columns:1fr}.filters>.el-input,.filters>.el-select{width:100%}}
</style>
