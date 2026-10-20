export type Repeat = 'NONE' | 'DAILY' | 'WEEKLY'
export interface ScheduleEvent {
  id: string; seriesId?: string; occurrenceDate?: string; title: string; description: string
  start: string; end?: string; allDay: boolean; timeZone: string; recurrence: Repeat
  repeatUntil?: string; color: string; version: number; exception?: boolean
  todoId?: string; todoStatus?: TodoStatus
}
export type TodoStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'SHELVED'
export interface Todo { id: string; title: string; description: string; expectedEffect: string; implementationIdea: string; status: TodoStatus; pinned: boolean; version: number }
export interface TodoDetail { todo: Todo; schedules: { event: ScheduleEvent; next?: ScheduleEvent }[]; next?: ScheduleEvent }
export const todoStatusText: Record<TodoStatus, string> = { PENDING: '待处理', IN_PROGRESS: '进行中', COMPLETED: '已完成', SHELVED: '已搁置' }
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
