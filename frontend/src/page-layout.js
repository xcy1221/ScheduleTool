(() => {
  'use strict';
  const prefix = 'serverpanel-page-layout-v1:';
  function number(value, fallback, min, max) {
    if (!['number', 'string'].includes(typeof value) || String(value).trim() === '') return fallback;
    const n = Number(value);
    return Number.isFinite(n) ? Math.min(max, Math.max(min, Math.round(n))) : fallback;
  }
  function normalize(value, options) {
    const v = value && typeof value === 'object' ? value : {};
    const density = v.density === 'compact' ? 75 : v.density === 'comfortable' ? 100 : v.density;
    const columns = v.columns === 'split' ? 2 : v.columns === 'stack' ? 1 : v.columns;
    return {
      density: number(density, 100, 60, 150),
      columns: ['auto', 'default'].includes(columns) ? columns : number(columns, options.defaultColumns ?? 'default', 1, options.columns || 12),
      insetX: number(v.insetX, null, 0, 20),
      insetY: number(v.insetY, null, 0, 120),
      gap: number(v.gap, null, 0, 64)
    };
  }
  function create(options) {
    const root = document.documentElement, key = prefix + options.id;
    let host, failed = false;
    function read() {
      try {
        const saved = localStorage.getItem(key);
        const value = JSON.parse(saved ?? localStorage.getItem(options.legacyKey) ?? 'null');
        if (saved === null && value?.columns === 'auto') value.columns = 'default';
        return normalize(value, options);
      } catch { return normalize(null, options); }
    }
    let layout = read();
    function optional(name, value, unit) {
      if (value === null) root.style.removeProperty(name);
      else root.style.setProperty(name, value + unit);
    }
    function apply() {
      root.dataset.layoutPage = options.id;
      root.dataset.layoutColumns = String(layout.columns);
      for (const [field, attr] of [['insetX', 'layoutInsetX'], ['insetY', 'layoutInsetY']]) {
        if (layout[field] === null) delete root.dataset[attr]; else root.dataset[attr] = 'custom';
      }
      root.style.setProperty('--page-density', String(layout.density / 100));
      root.style.setProperty('--page-gap', layout.gap === null ? `calc(${options.gap || 20}px * var(--page-density))` : layout.gap + 'px');
      root.style.setProperty('--page-columns', String(['auto', 'default'].includes(layout.columns) ? 1 : layout.columns));
      root.style.setProperty('--page-card-track', layout.columns === 'auto' ? 'var(--page-card-auto, 240px)' : 'max(var(--page-card-min, 180px), calc((100% - (var(--page-columns) - 1) * var(--page-gap)) / var(--page-columns)))');
      optional('--page-inset-x', layout.insetX, '%');
      optional('--page-inset-y', layout.insetY, 'px');
      window.dispatchEvent(new CustomEvent('serverpanel-layout-change', { detail: { page: options.id } }));
    }
    function sync() {
      if (!host) return;
      host.querySelectorAll('[data-field]').forEach(input => {
        const field = input.dataset.field, auto = layout[field] === null;
        if (input.type === 'checkbox') input.checked = !auto;
        else {
          input.disabled = auto;
          input.value = String(layout[field] ?? ({ insetX: 2, insetY: 32, gap: 20 }[field]));
        }
      });
      const columns = host.querySelector('[data-columns]');
      if (columns) columns.value = String(layout.columns);
      host.querySelector('[role=status]').textContent = failed ? '浏览器未允许保存，本次调整仍然生效。' : '';
    }
    function save() {
      try { localStorage.setItem(key, JSON.stringify(layout)); } catch { failed = true; }
      apply(); sync();
    }
    function row(field, title, min, max, unit, custom) {
      return `<div class="sp-layout-row"><div class="sp-layout-label"><span>${title} <small>${unit}</small></span>${custom ? `<label><input type="checkbox" data-field="${field}" aria-label="自定义${title}">自定义</label>` : ''}</div><div class="sp-layout-inputs"><input type="range" min="${min}" max="${max}" step="1" data-field="${field}" aria-label="${title}滑块"><input type="number" min="${min}" max="${max}" step="1" data-field="${field}" aria-label="${title}数值"></div></div>`;
    }
    function mount(element) {
      host = element;
      host.classList.add('sp-layout');
      host.innerHTML = `<style>
        .sp-layout{display:grid;gap:18px;margin:20px 0;color:var(--text);font:12px/1.6 system-ui,sans-serif}
        .sp-layout *{box-sizing:border-box}.sp-layout .sp-layout-label{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:6px}
        .sp-layout .sp-layout-label label{display:flex;flex-direction:row;align-items:center;gap:5px;margin:0;font-size:11px;color:var(--muted);white-space:nowrap}
        .sp-layout small,.sp-layout p{color:var(--muted);font-size:11px}.sp-layout p{margin:0;line-height:1.7}
        .sp-layout .sp-layout-inputs{display:grid;grid-template-columns:minmax(0,1fr) 68px;gap:12px;align-items:center}
        .sp-layout input[type=range]{width:100%;min-width:0;padding:0;margin:0;accent-color:var(--accent);height:24px;border:0;background:transparent}
        .sp-layout input[type=checkbox]{width:14px;height:14px;padding:0;margin:0;accent-color:var(--accent)}
        .sp-layout input[type=number],.sp-layout select{width:100%;min-width:0;padding:6px 8px;border:1px solid var(--line);border-radius:6px;background:var(--input,var(--surface,var(--panel)));color:var(--text);font:inherit}
        .sp-layout input:disabled{opacity:.4}.sp-layout input:focus-visible,.sp-layout select:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
        .sp-layout .sp-layout-presets{display:flex;gap:7px}.sp-layout .sp-layout-presets button{font:inherit;min-height:30px;padding:5px 10px;border:1px solid var(--line);border-radius:6px;color:var(--text);background:var(--surface,var(--panel))}
        .sp-layout [role=status]:empty{display:none}
      </style><p>本页布局独立保存，调整不会影响其他页面。</p>
      ${options.columns ? `<div><div class="sp-layout-label"><span>卡片列数</span></div><select data-columns aria-label="卡片列数"><option value="default">页面默认</option><option value="auto">自动适应</option>${Array.from({ length: options.columns }, (_, i) => `<option value="${i + 1}">${i + 1} 列</option>`).join('')}</select></div>` : ''}
      ${row('insetX', '左右页边距', 0, 20, '%', true)}${row('insetY', '上下页边距', 0, 120, 'px', true)}
      ${row('density', '内容密度', 60, 150, '%', false)}<div class="sp-layout-presets"><button type="button" data-density="75">紧凑</button><button type="button" data-density="100">标准</button><button type="button" data-density="125">宽松</button></div>
      ${options.cardGap === false ? '' : row('gap', '卡片间距', 0, 64, 'px', true)}
      <p>未开启自定义时沿用默认留白。密度调整内部间距，不缩放文字；列数会随可用空间减少，避免卡片过窄。</p><p role="status" aria-live="polite"></p>`;
      function update(event) {
        const input = event.target;
        if (input.dataset.columns !== undefined) layout.columns = input.value;
        else if (input.dataset.field) {
          const field = input.dataset.field;
          if (input.type === 'checkbox') layout[field] = input.checked ? ({ insetX: 2, insetY: 32, gap: 20 }[field]) : null;
          else if (input.value !== '' && input.validity.valid) layout[field] = Number(input.value);
          else { if (event.type === 'change') sync(); return; }
        } else return;
        layout = normalize(layout, options); save();
      }
      host.oninput = update;
      host.onchange = update;
      host.onclick = event => {
        const button = event.target.closest('button[data-density]');
        if (button) { layout.density = Number(button.dataset.density); save(); }
      };
      sync();
    }
    function storage(event) { if (event.key === key || event.key === null) { layout = read(); apply(); sync(); } }
    window.addEventListener('storage', storage);
    apply();
    return {
      mount,
      reset() { layout = normalize(null, options); save(); },
      destroy() { window.removeEventListener('storage', storage); host = null; }
    };
  }
  window.ServerPanelLayout = { create };
})();
