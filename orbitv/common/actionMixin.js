export default {
  data() {
    return {
      pending: '',
      lastAction: '',
      result: null,
      failed: false,
    }
  },

  methods: {
    async runAction(name, action) {
      this.pending = name
      this.lastAction = name
      this.failed = false
      this.result = '正在执行…'

      try {
        const value = await action()
        this.result = value === undefined ? { ok: true } : value
        return value
      } catch (error) {
        this.failed = true
        this.result = {
          code: error.code || 'ACTION_FAILED',
          message: error.message || String(error),
          details: error.details || null,
        }
        return null
      } finally {
        this.pending = ''
      }
    },

    clearResult() {
      this.pending = ''
      this.lastAction = ''
      this.failed = false
      this.result = null
    },
  },
}
