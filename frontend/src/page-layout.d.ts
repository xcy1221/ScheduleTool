export {};
declare global {
  interface Window { ServerPanelLayout: { create(options: { id: string; legacyKey: string; columns?: number; defaultColumns?: number | 'auto' | 'default'; gap?: number; cardGap?: boolean }): { mount(host: HTMLElement): void; reset(): void; destroy(): void } } }
}
