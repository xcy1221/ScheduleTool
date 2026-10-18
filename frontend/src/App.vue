<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import FullCalendar from '@fullcalendar/vue3'
import dayGridPlugin from '@fullcalendar/daygrid'
import timeGridPlugin from '@fullcalendar/timegrid'
import interactionPlugin from '@fullcalendar/interaction'
import luxonPlugin from '@fullcalendar/luxon3'
import zhCn from '@fullcalendar/core/locales/zh-cn'
import type { CalendarOptions, DatesSetArg, EventClickArg, DateSelectArg } from '@fullcalendar/core'
import { DateTime } from 'luxon'
import { api, ApiFailure, setCsrf, type ScheduleEvent, type Session, type Repeat } from './api'
import ThemeToggle from './ThemeToggle.vue'

const zone = 'Asia/Shanghai'
const now = () => DateTime.now().setZone(zone)
const today = now().toISODate()!
const user = ref<Session | null>(null), checking = ref(true), busy = ref(false), loading = ref(false)
const login = reactive({ username: 'admin', password: '' }), loginError = ref(''), error = ref(''), toast = ref('')
const calendar = ref<InstanceType<typeof FullCalendar>>()
const view = ref('dayGridMonth'), heading = ref(''), focusDate = ref(today), jumpDate = ref(today)
const events = ref<ScheduleEvent[]>([]), editor = ref(false), original = ref<ScheduleEvent | null>(null)
const scope = ref<'OCCURRENCE' | 'SERIES'>('OCCURRENCE'), deleteConfirm = ref(false), formError = ref('')
const form = reactive({ title: '', description: '', date: today, time: '09:00', endDate: today, endTime: '10:00', kind: 'point', recurrence: 'NONE' as Repeat, repeatUntil: '', color: '#256f62', version: 0 })
const views = [{ id: 'dayGridMonth', label: '月视图', icon: '▦' }, { id: 'timeGridWeek', label: '周视图', icon: '▥' }, { id: 'timeGridDay', label: '日视图', icon: '▤' }]
const repeatText = { NONE: '不重复', DAILY: '每天重复', WEEKLY: '每周重复' }
const displayDate = computed(() => DateTime.fromISO(focusDate.value).setLocale('zh-CN').toFormat('M月d日 cccc'))
const agenda = computed(() => events.value.filter(e => {
  const start = e.start.slice(0, 10), end = e.end?.slice(0, 10)
  return start === focusDate.value || (start < focusDate.value && !!end && (e.allDay ? end > focusDate.value : e.end! > `${focusDate.value}T00:00:00`))
}).sort((a, b) => Number(b.allDay) - Number(a.allDay) || a.start.localeCompare(b.start)))
function notice(text: string) { toast.value = text; window.setTimeout(() => { toast.value = '' }, 3000) }
function expired() { user.value = null; editor.value = false; events.value = []; setCsrf('') }
async function signIn() {
  busy.value = true; loginError.value = ''
  try { const s = await api<Session>('/api/v1/auth/login', 'POST', login); setCsrf(s.csrfToken); user.value = s; login.password = '' }
  catch (e) { loginError.value = (e as Error).message }
  finally { busy.value = false }
}
async function signOut() {
  try { await api('/api/v1/auth/logout', 'POST'); expired() } catch (e) { error.value = (e as Error).message }
}
const options: CalendarOptions = {
  plugins: [dayGridPlugin, timeGridPlugin, interactionPlugin, luxonPlugin], locale: zhCn, timeZone: zone,
  initialView: 'dayGridMonth', initialDate: today, headerToolbar: false, firstDay: 1, height: '100%',
  nowIndicator: true, selectable: true, dayMaxEvents: 3, weekends: true, allDayText: '全天',
  slotMinTime: '00:00:00', scrollTime: '08:00:00', slotDuration: '00:30:00',
  eventTimeFormat: { hour: '2-digit', minute: '2-digit', hour12: false },
  slotLabelFormat: { hour: '2-digit', minute: '2-digit', hour12: false },
  datesSet(info: DatesSetArg) {
    heading.value = info.view.title; view.value = info.view.type
    const current = DateTime.fromJSDate(info.view.currentStart).setZone(zone)
    const rangeStart = DateTime.fromJSDate(info.start).setZone(zone).toISODate()!
    const rangeEnd = DateTime.fromJSDate(info.end).setZone(zone).toISODate()!
    if (focusDate.value < rangeStart || focusDate.value >= rangeEnd) focusDate.value = current.toISODate()!
    jumpDate.value = current.toISODate()!
  },
  events: async (info, success, failure) => {
    loading.value = true; error.value = ''
    try {
      const from = DateTime.fromJSDate(info.start).setZone(zone).toFormat("yyyy-MM-dd'T'HH:mm:ss")
      const to = DateTime.fromJSDate(info.end).setZone(zone).toFormat("yyyy-MM-dd'T'HH:mm:ss")
      const data = await api<ScheduleEvent[]>(`/api/v1/events?from=${from}&to=${to}`)
      events.value = data
      success(data.map(e => ({ id: e.id, title: e.title, start: e.start, end: e.end, allDay: e.allDay, backgroundColor: e.color, borderColor: e.color, textColor: eventTextColor(e.color), extendedProps: { event: e } })))
    } catch (e) { error.value = (e as Error).message; failure(e as Error) }
    finally { loading.value = false }
  },
  select(info: DateSelectArg) {
    const start = DateTime.fromISO(info.startStr, { zone })
    focusDate.value = start.toISODate()!
    newEvent(start.toISODate()!, info.allDay ? undefined : start.toFormat('HH:mm'))
    if (!info.allDay) { form.kind = 'range'; const end = DateTime.fromISO(info.endStr, { zone }); form.endDate = end.toISODate()!; form.endTime = end.toFormat('HH:mm') }
    calendar.value?.getApi().unselect()
  },
  eventClick(info: EventClickArg) { openEvent(info.event.extendedProps.event as ScheduleEvent) },
  eventDidMount(info) { const e = info.event.extendedProps.event as ScheduleEvent; info.el.title = `${e.title}\n${eventTime(e)}${e.recurrence !== 'NONE' ? '\n' + repeatText[e.recurrence] : ''}${e.description ? '\n' + e.description : ''}` }
}
function changeView(id: string) { calendar.value?.getApi().changeView(id, focusDate.value) }
function move(dir: number) { const c = calendar.value?.getApi(); if (dir < 0) c?.prev(); else c?.next() }
function goToday() { focusDate.value = today; calendar.value?.getApi().today() }
function jump() { if (jumpDate.value) { focusDate.value = jumpDate.value; calendar.value?.getApi().gotoDate(jumpDate.value) } }
function refresh() { calendar.value?.getApi().refetchEvents() }
function newEvent(date = today, time?: string) {
  original.value = null; scope.value = 'SERIES'; deleteConfirm.value = false; formError.value = ''
  Object.assign(form, { title: '', description: '', date, time: time || '09:00', endDate: date, endTime: time ? DateTime.fromISO(`${date}T${time}`).plus({ hours: 1 }).toFormat('HH:mm') : '10:00', kind: 'point', recurrence: 'NONE', repeatUntil: '', color: '#256f62', version: 0 })
  editor.value = true; nextTick(() => document.querySelector<HTMLInputElement>('#event-title')?.focus())
}
function fill(e: ScheduleEvent) {
  Object.assign(form, { title: e.title, description: e.description, date: e.start.slice(0, 10), time: e.start.slice(11, 16),
    endDate: e.end ? (e.allDay ? DateTime.fromISO(e.end).minus({ days: 1 }).toISODate() : e.end.slice(0, 10)) : e.start.slice(0, 10),
    endTime: e.end?.slice(11, 16) || '10:00', kind: e.allDay ? 'allDay' : e.end ? 'range' : 'point', recurrence: e.recurrence, repeatUntil: e.repeatUntil || '', color: e.color, version: e.version })
}
function openEvent(e: ScheduleEvent) { original.value = e; scope.value = e.recurrence === 'NONE' ? 'SERIES' : 'OCCURRENCE'; fill(e); formError.value = ''; deleteConfirm.value = false; editor.value = true }
async function switchScope() {
  if (!original.value) return
  formError.value = ''; busy.value = true
  try {
    if (scope.value === 'SERIES') fill(await api<ScheduleEvent>(`/api/v1/events/${original.value.seriesId}`))
    else { const base = await api<ScheduleEvent>(`/api/v1/events/${original.value.seriesId}`); fill({ ...original.value, version: base.version }) }
  } catch (e) { formError.value = (e as Error).message }
  finally { busy.value = false }
}
function query() { return `scope=${scope.value}&occurrenceDate=${original.value?.occurrenceDate || form.date}` }
async function save() {
  busy.value = true; formError.value = ''
  try {
    const allDay = form.kind === 'allDay'
    const start = `${form.date}T${allDay ? '00:00' : form.time}:00`
    const end = allDay ? `${DateTime.fromISO(form.endDate).plus({ days: 1 }).toISODate()}T00:00:00` : form.kind === 'range' ? `${form.endDate}T${form.endTime}:00` : null
    const payload = { title: form.title, description: form.description, start, end, allDay, timeZone: zone, recurrence: form.recurrence, repeatUntil: form.repeatUntil || null, color: form.color, version: form.version }
    await api(original.value ? `/api/v1/events/${original.value.seriesId}?${query()}` : '/api/v1/events', original.value ? 'PUT' : 'POST', payload)
    editor.value = false; refresh(); notice(original.value ? '日程已更新' : '日程已添加')
  } catch (e) { formError.value = (e as Error).message; if (e instanceof ApiFailure && e.status === 409) refresh() }
  finally { busy.value = false }
}
async function remove() {
  if (!original.value) return
  busy.value = true; formError.value = ''
  try { await api(`/api/v1/events/${original.value.seriesId}?${query()}&version=${form.version}`, 'DELETE'); editor.value = false; refresh(); notice('日程已删除') }
  catch (e) { formError.value = (e as Error).message }
  finally { busy.value = false }
}
function eventTime(e: ScheduleEvent) {
  if (e.allDay) return e.end && DateTime.fromISO(e.end).diff(DateTime.fromISO(e.start), 'days').days > 1 ? `${e.start.slice(5, 10)} 至 ${DateTime.fromISO(e.end).minus({ days: 1 }).toFormat('MM-dd')} · 全天` : '全天'
  const first = e.start.slice(11, 16)
  return !e.end ? `${first} · 时间点` : `${first} — ${e.end.slice(0, 10) !== e.start.slice(0, 10) ? e.end.slice(5, 10) + ' ' : ''}${e.end.slice(11, 16)}`
}
function eventTextColor(color: string) {
  const rgb = [1, 3, 5].map(i => parseInt(color.slice(i, i + 2), 16) / 255)
    .map(c => c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
  return 0.2126 * rgb[0]! + 0.7152 * rgb[1]! + 0.0722 * rgb[2]! > 0.179 ? '#000000' : '#ffffff'
}
function escape(event: KeyboardEvent) { if (event.key === 'Escape' && !busy.value) editor.value = false }
onMounted(async () => {
  window.addEventListener('session-expired', expired); window.addEventListener('keydown', escape)
  try { const s = await api<Session>('/api/v1/auth/me'); user.value = s; setCsrf(s.csrfToken) } catch { /* login is shown for an unauthenticated session */ }
  finally { checking.value = false }
})
onUnmounted(() => { window.removeEventListener('session-expired', expired); window.removeEventListener('keydown', escape) })
</script>

<template>
  <ThemeToggle v-if="!user" class="login-theme" />
  <div v-if="checking" class="boot">正在打开日程…</div>
  <main v-else-if="!user" class="login-page">
    <div class="login-intro"><div class="brand-mark">日</div><span class="eyebrow">SCHEDULETOOL</span><h1>把时间，<br>留给要做的事。</h1><p>一个清楚、安静的地方，<br>安排每一天。</p><div class="intro-bottom">日程 · 你的个人日历</div></div>
    <form class="login-card" @submit.prevent="signIn"><span class="eyebrow">欢迎回来</span><h2>登录你的日程</h2><p>查看安排，开始从容的一天。</p>
      <label>用户名<input v-model="login.username" name="username" autocomplete="username" required maxlength="100"></label>
      <label>密码<input v-model="login.password" name="password" type="password" autocomplete="current-password" required maxlength="200"></label>
      <p v-if="loginError" class="error" role="alert">{{ loginError }}</p><button class="primary login-submit" :disabled="busy">{{ busy ? '正在登录…' : '登录' }} <span>→</span></button><small>个人日程 · Asia/Shanghai</small>
    </form>
  </main>
  <div v-else class="app-shell">
    <aside class="sidebar"><a class="brand" href="./"><span class="brand-mark">日</span><span>日程<small>SCHEDULETOOL</small></span></a>
      <button class="primary add-button" @click="newEvent()"><span>＋</span> 新建日程</button>
      <div class="nav-label">我的日历</div><nav aria-label="日历视图"><button v-for="v in views" :key="v.id" :class="{ active: view === v.id }" @click="changeView(v.id)"><span>{{ v.icon }}</span>{{ v.label }}</button></nav>
      <section class="agenda"><div class="agenda-heading"><span>{{ displayDate }}</span><span class="count">{{ agenda.length }}</span></div>
        <p v-if="!agenda.length" class="empty-agenda">这一天还没有安排。<br><button @click="newEvent(focusDate)">留一点时间给重要的事 ↗</button></p>
        <button v-for="e in agenda" :key="e.id" class="agenda-event" @click="openEvent(e)"><i :style="{ background: e.color }"></i><div><strong>{{ e.title }}</strong><small>{{ eventTime(e) }}</small><small v-if="e.recurrence !== 'NONE'">↻ {{ repeatText[e.recurrence] }}</small></div></button>
      </section>
      <footer><span class="zone-dot"></span> 上海时间 · UTC+8<div><a href="/schedule/api/docs" target="_blank" rel="noopener">API 文档 ↗</a><button @click="signOut">退出登录</button></div></footer>
    </aside>
    <main class="workspace"><header class="page-header"><div><span class="eyebrow">给每件事，留好时间</span><h1>我的日历</h1></div><div class="header-actions"><div class="header-date">{{ now().setLocale('zh-CN').toFormat('yyyy年M月d日 cccc') }}</div><ThemeToggle /></div></header>
      <div class="calendar-card"><div class="toolbar"><div class="date-controls"><h2 :title="heading">{{ heading }}</h2><div class="arrows"><button aria-label="上一页" @click="move(-1)">‹</button><button aria-label="下一页" @click="move(1)">›</button></div><button class="today" @click="goToday">今天</button><input v-model="jumpDate" type="date" aria-label="跳转日期" @change="jump"></div><div class="view-switch"><button v-for="v in views" :key="v.id" :class="{ selected: view === v.id }" @click="changeView(v.id)">{{ v.label[0] }}</button></div></div>
        <div v-if="error" class="banner" role="alert">{{ error }} <button @click="refresh">重试</button></div><div v-if="loading" class="loading-bar" aria-label="正在加载"></div>
        <div class="calendar-wrap"><FullCalendar ref="calendar" :options="options" /></div>
      </div><div class="calendar-hint"><span>点击日期或选取时间，添加安排；点击日程查看和编辑。</span><span>Asia/Shanghai</span></div>
    </main>
    <div v-if="toast" class="toast" role="status">✓ {{ toast }}</div>
    <div v-if="editor" class="modal-backdrop" @mousedown.self="!busy && (editor = false)"><section class="editor" role="dialog" aria-modal="true" aria-labelledby="editor-heading"><header><div><span class="eyebrow">{{ original ? '日程详情' : '安排一件事' }}</span><h2 id="editor-heading">{{ original ? '编辑日程' : '新建日程' }}</h2></div><button class="close" aria-label="关闭" :disabled="busy" @click="editor = false">×</button></header>
      <form @submit.prevent="save"><div class="form-body"><template v-if="original && original.recurrence !== 'NONE'"><label>操作范围<select v-model="scope" :disabled="busy" @change="switchScope"><option value="OCCURRENCE">仅本次 · {{ original.occurrenceDate }}</option><option value="SERIES">整个重复系列</option></select></label><p class="scope-note">{{ scope === 'SERIES' ? '修改整个系列会重置所有已修改或删除的单次安排。下面显示系列最初的时间。' : '仅修改这一次，其他日期与重复规则保持不变。' }}</p></template>
        <label>做什么 <span class="required">*</span><input id="event-title" v-model="form.title" placeholder="给这件事起个名字" required maxlength="200" :disabled="busy"></label>
        <label>时间类型<select v-model="form.kind" :disabled="busy"><option value="point">时间点 · 不设结束时间</option><option value="range">时间段 · 开始与结束</option><option value="allDay">全天 · 按日期安排</option></select></label>
        <div class="form-row"><label>{{ form.kind === 'allDay' ? '开始日期' : '开始' }}<input v-model="form.date" type="date" min="1900-01-01" max="2199-12-31" required :disabled="busy"></label><label v-if="form.kind !== 'allDay'">时间<input v-model="form.time" type="time" required :disabled="busy"></label></div>
        <div v-if="form.kind !== 'point'" class="form-row"><label>{{ form.kind === 'allDay' ? '最后一天（含当天）' : '结束' }}<input v-model="form.endDate" type="date" :min="form.date" max="2199-12-31" required :disabled="busy"></label><label v-if="form.kind === 'range'">时间<input v-model="form.endTime" type="time" required :disabled="busy"></label></div>
        <template v-if="!original || scope === 'SERIES'"><div class="form-row"><label>重复<select v-model="form.recurrence" :disabled="busy"><option value="NONE">不重复</option><option value="DAILY">每天</option><option value="WEEKLY">每周 · 与开始日同一星期</option></select></label><label v-if="form.recurrence !== 'NONE'">截止日期（可选）<input v-model="form.repeatUntil" type="date" :min="form.date" max="2199-12-31" :disabled="busy"></label></div><p v-if="form.recurrence !== 'NONE'" class="field-hint">留空将持续重复；截止日期包含当天。</p></template>
        <label>备注<textarea v-model="form.description" placeholder="需要记住的细节（可选）" rows="3" maxlength="4000" :disabled="busy"></textarea></label>
        <fieldset class="colors"><legend>日程颜色</legend><label v-for="c in ['#256f62', '#477bb0', '#a076b6', '#ca8650', '#ba6072']" :key="c" :style="{ background: c, color: eventTextColor(c) }" :class="{ chosen: form.color === c }"><input v-model="form.color" type="radio" :value="c" :aria-label="'颜色 ' + c" :disabled="busy"><span v-if="form.color === c">✓</span></label></fieldset>
        <p class="field-hint">所有安排均使用上海时间（UTC+8）。</p><p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <div v-if="deleteConfirm" class="delete-confirm"><strong>确定删除{{ original?.recurrence !== 'NONE' ? scope === 'OCCURRENCE' ? '这一次安排' : '整个重复系列' : '这条日程' }}？</strong><p>删除后无法撤销。</p><button type="button" class="danger-solid" :disabled="busy" @click="remove">确认删除</button><button type="button" :disabled="busy" @click="deleteConfirm = false">取消</button></div>
      </div><footer class="editor-actions"><button v-if="original" type="button" class="danger" :disabled="busy" @click="deleteConfirm = !deleteConfirm">删除日程</button><span></span><button type="button" :disabled="busy" @click="editor = false">取消</button><button class="primary" :disabled="busy || deleteConfirm">{{ busy ? '正在保存…' : '保存日程' }}</button></footer></form>
    </section></div>
  </div>
</template>
