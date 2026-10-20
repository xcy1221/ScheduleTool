<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api, ApiFailure, todoStatusText, type Todo, type TodoDetail, type TodoStatus, type TodoFilter, type ScheduleEvent } from './api'
const props = defineProps<{ items: TodoDetail[]; selected: string; loading: boolean; filter: TodoFilter }>()
const emit = defineEmits<{ select: [id: string]; changed: []; schedule: [todo: Todo]; calendar: [event: ScheduleEvent]; 'update:filter': [filter: TodoFilter] }>()
const q = ref(''), quick = ref(''), busy = ref(false), error = ref(''), deleting = ref(false), creating = ref(false)
const filter = computed({ get: () => props.filter, set: value => emit('update:filter', value) })
const draft = reactive({ title: '', description: '', expectedEffect: '', implementationIdea: '', status: 'PENDING' as TodoStatus, pinned: false, version: 0 })
const detail = computed(() => props.items.find(d => d.todo.id === props.selected))
const visible = computed(() => props.items.filter(d => {
  const t = d.todo
  return (filter.value === 'ALL' || (filter.value === 'OPEN' ? t.status !== 'COMPLETED' : filter.value === 'UNSCHEDULED' ? t.status !== 'COMPLETED' && !d.next : t.status === filter.value)) &&
    `${t.title}\n${t.description}\n${t.expectedEffect}\n${t.implementationIdea}`.toLocaleLowerCase().includes(q.value.toLocaleLowerCase())
}).sort((a, b) => Number(b.todo.pinned) - Number(a.todo.pinned)))
watch(() => props.selected, id => { if (id) creating.value = false; deleting.value = false; error.value = ''; if (detail.value) Object.assign(draft, detail.value.todo) }, { immediate: true })
function begin() { creating.value = true; emit('select', ''); Object.assign(draft, { title: '', description: '', expectedEffect: '', implementationIdea: '', status: 'PENDING', pinned: false, version: 0 }); error.value = '' }
defineExpose({ begin })
async function createQuick() {
  if (!quick.value.trim() || busy.value) return
  busy.value = true; error.value = ''
  try { const t = await api<Todo>('/api/v1/todos', 'POST', { title: quick.value }); quick.value = ''; filter.value = 'OPEN'; emit('changed'); emit('select', t.id) }
  catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function save() {
  busy.value = true; error.value = ''
  try {
    const t = await api<Todo>(creating.value ? '/api/v1/todos' : `/api/v1/todos/${props.selected}`, creating.value ? 'POST' : 'PUT', draft)
    creating.value = false; emit('changed'); emit('select', t.id)
  } catch (e) { error.value = (e as Error).message; if (e instanceof ApiFailure && e.status === 409) emit('changed') }
  finally { busy.value = false }
}
watch(detail, (d) => { if (d && !error.value) Object.assign(draft, d.todo) })
async function remove(mode: string) {
  if (!detail.value) return
  busy.value = true; error.value = ''
  try { await api(`/api/v1/todos/${props.selected}?version=${draft.version}&mode=${mode}`, 'DELETE'); emit('select', ''); emit('changed') }
  catch (e) { error.value = (e as Error).message; if (e instanceof ApiFailure && e.status === 409) emit('changed') }
  finally { busy.value = false }
}
function reloadDraft() { if (detail.value) Object.assign(draft, detail.value.todo); error.value = ''; deleting.value = false }
</script>

<template>
  <section class="todo-workspace" :class="{ 'has-detail': selected || creating }">
    <div class="todo-list-pane">
      <form class="quick-todo" @submit.prevent="createQuick"><input v-model="quick" aria-label="一句话创建待办" placeholder="记下一件事或一个想法…" maxlength="200" required><button class="primary" :disabled="busy">添加</button></form>
      <div class="todo-filters"><input v-model="q" type="search" aria-label="搜索待办" placeholder="搜索标题、说明与方案"><select v-model="filter" aria-label="待办筛选" @change="emit('select', '')"><option value="OPEN">未完成</option><option value="UNSCHEDULED">尚未排期</option><option value="ALL">全部事项</option><option v-for="(label, value) in todoStatusText" :value="value" :key="value">{{ label }}</option></select></div>
      <p v-if="loading">正在加载待办…</p><p v-else-if="!visible.length" class="empty-agenda">这里还没有事项。</p>
      <button v-for="d in visible" :key="d.todo.id" class="todo-row" :class="{ selected: selected === d.todo.id, completed: d.todo.status === 'COMPLETED' }" @click="emit('select', d.todo.id)"><strong>{{ d.todo.pinned ? '⌃ ' : '' }}{{ d.todo.title }}</strong><span class="todo-meta">{{ todoStatusText[d.todo.status] }} · {{ d.schedules.length }} 条日程</span><small>{{ d.next ? `下次 ${d.next.start.replace('T', ' ').slice(0, 16)}` : '暂无有效安排' }}</small></button>
    </div>
    <div class="todo-detail-pane">
      <template v-if="detail || creating">
        <div class="todo-detail-toolbar"><button @click="emit('select', ''); creating = false">‹ 返回列表</button><button v-if="detail" :disabled="busy" @click="emit('schedule', detail.todo)">＋ 安排日程</button></div>
        <form @submit.prevent="save">
          <label>标题 <span class="required">*</span><input v-model="draft.title" aria-label="待办标题" required maxlength="200"></label>
          <div class="form-row"><label>状态<select v-model="draft.status" aria-label="待办状态"><option v-for="(label, value) in todoStatusText" :value="value" :key="value">{{ label }}</option></select></label><label class="pin-control"><input v-model="draft.pinned" type="checkbox">置顶</label></div>
          <label>详细说明<textarea v-model="draft.description" maxlength="4000" rows="3" placeholder="这件事的背景与细节（可选）"></textarea></label>
          <label>预期效果<textarea v-model="draft.expectedEffect" maxlength="4000" rows="2" placeholder="希望达成什么（可选）"></textarea></label>
          <label>实现思路<textarea v-model="draft.implementationIdea" maxlength="4000" rows="3" placeholder="准备怎样推进（可选）"></textarea></label>
          <p class="field-hint">待办记录整体目标；日程备注记录某次执行。日程结束不会自动完成待办。</p>
          <div class="todo-detail-actions"><button class="primary" :disabled="busy || deleting">{{ busy ? '处理中…' : '保存待办' }}</button><button v-if="detail" type="button" @click="reloadDraft">重新读取</button><button v-if="detail" type="button" class="danger" :disabled="busy" @click="deleting = !deleting">删除待办</button></div>
        </form>
        <section v-if="detail" class="todo-schedules"><h3>关联日程 · {{ detail.schedules.length }}</h3><p class="field-hint">{{ detail.next ? `下次有效安排：${detail.next.start.replace('T', ' ').slice(0, 16)}` : '暂无后续或进行中的有效安排' }}</p><button v-for="s in detail.schedules" :key="s.event.id" class="todo-row" @click="emit('calendar', s.next || s.event)"><strong>{{ s.event.title }} ↗</strong><small>{{ s.next ? s.next.start.replace('T', ' ').slice(0, 16) : '该系列暂无有效安排' }} · {{ s.event.recurrence === 'NONE' ? '单次' : s.event.recurrence === 'DAILY' ? '每天' : '每周' }}</small></button></section>
        <div v-if="deleting" class="delete-confirm" role="alert"><strong>确认删除“{{ detail?.todo.title }}”？</strong><p>影响 {{ detail?.schedules.length || 0 }} 个关联日程系列及各自的全部单次例外。保留日程会解除关联；一并删除会移除这些系列的所有日期安排。此操作无法撤销。</p><button :disabled="busy" @click="remove('KEEP_EVENTS')">保留日程并解除关联</button><button class="danger-solid" :disabled="busy" @click="remove('DELETE_EVENTS')">一并删除关联日程</button><button :disabled="busy" @click="deleting = false">取消</button></div>
      </template><p v-else class="todo-placeholder">选择一件事，记下目标和思路。</p>
    </div>
    <p v-if="error" class="error todo-error" role="alert">{{ error }}<button @click="reloadDraft">读取最新版本</button></p>
  </section>
</template>
