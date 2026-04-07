/**
 * Fetch with an automatic AbortController timeout.
 * Prevents hung requests from permanently disabling UI state.
 */
export async function fetchWithTimeout(
	url: string,
	init: RequestInit,
	timeoutMs: number = 60000
): Promise<Response> {
	const ctrl = new AbortController();
	const tid = setTimeout(() => ctrl.abort(), timeoutMs);
	try {
		return await fetch(url, { ...init, signal: ctrl.signal });
	} finally {
		clearTimeout(tid);
	}
}
