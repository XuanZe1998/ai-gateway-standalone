<template>
  <span class="model-icon" :title="label">
    <img v-if="!failed" :src="modelIconUrl(id)" :alt="label + '图标'" width="32" height="32" @error="failed = true" />
    <span v-else class="icon-fallback" aria-label="模型图标">{{ model.displayName.slice(0, 1).toUpperCase() || 'M' }}</span>
  </span>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { modelIconId, modelIconLabel, modelIconUrl, type IconModel } from '@/utils/modelIcons'
const props = defineProps<{ model: IconModel }>()
const id = computed(() => modelIconId(props.model)), label = computed(() => modelIconLabel(id.value))
const failed = ref(false)
watch(id, () => { failed.value = false })
</script>
<style scoped>
.model-icon{display:grid;place-items:center;width:44px;height:44px;flex-shrink:0;background:#f5f8fc;border:1px solid #e4ebf3;border-radius:13px}.model-icon img{display:block;width:32px;height:32px;object-fit:contain}.icon-fallback{font-size:22px;font-weight:650;color:#327adc}
</style>
