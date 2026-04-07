import { writable, get } from 'svelte/store';
import { toast } from '$lib/stores/toast';
import { persistedWritable } from './persisted';

// ── Interfaces ────────────────────────────────────────────────────────

export interface Task {
	id: string;
	parent_id: string | null;
	title: string;
	description: string;
	status: 'todo' | 'in_progress' | 'done' | 'blocked';
	priority: 'urgent' | 'high' | 'medium' | 'low';
	due_date: string | null;
	tags: string[];
	recurrence: string | null;
	created_at: string;
	updated_at: string;
	completed_at: string | null;
	subtasks?: Task[];
}

export interface Note {
	id: string;
	title: string;
	content: string;
	tags: string[];
	pinned: boolean;
	created_at: string;
	updated_at: string;
}

export interface TaskFilter {
	status: string;
	priority: string;
	tag: string;
	sort: string;
}

// ── Stores ────────────────────────────────────────────────────────────

export const tasks = writable<Task[]>([]);
export const notes = writable<Note[]>([]);
export const allTags = writable<string[]>([]);

export const activeTab = persistedWritable<'tasks' | 'notes'>('gizmo:tracker:tab', 'tasks');
export const taskFilter = persistedWritable<TaskFilter>('gizmo:tracker:filter', {
	status: '',
	priority: '',
	tag: '',
	sort: 'priority',
});
export const selectedTaskId = writable<string | null>(null);
export const selectedNoteId = writable<string | null>(null);
export const trackerChatOpen = persistedWritable<boolean>('gizmo:tracker:chat', true);

// ── Task API ──────────────────────────────────────────────────────────

export async function loadTasks(filters?: Partial<TaskFilter>) {
	try {
		const params = new URLSearchParams();
		const f = filters || get(taskFilter);
		if (f.status) params.set('status', f.status);
		if (f.priority) params.set('priority', f.priority);
		if (f.tag) params.set('tag', f.tag);
		if (f.sort) params.set('sort', f.sort);
		const qs = params.toString();
		const resp = await fetch(`/api/tracker/tasks${qs ? '?' + qs : ''}`);
		if (resp.ok) {
			const data = await resp.json();
			tasks.set(data.tasks || []);
		}
	} catch {
		toast('Failed to load tasks', 'error');
	}
}

export async function createTask(data: Partial<Task>) {
	try {
		const resp = await fetch('/api/tracker/tasks', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(data),
		});
		if (resp.ok) {
			await loadTasks();
			await loadTags();
		}
	} catch {
		toast('Tracker service unavailable', 'error');
	}
}

export async function updateTask(id: string, data: Partial<Task>) {
	try {
		const resp = await fetch(`/api/tracker/tasks/${id}`, {
			method: 'PATCH',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(data),
		});
		if (resp.ok) {
			await loadTasks();
			await loadTags();
		}
	} catch {
		toast('Tracker service unavailable', 'error');
	}
}

export async function completeTask(id: string) {
	try {
		const resp = await fetch(`/api/tracker/tasks/${id}/complete`, {
			method: 'PATCH',
		});
		if (resp.ok) {
			await loadTasks();
		}
	} catch {
		toast('Tracker service unavailable', 'error');
	}
}

const pendingDeletes = new Map<string, ReturnType<typeof setTimeout>>();

export function deleteTask(id: string) {
	// Optimistic removal from UI
	tasks.update((t) => t.filter((task) => task.id !== id));
	if (get(selectedTaskId) === id) {
		selectedTaskId.set(null);
	}

	// Cancel any existing pending delete for this id
	const existing = pendingDeletes.get(id);
	if (existing) clearTimeout(existing);

	// Schedule actual API delete after 5 seconds
	const timer = setTimeout(async () => {
		pendingDeletes.delete(id);
		try {
			await fetch(`/api/tracker/tasks/${id}`, { method: 'DELETE' });
			await loadTags();
		} catch {
			toast('Failed to delete task', 'error');
			await loadTasks();
		}
	}, 5000);
	pendingDeletes.set(id, timer);

	// Show toast with undo action
	toast('Task deleted', 'info', 5000, {
		label: 'Undo',
		onclick: () => {
			clearTimeout(timer);
			pendingDeletes.delete(id);
			loadTasks();
		},
	});
}

// ── Note API ──────────────────────────────────────────────────────────

export async function loadNotes(filters?: { search?: string; pinned?: boolean }) {
	try {
		const params = new URLSearchParams();
		if (filters?.search) params.set('search', filters.search);
		if (filters?.pinned !== undefined) params.set('pinned', String(filters.pinned));
		const qs = params.toString();
		const resp = await fetch(`/api/tracker/notes${qs ? '?' + qs : ''}`);
		if (resp.ok) {
			const data = await resp.json();
			notes.set(data.notes || []);
		}
	} catch {
		toast('Tracker service unavailable', 'error');
	}
}

export async function createNote(data: Partial<Note>) {
	try {
		const resp = await fetch('/api/tracker/notes', {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(data),
		});
		if (resp.ok) {
			await loadNotes();
			await loadTags();
		}
	} catch {
		toast('Tracker service unavailable', 'error');
	}
}

export async function updateNote(id: string, data: Partial<Note>) {
	try {
		const resp = await fetch(`/api/tracker/notes/${id}`, {
			method: 'PATCH',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(data),
		});
		if (resp.ok) {
			await loadNotes();
			await loadTags();
		}
	} catch {
		toast('Tracker service unavailable', 'error');
	}
}

export async function deleteNote(id: string) {
	try {
		const resp = await fetch(`/api/tracker/notes/${id}`, {
			method: 'DELETE',
		});
		if (resp.ok) {
			notes.update((n) => n.filter((note) => note.id !== id));
			if (get(selectedNoteId) === id) {
				selectedNoteId.set(null);
			}
			await loadTags();
		}
	} catch {
		toast('Tracker service unavailable', 'error');
	}
}

// ── Tags API ──────────────────────────────────────────────────────────

export async function loadTags() {
	try {
		const resp = await fetch('/api/tracker/tags');
		if (resp.ok) {
			const data = await resp.json();
			allTags.set(data.tags || []);
		}
	} catch {
		toast('Tracker service unavailable', 'error');
	}
}
