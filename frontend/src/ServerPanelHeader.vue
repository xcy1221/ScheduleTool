<script setup lang="ts">
import ThemeToggle from './ThemeToggle.vue'
</script>
<template>
  <header class="server-panel-header" aria-label="Server Panel 通用顶部栏">
    <a class="server-panel-brand" href="/server-readme/" target="_top"><span>HOMESERVER ·</span><strong>ScheduleTool</strong></a>
    <a class="server-panel-return" href="/server-readme/" target="_top">←返回server panel</a>
    <ThemeToggle />
  </header>
</template>
