<template>
  <div
    v-if="rows.length"
    :role="failed ? 'alert' : 'status'"
    :aria-live="failed ? 'assertive' : 'polite'"
  >
    <k-block-title>{{ heading }}</k-block-title>
    <k-list strong inset>
      <k-list-item
        v-for="row in rows"
        :key="row.key || `${row.label}:${row.value}`"
        :title="row.label"
        :subtitle="row.multiline ? row.value : undefined"
        :after="row.multiline ? undefined : row.value"
      />
    </k-list>
  </div>
</template>

<script>
import { kBlockTitle, kList, kListItem } from 'konsta/vue'
import { getOrbitV } from './orbitv.js'

export default {
  name: 'ActionResult',

  components: {
    kBlockTitle,
    kList,
    kListItem,
  },

  computed: {
    notificationSignature() {
      return `${this.title}\n${this.message}\n${this.failed}`
    },
  },

  watch: {
    notificationSignature() {
      this.showNotification()
    },
  },

  mounted() {
    this.showNotification()
  },

  props: {
    heading: {
      type: String,
      default: '详细内容',
    },
    title: {
      type: String,
      required: true,
    },
    message: {
      type: String,
      default: '',
    },
    rows: {
      type: Array,
      default: () => [],
    },
    failed: {
      type: Boolean,
      default: false,
    },
  },

  methods: {
    async showNotification() {
      if (this.title.startsWith('正在')) return
      try {
        const ov = await getOrbitV()
        await ov.ui.toast({
          type: this.failed ? 'error' : 'success',
          title: this.title,
          message: this.message || (this.failed ? '请检查后重试' : '操作已完成'),
        })
      } catch (error) {
        console.error('[OrbitV 示例] Toast 显示失败', error)
      }
    },
  },
}
</script>

<style scoped>
/* 结果直接使用 Konsta UI 列表展示，不输出原始 JSON。 */
</style>
