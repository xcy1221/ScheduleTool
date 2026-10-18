<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, useId } from 'vue'
import { theme, tone, tones, setTheme, setTone } from './theme'

const id = useId(), open = ref(false)
const control = ref<HTMLElement>(), trigger = ref<HTMLButtonElement>()
function close(restoreFocus = false) {
  open.value = false
  if (restoreFocus) trigger.value?.focus()
}
async function toggle() {
  if (open.value) { close(); return }
  open.value = true
  await nextTick()
  control.value?.querySelector<HTMLInputElement>('input:checked')?.focus()
}
function outside(event: PointerEvent) {
  if (event.target instanceof Node && !control.value?.contains(event.target)) close()
}
function focusLeft(event: FocusEvent) {
  if (event.relatedTarget instanceof Node && !control.value?.contains(event.relatedTarget)) close()
}
onMounted(() => document.addEventListener('pointerdown', outside))
onUnmounted(() => document.removeEventListener('pointerdown', outside))
</script>

<template>
  <div ref="control" class="appearance-control" @keydown.esc.stop.prevent="close(true)" @focusout="focusLeft">
    <button ref="trigger" class="appearance-trigger" type="button" aria-haspopup="dialog" :aria-expanded="open" :aria-controls="id" @click="toggle">
      <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><circle cx="12" cy="12" r="8" /><path d="M12 4a8 8 0 0 0 0 16Z" fill="currentColor" stroke="none" /></svg>
      外观 <span aria-hidden="true" class="appearance-chevron">⌄</span>
    </button>
    <section v-if="open" :id="id" class="appearance-panel" role="dialog" aria-label="外观设置">
      <header><h2>外观设置</h2><button type="button" aria-label="关闭外观设置" @click="close(true)">×</button></header>
      <fieldset><legend>界面明暗</legend><div class="appearance-modes">
        <label :class="{ selected: theme === 'light' }"><input type="radio" :name="`${id}-mode`" value="light" :checked="theme === 'light'" @change="setTheme('light')"><span>浅色</span></label>
        <label :class="{ selected: theme === 'dark' }"><input type="radio" :name="`${id}-mode`" value="dark" :checked="theme === 'dark'" @change="setTheme('dark')"><span>深色</span></label>
      </div></fieldset>
      <fieldset><legend>界面色调</legend><div class="appearance-tones">
        <label v-for="item in tones" :key="item.value" :class="{ selected: tone === item.value }">
          <input type="radio" :name="`${id}-tone`" :value="item.value" :checked="tone === item.value" @change="setTone(item.value)">
          <span class="tone-swatch" :style="{ background: item.swatch }" aria-hidden="true">{{ tone === item.value ? '✓' : '' }}</span><span>{{ item.label }}</span>
        </label>
      </div></fieldset>
    </section>
  </div>
</template>
