import { reactive, watch } from 'vue'

type Layout = { density: 'comfortable' | 'compact'; slotMinutes: '15' | '30' | '60' }
const layoutKey = 'scheduletool-layout-v1'
const defaultLayout: Layout = { density: 'comfortable', slotMinutes: '30' }
function readLayout(): Layout {
  try {
    const value = JSON.parse(localStorage.getItem(layoutKey) || 'null')
    return { density: value?.density === 'compact' ? 'compact' : 'comfortable',
      slotMinutes: ['15','30','60'].includes(value?.slotMinutes) ? value.slotMinutes : '30' }
  } catch { return { ...defaultLayout } }
}
export const layout = reactive(readLayout())
function applyLayout() { Object.assign(document.documentElement.dataset, { scheduleDensity: layout.density }) }
export function slotDuration() { return layout.slotMinutes === '60' ? '01:00:00' : '00:' + layout.slotMinutes + ':00' }
export function resetLayout() { Object.assign(layout, defaultLayout) }
watch(layout, () => { applyLayout(); try { localStorage.setItem(layoutKey, JSON.stringify(layout)) } catch {} }, { deep: true })
window.addEventListener('storage', event => { if (event.key === layoutKey || event.key === null) Object.assign(layout, readLayout()) })
applyLayout()
