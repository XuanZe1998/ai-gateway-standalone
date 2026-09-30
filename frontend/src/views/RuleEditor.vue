<template>
  <div class="rule-editor">
    <!-- 规则名称 + 优先级 -->
    <div class="rule-header">
      <el-input v-model="ruleName" placeholder="规则名称（如：工作日高峰）" maxlength="100" style="width:240px" />
      <span class="muted">优先级 {{ priority }}（数字越小越先匹配）</span>
    </div>

    <!-- 匹配条件 -->
    <div class="section-title">匹配条件</div>
    <div class="muted gap-sm">无条件规则（空条件组）= 兜底，总是命中</div>
    <ConditionGroup
      :node="rootCondition"
      :fields="fields"
      :depth="0"
      @remove-condition="removeCondition"
      @add-condition="addCondition"
      @add-group="addGroup"
      @toggle-operator="toggleOperator"
      @change-field="onChangeField"
      @change-op="onChangeOp"
      @change-value="onChangeValue" />

    <!-- 计费维度价格 -->
    <div class="section-title gap-top">计费维度价格（元/M token）</div>
    <div class="muted gap-sm">未填的维度不参与本规则计费。维度列表来自计费维度管理页（{{ groupLabel }} 组）。</div>
    <div class="price-grid gap-sm">
      <div v-for="dim in availableDims" :key="dim.dimensionKey" class="price-row">
        <span class="dim-name">{{ dim.displayName }}</span>
        <el-input-number
          v-model="priceMap[dim.dimensionKey]"
          :min="dim.valueType === 'discount' ? 0 : undefined"
          :max="dim.valueType === 'discount' ? 100 : undefined"
          :precision="dim.valueType === 'discount' ? 0 : 6"
          :placeholder="dim.valueType === 'discount' ? '0-100' : '单价'"
          size="small"
          controls-position="right"
          style="width:160px"
          class="price-input" />
        <span class="muted">{{ dim.unit }}</span>
      </div>
      <div v-if="availableDims.length === 0" class="muted">暂无可用维度，请先在维度管理页添加</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getContextFields, getDimensions,
  type ContextField, type BillingDimension, type ConditionNode
} from '@/api/pricing'

const props = defineProps<{
  modelValue: { ruleName: string; matchJson: string; priceJson: string; priority: number }
  groupKey: string
}>()
const emit = defineEmits<{
  'update:modelValue': [val: { ruleName: string; matchJson: string; priceJson: string; priority: number }]
}>()

// 从后端加载字段元数据与维度列表
const fields = ref<ContextField[]>([])
const dims = ref<BillingDimension[]>([])
const groupLabel = computed(() => {
  const g = props.groupKey
  return g === 'text' ? '文本' : g === 'voice' ? '语音' : g === 'image' ? '图像' : g
})

// 当前组的价格维度（valueType=price 或 discount）
const availableDims = computed(() =>
  dims.value.filter(d => d.groupKey === props.groupKey && d.enabled && (d.valueType === 'price' || d.valueType === 'discount'))
)

// 规则名/优先级
const ruleName = ref(props.modelValue.ruleName)
const priority = ref(props.modelValue.priority)

// 条件树
const rootCondition = ref<ConditionNode>(parseMatch(props.modelValue.matchJson))

// 价格 map
const priceMap = ref<Record<string, number | null>>(parsePrice(props.modelValue.priceJson))

// 加载元数据
async function loadMeta() {
  try {
    fields.value = await getContextFields()
    dims.value = await getDimensions(props.groupKey)
    // 如果 priceMap 里有 key 不在 dims 里（旧数据），保留
    const saved = parsePrice(props.modelValue.priceJson)
    for (const dim of availableDims.value) {
      if (saved[dim.dimensionKey] !== undefined) {
        priceMap.value[dim.dimensionKey] = saved[dim.dimensionKey]
      } else if (priceMap.value[dim.dimensionKey] === undefined) {
        priceMap.value[dim.dimensionKey] = null
      }
    }
  } catch { ElMessage.error('加载上下文字段或维度列表失败') }
}

// 解析 matchJson → ConditionNode
function parseMatch(json: string): ConditionNode {
  if (!json) return { operator: 'AND', conditions: [] }
  try {
    const obj = JSON.parse(json)
    if (obj && obj.operator && Array.isArray(obj.conditions)) return obj
    return { operator: 'AND', conditions: [] }
  } catch {
    return { operator: 'AND', conditions: [] }
  }
}

// 解析 priceJson → map
function parsePrice(json: string): Record<string, number | null> {
  if (!json) return {}
  try {
    const obj = JSON.parse(json)
    const map: Record<string, number | null> = {}
    if (obj && typeof obj === 'object') {
      for (const [k, v] of Object.entries(obj)) {
        if (typeof v === 'number') map[k] = v
      }
    }
    return map
  } catch { return {} }
}

// ===== 条件树操作 =====
function addCondition(parent: ConditionNode) {
  if (!parent.conditions) parent.conditions = []
  parent.conditions.push({ field: fields.value[0]?.field || 'tokenCount', op: '>=', value: 0 })
}

function addGroup(parent: ConditionNode) {
  if (!parent.conditions) parent.conditions = []
  parent.conditions.push({ operator: 'OR', conditions: [{ field: fields.value[0]?.field || 'tokenCount', op: '>=', value: 0 }] })
}

function removeCondition(parent: ConditionNode, index: number) {
  if (parent.conditions) parent.conditions.splice(index, 1)
}

function toggleOperator(node: ConditionNode) {
  node.operator = node.operator === 'AND' ? 'OR' : 'AND'
}

function onChangeField(node: ConditionNode, field: string) {
  node.field = field
  // 根据字段类型重置 op 和 value
  const f = fields.value.find(f => f.field === field)
  if (f) {
    node.op = f.operators[0]
    if (f.valueType === 'boolean') node.value = false
    else if (f.valueType === 'int') node.value = 0
    else node.value = ''
  }
}

function onChangeOp(node: ConditionNode, op: string) { node.op = op }

function onChangeValue(node: ConditionNode, value: any) { node.value = value }

// ===== 输出 =====
function buildMatchJson(): string {
  return JSON.stringify(rootCondition.value)
}

function buildPriceJson(): string {
  const result: Record<string, number> = {}
  for (const [k, v] of Object.entries(priceMap.value)) {
    if (v != null && !isNaN(v)) result[k] = v
  }
  return JSON.stringify(result)
}

// 监听变化并 emit
watch([ruleName, priority, rootCondition, priceMap], () => {
  emit('update:modelValue', {
    ruleName: ruleName.value,
    matchJson: buildMatchJson(),
    priceJson: buildPriceJson(),
    priority: priority.value
  })
}, { deep: true })

loadMeta()
</script>

<script lang="ts">
// 递归条件组组件（在同一文件内定义，避免多文件）
import { defineComponent, h } from 'vue'
import { ElIcon, ElButton, ElSelect, ElOption, ElInputNumber, ElSwitch, ElInput } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'

export const ConditionGroup = defineComponent({
  name: 'ConditionGroup',
  props: {
    node: { type: Object as () => ConditionNode, required: true },
    fields: { type: Array as () => ContextField[], required: true },
    depth: { type: Number, default: 0 }
  },
  emits: ['remove-condition', 'add-condition', 'add-group', 'toggle-operator', 'change-field', 'change-op', 'change-value'],
  setup(props, { emit }) {
    return () => {
      const node: any = props.node
      const children: any[] = []

      // AND/OR 切换按钮
      children.push(
        h('div', { class: 'cond-operator-row' }, [
          h(ElButton, {
            size: 'small',
            type: node.operator === 'AND' ? 'primary' : 'info',
            plain: true,
            onClick: () => emit('toggle-operator', node)
          }, () => node.operator || 'AND'),
          h(ElButton, { size: 'small', type: 'primary', plain: true, onClick: () => emit('add-condition', node) }, () => [
            h(ElIcon, null, () => h(Plus)),
            ' 条件'
          ]),
          h(ElButton, { size: 'small', type: 'warning', plain: true, onClick: () => emit('add-group', node) }, () => '条件组'),
          props.depth > 0 ? h(ElButton, { size: 'small', type: 'danger', plain: true, onClick: () => emit('remove-condition', node) }, () => '删除组') : null
        ].filter(Boolean))
      )

      // 子条件列表
      if (node.conditions && node.conditions.length > 0) {
        children.push(h('div', { class: 'cond-list', style: { marginLeft: props.depth > 0 ? '20px' : '0' } },
          node.conditions.map((cond: any, index: number) => {
            if (cond.operator) {
              // 递归子组
              return h(ConditionGroup, {
                key: index,
                node: cond,
                fields: props.fields,
                depth: props.depth + 1,
                onRemoveCondition: () => emit('remove-condition', node, index),
                onAddCondition: (n: any) => emit('add-condition', n),
                onAddGroup: (n: any) => emit('add-group', n),
                onToggleOperator: (n: any) => emit('toggle-operator', n),
                onChangeField: (n: any, f: string) => emit('change-field', n, f),
                onChangeOp: (n: any, o: string) => emit('change-op', n, o),
                onChangeValue: (n: any, v: any) => emit('change-value', n, v)
              })
            }
            // 叶子条件行
            return h('div', { key: index, class: 'cond-leaf' }, [
              // 字段下拉
              h(ElSelect, {
                modelValue: cond.field,
                'onUpdate:modelValue': (v: string) => emit('change-field', cond, v),
                size: 'small',
                style: 'width:160px'
              }, () => props.fields.map((f: any) => h(ElOption, { value: f.field, label: f.label }))),
              // 操作符下拉
              h(ElSelect, {
                modelValue: cond.op,
                'onUpdate:modelValue': (v: string) => emit('change-op', cond, v),
                size: 'small',
                style: 'width:100px'
              }, () => {
                const f = props.fields.find((f: any) => f.field === cond.field)
                const ops = f ? f.operators : ['==']
                return ops.map((op: string) => h(ElOption, { value: op, label: op }))
              }),
              // 值输入
              renderValueInput(cond, props.fields, emit),
              // 删除按钮
              h(ElButton, { size: 'small', type: 'danger', plain: true, onClick: () => emit('remove-condition', node, index) }, () => [
                h(ElIcon, null, () => h(Delete))
              ])
            ])
          })
        ))
      }

      return h('div', { class: 'cond-group' }, children)
    }
  }
})

function renderValueInput(cond: any, fields: any[], emit: any) {
  const field = fields.find(f => f.field === cond.field)
  if (!field) return h(ElInput, { modelValue: cond.value, size: 'small', style: 'width:120px', 'onUpdate:modelValue': (v: string) => emit('change-value', cond, v) })

  if (field.valueType === 'boolean') {
    return h(ElSwitch, { modelValue: !!cond.value, 'onUpdate:modelValue': (v: any) => emit('change-value', cond, v) })
  }

  if (cond.op === 'between' && field.valueType === 'int') {
    const arr = Array.isArray(cond.value) ? cond.value : [0, 0]
    return h('div', { style: 'display:flex;gap:4px;align-items:center' }, [
      h(ElInputNumber, { modelValue: arr[0], 'onUpdate:modelValue': (v: any) => { cond.value = [v, arr[1]]; emit('change-value', cond, cond.value) }, size: 'small', style: 'width:100px' }),
      h('span', { style: 'font-size:12px;color:#8296a7' }, '~'),
      h(ElInputNumber, { modelValue: arr[1], 'onUpdate:modelValue': (v: any) => { cond.value = [arr[0], v]; emit('change-value', cond, cond.value) }, size: 'small', style: 'width:100px' })
    ])
  }

  if (cond.op === 'in' || cond.op === 'notIn') {
    return h(ElInput, {
      modelValue: Array.isArray(cond.value) ? cond.value.join(', ') : (cond.value || ''),
      'onUpdate:modelValue': (v: string) => { cond.value = v.split(',').map(s => s.trim()).filter(Boolean); emit('change-value', cond, cond.value) },
      size: 'small', style: 'width:200px', placeholder: '逗号分隔'
    })
  }

  if (field.valueType === 'int') {
    return h(ElInputNumber, { modelValue: cond.value ?? 0, 'onUpdate:modelValue': (v: any) => emit('change-value', cond, v), size: 'small', style: 'width:120px' })
  }

  return h(ElInput, { modelValue: cond.value ?? '', 'onUpdate:modelValue': (v: string) => emit('change-value', cond, v), size: 'small', style: 'width:120px' })
}
</script>

<style scoped>
.rule-editor { padding: 0 }
.rule-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px }
.section-title { font-weight: 600; font-size: 14px; margin-bottom: 8px; padding-bottom: 6px; border-bottom: 1px solid #e4edf3 }
.gap-sm { margin-bottom: 8px }
.gap-top { margin-top: 20px }
.muted { font-size: 12px; color: #8296a7 }
.price-grid { display: flex; flex-direction: column; gap: 8px }
.price-row { display: flex; align-items: center; gap: 12px }
.dim-name { width: 200px; flex-shrink: 0; font-size: 13px }
.price-input { flex-shrink: 0 }
:deep(.cond-group) { margin-bottom: 8px }
:deep(.cond-operator-row) { display: flex; gap: 6px; margin-bottom: 8px; align-items: center }
:deep(.cond-list) { display: flex; flex-direction: column; gap: 6px; padding-left: 12px; border-left: 2px solid #e4edf3 }
:deep(.cond-leaf) { display: flex; gap: 8px; align-items: center; flex-wrap: wrap }
</style>
