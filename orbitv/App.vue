<template>
  <k-app theme="ios" :safe-areas="false" :class="{ dark: isDark }">
    <div
      v-if="initializing"
      class="flex h-full w-full flex-col items-center justify-center gap-3 text-sm text-black/55 dark:text-white/55"
      role="status"
    >
      <k-preloader />
      <span>正在初始化 OrbitV…</span>
    </div>
    <div
      v-else-if="initializationError"
      class="flex h-full w-full items-center justify-center p-8 text-center text-sm text-red-600 dark:text-red-400"
      role="alert"
    >
      {{ initializationError }}
    </div>
    <div v-else class="route-stage">
      <RouterView v-slot="{ Component, route }">
        <Transition :name="route.meta.pageTransition || 'page-forward'">
          <KeepAlive :max="10">
            <PageStackItem
              v-if="Component"
              :key="route.fullPath"
              :page-component="Component"
            />
          </KeepAlive>
        </Transition>
      </RouterView>
    </div>
  </k-app>
</template>

<script>
import { kApp, kPreloader } from 'konsta/vue'
import manifest from './orbitv.json'
import PageStackItem from './common/PageStackItem.vue'
import { initializeOrbitV } from './common/orbitv.js'

export default {
  name: 'App',

  components: {
    kApp,
    kPreloader,
    PageStackItem,
  },

  data() {
    return {
      backRegistration: null,
      colorSchemeQuery: null,
      initializationError: '',
      initializing: true,
      isDark: false,
    }
  },

  async mounted() {
    this.colorSchemeQuery = window.matchMedia('(prefers-color-scheme: dark)')
    this.isDark = this.colorSchemeQuery.matches
    this.colorSchemeQuery.addEventListener('change', this.updateColorScheme)

    try {
      const { ov } = await initializeOrbitV(manifest)
      this.backRegistration = ov.navigation.onBack(() => {
        if (this.$route.path === '/') return false
        this.$router.back()
        return true
      })
    } catch (error) {
      this.initializationError = error.message || 'OrbitV 初始化失败'
    } finally {
      this.initializing = false
    }
  },

  beforeUnmount() {
    this.backRegistration?.remove()
    this.colorSchemeQuery?.removeEventListener('change', this.updateColorScheme)
  },

  methods: {
    updateColorScheme(event) {
      this.isDark = event.matches
    },
  },
}
</script>

<style src="./static/app.css"></style>
