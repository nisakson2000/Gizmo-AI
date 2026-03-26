import { get } from 'svelte/store';
import { soundsEnabled } from '$lib/stores/sounds';
import { theme } from '$lib/stores/theme';
import type { ThemeName } from '$lib/stores/theme';

// ─── Single AudioContext (lazy-init) ────────────────────────────────────────

let ctx: AudioContext | null = null;

function getCtx(): AudioContext {
	if (!ctx) ctx = new AudioContext();
	if (ctx.state === 'suspended') ctx.resume();
	return ctx;
}

// ─── Synth Engine ───────────────────────────────────────────────────────────

interface ToneSpec {
	freq: number;
	type: OscillatorType;
	duration: number;
	volume?: number;
	attack?: number;
	decay?: number;
	pitchBend?: number;
	detune?: number;
	delayTime?: number;
	delayFeedback?: number;
}

type SoundDef = ToneSpec | ToneSpec[];

function playTone(spec: ToneSpec, startOffset = 0): void {
	try {
		const c = getCtx();
		const now = c.currentTime + startOffset;
		const vol = spec.volume ?? 0.06;
		const attack = spec.attack ?? 0.005;
		const decay = spec.decay ?? spec.duration;

		const gain = c.createGain();
		gain.gain.setValueAtTime(0, now);
		gain.gain.linearRampToValueAtTime(vol, now + attack);
		gain.gain.exponentialRampToValueAtTime(0.001, now + decay);

		let destination: AudioNode = c.destination;

		// Delay effect
		if (spec.delayTime && spec.delayTime > 0) {
			const delay = c.createDelay(1);
			delay.delayTime.value = spec.delayTime;
			const fb = c.createGain();
			fb.gain.value = spec.delayFeedback ?? 0;
			const dry = c.createGain();
			dry.gain.value = 1;
			delay.connect(fb).connect(delay);
			gain.connect(dry).connect(c.destination);
			gain.connect(delay).connect(c.destination);
			// Don't connect gain -> destination again
			destination = gain; // already routed
			// Schedule cleanup
			const cleanup = now + spec.duration + 2;
			setTimeout(() => {
				try { delay.disconnect(); fb.disconnect(); dry.disconnect(); } catch {}
			}, (cleanup - c.currentTime) * 1000 + 100);
		} else {
			gain.connect(destination);
		}

		// Primary oscillator
		const osc = c.createOscillator();
		osc.type = spec.type;
		osc.frequency.setValueAtTime(spec.freq, now);
		if (spec.pitchBend) {
			osc.frequency.linearRampToValueAtTime(spec.freq + spec.pitchBend, now + spec.duration);
		}
		osc.connect(gain);
		osc.start(now);
		osc.stop(now + spec.duration + 0.05);

		// Dual oscillator for warmth (detune)
		if (spec.detune && spec.detune > 0) {
			const osc2 = c.createOscillator();
			osc2.type = spec.type;
			osc2.frequency.setValueAtTime(spec.freq, now);
			osc2.detune.value = spec.detune;
			if (spec.pitchBend) {
				osc2.frequency.linearRampToValueAtTime(spec.freq + spec.pitchBend, now + spec.duration);
			}
			osc2.connect(gain);
			osc2.start(now);
			osc2.stop(now + spec.duration + 0.05);
		}
	} catch {
		// AudioContext not available
	}
}

function playSoundDef(def: SoundDef): void {
	if (Array.isArray(def)) {
		let offset = 0;
		for (const tone of def) {
			playTone(tone, offset);
			offset += tone.duration;
		}
	} else {
		playTone(def);
	}
}

// ─── Per-Console Sound Definitions ──────────────────────────────────────────

type SoundType = 'select' | 'confirm' | 'cancel' | 'error' | 'navigate' | 'boot';
type ConsoleSounds = Record<SoundType, SoundDef>;

const consoleSounds: Record<Exclude<ThemeName, 'default'>, ConsoleSounds> = {

	// NES — Harsh, clipped, dry. Pure square wave, no effects.
	nes: {
		select:   { freq: 880, type: 'square', duration: 0.06, volume: 0.05 },
		confirm:  [
			{ freq: 440, type: 'square', duration: 0.04, volume: 0.04 },
			{ freq: 660, type: 'square', duration: 0.04, volume: 0.04 },
			{ freq: 880, type: 'square', duration: 0.04, volume: 0.04 },
		],
		cancel:   { freq: 220, type: 'square', duration: 0.1, volume: 0.04 },
		error:    { freq: 150, type: 'square', duration: 0.18, volume: 0.05 },
		navigate: { freq: 660, type: 'square', duration: 0.04, volume: 0.03 },
		boot:     [
			{ freq: 330, type: 'square', duration: 0.08, volume: 0.06 },
			{ freq: 440, type: 'square', duration: 0.08, volume: 0.06 },
		],
	},

	// SNES — Warmer, with echo. Triangle/square blend via detune, slight delay.
	snes: {
		select:   { freq: 700, type: 'triangle', duration: 0.1, volume: 0.05, detune: 8, delayTime: 0.12, delayFeedback: 0.15 },
		confirm:  [
			{ freq: 440, type: 'triangle', duration: 0.06, volume: 0.04, delayTime: 0.1, delayFeedback: 0.12 },
			{ freq: 554, type: 'triangle', duration: 0.06, volume: 0.04, delayTime: 0.1, delayFeedback: 0.12 },
			{ freq: 660, type: 'triangle', duration: 0.06, volume: 0.04, delayTime: 0.1, delayFeedback: 0.12 },
		],
		cancel:   { freq: 260, type: 'triangle', duration: 0.12, volume: 0.04, delayTime: 0.1, delayFeedback: 0.1 },
		error:    { freq: 180, type: 'square', duration: 0.2, volume: 0.05 },
		navigate: { freq: 580, type: 'triangle', duration: 0.06, volume: 0.03, delayTime: 0.08, delayFeedback: 0.1 },
		boot:     [
			{ freq: 330, type: 'triangle', duration: 0.07, volume: 0.05, delayTime: 0.15, delayFeedback: 0.2 },
			{ freq: 440, type: 'triangle', duration: 0.07, volume: 0.05, delayTime: 0.15, delayFeedback: 0.2 },
			{ freq: 554, type: 'triangle', duration: 0.07, volume: 0.05, delayTime: 0.15, delayFeedback: 0.2 },
			{ freq: 660, type: 'triangle', duration: 0.07, volume: 0.05, delayTime: 0.15, delayFeedback: 0.2 },
		],
	},

	// GBA — Bright, small, tinny. Like NES through a tiny speaker.
	gba: {
		select:   { freq: 1000, type: 'square', duration: 0.05, volume: 0.04 },
		confirm:  [
			{ freq: 660, type: 'square', duration: 0.05, volume: 0.04 },
			{ freq: 880, type: 'square', duration: 0.05, volume: 0.04 },
		],
		cancel:   { freq: 330, type: 'square', duration: 0.08, volume: 0.04 },
		error:    { freq: 200, type: 'square', duration: 0.15, volume: 0.05 },
		navigate: { freq: 780, type: 'square', duration: 0.035, volume: 0.03 },
		boot:     [
			{ freq: 523, type: 'square', duration: 0.1, volume: 0.06 },
			{ freq: 659, type: 'square', duration: 0.1, volume: 0.06 },
		],
	},

	// N64 — Warm, bassy, slight pitch bend. Sine waves with gentle attack.
	n64: {
		select:   { freq: 500, type: 'sine', duration: 0.12, volume: 0.05, attack: 0.02, pitchBend: 30 },
		confirm:  { freq: 600, type: 'sine', duration: 0.15, volume: 0.05, attack: 0.025, pitchBend: 50 },
		cancel:   { freq: 280, type: 'sine', duration: 0.12, volume: 0.04, pitchBend: -20 },
		error:    { freq: 180, type: 'sine', duration: 0.25, volume: 0.05, attack: 0.03 },
		navigate: { freq: 440, type: 'sine', duration: 0.08, volume: 0.03, attack: 0.015, pitchBend: 20 },
		boot:     [
			{ freq: 330, type: 'sine', duration: 0.12, volume: 0.06, attack: 0.03, pitchBend: 30 },
			{ freq: 440, type: 'sine', duration: 0.12, volume: 0.06, attack: 0.03, pitchBend: 30 },
			{ freq: 554, type: 'sine', duration: 0.12, volume: 0.06, attack: 0.03, pitchBend: 30 },
		],
	},

	// GameCube — Clean, glassy, crystalline. Short sine plinks.
	gamecube: {
		select:   { freq: 1047, type: 'sine', duration: 0.08, volume: 0.04, attack: 0.003 },
		confirm:  [
			{ freq: 880, type: 'sine', duration: 0.06, volume: 0.04 },
			{ freq: 1047, type: 'sine', duration: 0.06, volume: 0.04 },
			{ freq: 1319, type: 'sine', duration: 0.06, volume: 0.04 },
		],
		cancel:   { freq: 523, type: 'sine', duration: 0.1, volume: 0.04 },
		error:    { freq: 330, type: 'sine', duration: 0.2, volume: 0.05 },
		navigate: { freq: 784, type: 'sine', duration: 0.05, volume: 0.03 },
		boot:     [
			{ freq: 659, type: 'sine', duration: 0.08, volume: 0.05 },
			{ freq: 784, type: 'sine', duration: 0.08, volume: 0.05 },
			{ freq: 1047, type: 'sine', duration: 0.08, volume: 0.05 },
			{ freq: 1319, type: 'sine', duration: 0.08, volume: 0.05 },
		],
	},

	// Wii — Soft, bubbly, friendly. Smooth envelopes, dual detuned oscillators.
	wii: {
		select:   { freq: 620, type: 'sine', duration: 0.1, volume: 0.05, attack: 0.02, detune: 6 },
		confirm:  { freq: 800, type: 'sine', duration: 0.12, volume: 0.05, attack: 0.025, detune: 5 },
		cancel:   { freq: 350, type: 'sine', duration: 0.1, volume: 0.04, attack: 0.02, detune: 4 },
		error:    { freq: 250, type: 'sine', duration: 0.18, volume: 0.05, attack: 0.03 },
		navigate: { freq: 550, type: 'sine', duration: 0.06, volume: 0.03, attack: 0.015, detune: 5 },
		boot:     [
			{ freq: 440, type: 'sine', duration: 0.1, volume: 0.05, attack: 0.03, detune: 6 },
			{ freq: 554, type: 'sine', duration: 0.1, volume: 0.05, attack: 0.03, detune: 6 },
			{ freq: 659, type: 'sine', duration: 0.1, volume: 0.05, attack: 0.03, detune: 6 },
		],
	},

	// Switch — Minimal, precise, tactile. Ultra-short bursts.
	switch: {
		select:   { freq: 1200, type: 'sine', duration: 0.025, volume: 0.05, attack: 0.002 },
		confirm:  { freq: 1400, type: 'sine', duration: 0.03, volume: 0.05, attack: 0.002 },
		cancel:   { freq: 600, type: 'sine', duration: 0.03, volume: 0.04, attack: 0.002 },
		error:    { freq: 400, type: 'sine', duration: 0.06, volume: 0.05 },
		navigate: { freq: 900, type: 'sine', duration: 0.02, volume: 0.03, attack: 0.002 },
		boot:     [
			{ freq: 800, type: 'sine', duration: 0.03, volume: 0.06, attack: 0.002 },
			{ freq: 1200, type: 'sine', duration: 0.03, volume: 0.06, attack: 0.002 },
		],
	},

	// DS — Cute, bright, toy-like. Medium sine/triangle, short but not harsh.
	ds: {
		select:   { freq: 720, type: 'sine', duration: 0.07, volume: 0.04 },
		confirm:  [
			{ freq: 600, type: 'sine', duration: 0.06, volume: 0.04 },
			{ freq: 800, type: 'sine', duration: 0.06, volume: 0.04 },
		],
		cancel:   { freq: 380, type: 'sine', duration: 0.08, volume: 0.04 },
		error:    { freq: 240, type: 'triangle', duration: 0.18, volume: 0.05 },
		navigate: { freq: 640, type: 'sine', duration: 0.05, volume: 0.03 },
		boot:     [
			{ freq: 523, type: 'sine', duration: 0.08, volume: 0.05 },
			{ freq: 659, type: 'sine', duration: 0.08, volume: 0.05 },
			{ freq: 784, type: 'sine', duration: 0.08, volume: 0.05 },
		],
	},

	// 3DS — Refined DS. Lower base freqs, subtle delay/reverb tail.
	'3ds': {
		select:   { freq: 660, type: 'sine', duration: 0.09, volume: 0.04, delayTime: 0.08, delayFeedback: 0.1 },
		confirm:  [
			{ freq: 554, type: 'sine', duration: 0.08, volume: 0.04, delayTime: 0.1, delayFeedback: 0.12 },
			{ freq: 740, type: 'sine', duration: 0.08, volume: 0.04, delayTime: 0.1, delayFeedback: 0.12 },
		],
		cancel:   { freq: 340, type: 'sine', duration: 0.1, volume: 0.04, delayTime: 0.06, delayFeedback: 0.08 },
		error:    { freq: 220, type: 'sine', duration: 0.2, volume: 0.05 },
		navigate: { freq: 580, type: 'sine', duration: 0.055, volume: 0.03, delayTime: 0.06, delayFeedback: 0.08 },
		boot:     [
			{ freq: 440, type: 'sine', duration: 0.08, volume: 0.05, delayTime: 0.1, delayFeedback: 0.15 },
			{ freq: 554, type: 'sine', duration: 0.08, volume: 0.05, delayTime: 0.1, delayFeedback: 0.15 },
			{ freq: 659, type: 'sine', duration: 0.08, volume: 0.05, delayTime: 0.1, delayFeedback: 0.15 },
			{ freq: 784, type: 'sine', duration: 0.08, volume: 0.05, delayTime: 0.1, delayFeedback: 0.15 },
		],
	},
};

// ─── Dispatch Helpers ───────────────────────────────────────────────────────

function playSound(type: SoundType): void {
	if (!get(soundsEnabled)) return;
	const t = get(theme);
	if (t === 'default') return;
	const sounds = consoleSounds[t];
	if (sounds) playSoundDef(sounds[type]);
}

// ─── Exports ────────────────────────────────────────────────────────────────

export function playSelect(): void { playSound('select'); }
export function playConfirm(): void { playSound('confirm'); }
export function playCancel(): void { playSound('cancel'); }
export function playError(): void { playSound('error'); }
export function playNavigate(): void { playSound('navigate'); }

// Boot sound respects soundsEnabled and default theme
export function playBootSound(): void {
	if (!get(soundsEnabled)) return;
	const t = get(theme);
	if (t === 'default') return;
	const sounds = consoleSounds[t];
	if (sounds) playSoundDef(sounds.boot);
}
