import { ref } from 'vue'

export type Theme = 'light' | 'dark'
export const tones = [
  { value: 'green', label: '淡绿色', swatch: '#8db8a1' },
  { value: 'blue', label: '淡蓝色', swatch: '#90b2d1' },
  { value: 'red', label: '淡红色', swatch: '#d69b91' },
  { value: 'pink', label: '淡粉色', swatch: '#d7a0ba' },
  { value: 'purple', label: '淡紫色', swatch: '#b5a1cf' }
] as const
export type Tone = typeof tones[number]['value']
const isTone = (value: unknown): value is Tone => tones.some(t => t.value === value)
const initialTone = document.documentElement.dataset.tone
export const theme = ref<Theme>(document.documentElement.dataset.theme === 'light' ? 'light' : 'dark')
export const tone = ref<Tone>(isTone(initialTone) ? initialTone : 'green')

function applyAppearance() {
  const root = document.documentElement
  root.dataset.theme = theme.value
  root.dataset.tone = tone.value
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', getComputedStyle(root).backgroundColor)
}

export function setTheme(value: Theme) {
  theme.value = value
  applyAppearance()
  try { localStorage.setItem('scheduletool-theme', value) } catch { /* 存储不可用时仍可使用当前选择。 */ }
}

export function setTone(value: Tone) {
  tone.value = value
  applyAppearance()
  try { localStorage.setItem('scheduletool-tone', value) } catch { /* 存储不可用时仍可使用当前选择。 */ }
}

window.addEventListener('storage', event => {
  if (event.key === 'scheduletool-theme') theme.value = event.newValue === 'light' ? 'light' : 'dark'
  else if (event.key === 'scheduletool-tone') tone.value = isTone(event.newValue) ? event.newValue : 'green'
  else return
  applyAppearance()
})
