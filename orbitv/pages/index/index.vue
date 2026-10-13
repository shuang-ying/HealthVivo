<template>
  <main class="sync-page">
    <div class="intro"><span class="badge">手机 → OrbitV → GT2</span><h1>把每日记录带到腕上</h1><p>配对一次，持续检查连接。断线后自动重新检测。</p></div>
    <section class="sync-card"><span class="label">连接状态 · 每 5 秒检测</span><h2>{{status}}</h2><div class="connection" :class="{ready: phoneReady}"><i></i><span>手机 App</span><strong>{{phoneReady ? '已连接' : '未连接'}}</strong></div><div class="connection" :class="{ready: transportReady}"><i></i><span>手表蓝牙通道</span><strong>{{transportReady ? '就绪' : '未就绪'}}</strong></div><div class="connection" :class="{ready: watchReady}"><i></i><span>手表活力记录</span><strong>{{watchReady ? '已连接' : '等待打开'}}</strong></div><p>{{phoneText}}</p><p>{{watchText}}</p><p v-if="savedText">{{savedText}}</p><button @click="refresh">立即检查连接</button></section>
    <section class="sync-card"><span class="label">手动更新</span><p>确认手机与手表都就绪后传输。连接检测不传输健康记录。</p><button @click="publish" :disabled="!phoneReady || !transportReady || !watchReady || busy">更新到手表</button><small>保持此页面和手表应用打开。也可在手机点击更新或在手表获取更新；只更新新增和修改的日期。</small></section>
    <section class="sync-card"><span class="label">{{paired ? '更换手机配对' : '首次连接'}}</span><p>在活力记录 App 的“我的”开启本机同步并复制配对信息，在下面粘贴一次。</p><textarea v-model="configuration" placeholder="粘贴完整配对信息" autocomplete="off" spellcheck="false"></textarea><button @click="pair" :disabled="!configuration.trim()">保存配对并连接</button><small>配对信息仅保存在此扩展程序。请勿发送给他人。</small></section>
    <p class="bottom">持续本机连接 · 仅手动同步 · 扩展程序 1.2.0</p>
  </main>
</template>
<script>
import { getOrbitV } from '../../common/orbitv.js'
import { validateLink, readControl, heartbeat, LINK_KEY } from '../../common/native-link.js'
export default {
  data: () => ({ configuration: '', paired: false, status: '等待配对', phoneText: '', watchText: '', savedText: '', phoneReady: false, transportReady: false, watchReady: false, timer: null, busy: false, generation: 0 }),
  activated() { this.generation++; clearInterval(this.timer); this.refresh(); this.timer = setInterval(() => this.refresh(), 5000) },
  deactivated() { this.generation++; clearInterval(this.timer); this.timer = null; this.phoneReady = this.transportReady = this.watchReady = false },
  beforeUnmount() { this.generation++; clearInterval(this.timer) },
  methods: {
    async publish() {
      if (this.busy) return; this.busy = true
      try {
        const ov = await getOrbitV(); const link = validateLink(await ov.storage.get(LINK_KEY));
        const info = await ov.watch.getInfo(); const state = await heartbeat(link, info.connected === true && info.transportReady === true);
        if (!state.transportReady || !state.watchAppConnected) { this.watchReady = false; this.status = '请连接手表并打开活力记录，等待就绪'; return }
        const control = await readControl(link, true); this.status = control.message || '更新请求已发出'
      }
      catch (error) { this.status = error.message || '更新失败' } finally { this.busy = false }
    },
    async pair() {
      try { const link = validateLink(this.configuration); const ov = await getOrbitV(); await ov.storage.set(LINK_KEY, link); this.paired = true; this.configuration = ''; await this.refresh() }
      catch (value) { this.status = value.message === '请粘贴活力记录 App 复制的完整配对信息' ? value.message : '配对信息格式不正确或保存失败' }
    },
    async refresh() {
      if (this.busy) return; this.busy = true; const generation = this.generation
      try {
        const ov = await getOrbitV(); const link = await ov.storage.get(LINK_KEY); this.paired = !!link
        if (!link) { this.phoneReady = this.transportReady = this.watchReady = false; this.status = '请先粘贴配对信息'; return }
        const validated = validateLink(link)
        const [phone, watch] = await Promise.allSettled([readControl(validated), ov.watch.getInfo()])
        if (generation !== this.generation) return
        this.phoneReady = phone.status === 'fulfilled'; this.transportReady = watch.status === 'fulfilled' && watch.value.connected === true && watch.value.transportReady === true
        this.watchReady = false
        this.phoneText = this.phoneReady ? '手机接口保持开启，无需反复延长时间' : phone.reason?.message || '手机 App 未连接，将自动重试'
        this.watchText = this.transportReady ? '请保持手表活力记录打开' : '请在 OrbitV 连接 GT2；此页会继续检查'
        if (this.phoneReady) {
          try {
            const state = await heartbeat(validated, this.transportReady)
            if (generation !== this.generation) return
            this.watchReady = this.transportReady && state.watchAppConnected === true
            this.savedText = state.lastSavedAt ? '手表最近确认：' + new Date(state.lastSavedAt).toLocaleTimeString() : '尚未收到手表保存确认'
            this.status = this.watchReady ? state.message || '连接就绪，等待手动更新' : this.transportReady ? '等待手表应用连接' : '等待手表蓝牙连接'
            if (state.watchVersion) this.watchText += ' · ' + state.watchVersion
          } catch (value) { this.phoneReady = false; this.phoneText = value.message; this.status = '手机连接中断，将自动重试' }
        } else this.status = '等待手机 App，将自动重试'
      } catch (value) { this.phoneReady = this.transportReady = this.watchReady = false; this.status = value.message || '连接暂不可用' }
      finally { this.busy = false }
    },
  },
}
</script>
<style scoped>
.sync-page{height:100%;min-height:0;overflow-y:auto;overscroll-behavior:contain;-webkit-overflow-scrolling:touch;box-sizing:border-box;background:#f5f3fa;color:#262132;padding:24px 20px 48px}.intro{padding:8px 2px 18px}.badge,.label{font-size:12px;letter-spacing:.4px;color:#7354bc}.badge{background:#e9e1fa;border-radius:16px;padding:7px 12px}h1{font-size:28px;line-height:1.3;margin:20px 0 8px}h2{font-size:21px;margin:10px 0}p{font-size:14px;line-height:1.7;color:#746a83;margin:10px 0}.sync-card{padding:22px;background:#fff;border:1px solid #ede7f5;border-radius:24px;margin:14px 0;box-shadow:0 6px 22px #30204406}button{background:#7750cc;color:white;border:0;border-radius:15px;padding:13px 18px;font:inherit;font-size:14px;margin:10px 0;cursor:pointer}button:disabled{opacity:.45}textarea{display:block;box-sizing:border-box;width:100%;height:104px;background:#f8f6fb;border:1px solid #e6dfef;border-radius:14px;padding:12px;font-size:13px;resize:vertical}small{display:block;font-size:12px;line-height:1.6;color:#94879e}.metrics{display:flex;gap:32px;margin:20px 0}.metrics div{display:flex;flex-direction:column;gap:4px}.metrics strong{font-size:28px;color:#7354bc}.metrics span{font-size:12px;color:#8c7e97}.bottom{text-align:center;font-size:11px}
.connection{display:flex;align-items:center;gap:9px;padding:12px 0;border-bottom:1px solid #f1edf7;font-size:13px}.connection i{width:8px;height:8px;background:#c7bdcf;border-radius:50%;flex:none}.connection span{flex:1}.connection strong{font-size:12px;color:#9b879e;font-weight:500}.connection.ready i{background:#2a9b70}.connection.ready strong{color:#238962}h2{overflow-wrap:anywhere}
</style>
