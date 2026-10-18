export type Repeat = 'NONE' | 'DAILY' | 'WEEKLY'
export interface ScheduleEvent {
  id: string; seriesId?: string; occurrenceDate?: string; title: string; description: string
  start: string; end?: string; allDay: boolean; timeZone: string; recurrence: Repeat
  repeatUntil?: string; color: string; version: number; exception?: boolean
}
export interface Session { username: string; csrfToken: string; expiresAt: string }
let csrf = ''
export function setCsrf(value: string) { csrf = value }
export class ApiFailure extends Error { constructor(public status: number, message: string) { super(message) } }
export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(`/schedule${path}`, {
    method, credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    if (response.status === 401 && !path.endsWith('/login')) window.dispatchEvent(new Event('session-expired'))
    throw new ApiFailure(response.status, problem.detail || '请求失败，请稍后重试')
  }
  return response.status === 204 ? undefined as T : response.json()
}
