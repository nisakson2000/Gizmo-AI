import { get } from 'svelte/store';
import { soundsEnabled } from '$lib/stores/sounds';
import { theme } from '$lib/stores/theme';

type Era = '8bit' | '3d' | 'modern' | 'none';

function getEra(): Era {
	const t = get(theme);
	switch (t) {
		case 'nes': case 'snes': case 'gba': return '8bit';
		case 'n64': case 'gamecube': return '3d';
		case 'wii': case 'switch': case 'ds': case '3ds': return 'modern';
		default: return 'none';
	}
}

function playTone(freq: number, duration: number, type: OscillatorType, volume = 0.06) {
	if (!get(soundsEnabled)) return;
	if (getEra() === 'none') return;
	try {
		const ctx = new AudioContext();
		const osc = ctx.createOscillator();
		const gain = ctx.createGain();
		osc.type = type;
		osc.frequency.value = freq;
		gain.gain.value = volume;
		osc.connect(gain).connect(ctx.destination);
		gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration);
		osc.start();
		osc.stop(ctx.currentTime + duration);
	} catch {
		// AudioContext not available
	}
}

function playArpeggio(freqs: number[], gap: number, type: OscillatorType, volume = 0.06) {
	if (!get(soundsEnabled)) return;
	if (getEra() === 'none') return;
	freqs.forEach((f, i) => {
		setTimeout(() => playTone(f, gap * 1.5, type, volume), i * gap * 1000);
	});
}

export function playSelect() {
	const era = getEra();
	if (era === '8bit') playTone(880, 0.08, 'square', 0.05);
	else if (era === '3d') playTone(660, 0.1, 'square', 0.04);
	else if (era === 'modern') playTone(800, 0.06, 'sine', 0.05);
}

export function playConfirm() {
	const era = getEra();
	if (era === '8bit') playArpeggio([440, 660, 880], 0.05, 'square', 0.04);
	else if (era === '3d') playTone(700, 0.15, 'sine', 0.04);
	else if (era === 'modern') playTone(1000, 0.05, 'sine', 0.04);
}

export function playCancel() {
	const era = getEra();
	if (era === '8bit') playTone(220, 0.12, 'square', 0.04);
	else if (era === '3d') playTone(300, 0.1, 'sine', 0.04);
	else if (era === 'modern') playTone(400, 0.08, 'sine', 0.04);
}

export function playError() {
	const era = getEra();
	if (era === '8bit') playTone(150, 0.2, 'square', 0.05);
	else if (era === '3d') playTone(200, 0.2, 'triangle', 0.05);
	else if (era === 'modern') playTone(300, 0.15, 'sine', 0.04);
}

export function playNavigate() {
	const era = getEra();
	if (era === '8bit') playTone(660, 0.05, 'square', 0.03);
	else if (era === '3d') playTone(500, 0.06, 'sine', 0.03);
	else if (era === 'modern') playTone(700, 0.04, 'sine', 0.03);
}
