<script lang="ts">
	import { theme } from '$lib/stores/theme';
	import { playBootSound } from '$lib/utils/sounds';
	import { bootAnimationsEnabled } from '$lib/stores/sounds';

	let visible = $state(false);
	let animating = $state(false);
	let currentTheme = $state('');
	let skipTimeout: ReturnType<typeof setTimeout> | null = null;

	function getSessionKey(t: string): string {
		return `gizmo:booted:${t}`;
	}

	function hasBooted(t: string): boolean {
		return typeof sessionStorage !== 'undefined' && sessionStorage.getItem(getSessionKey(t)) === '1';
	}

	function markBooted(t: string) {
		if (typeof sessionStorage !== 'undefined') {
			sessionStorage.setItem(getSessionKey(t), '1');
		}
	}

	let fadeTimeout: ReturnType<typeof setTimeout> | null = null;

	function dismiss() {
		if (skipTimeout) { clearTimeout(skipTimeout); skipTimeout = null; }
		animating = false;
		if (fadeTimeout) clearTimeout(fadeTimeout);
		fadeTimeout = setTimeout(() => { visible = false; }, 300); // fade-out duration
	}

	function triggerBoot(t: string) {
		currentTheme = t;
		visible = true;
		// Small delay to ensure DOM is rendered before animation starts
		requestAnimationFrame(() => { animating = true; });
		markBooted(t);
		// Play boot sound with slight delay for visual sync
		setTimeout(() => playBootSound(), 200);
		// Auto-dismiss after animation duration
		skipTimeout = setTimeout(dismiss, 2800);
	}

	// Re-trigger boot sequence (called from ConsoleButtons)
	export function replayBoot() {
		const t = $theme;
		if (t === 'default') return;
		if (typeof sessionStorage !== 'undefined') {
			sessionStorage.removeItem(getSessionKey(t));
		}
		triggerBoot(t);
	}

	let lastTheme = $state('');

	// Watch for theme changes — play boot on every switch if enabled
	$effect(() => {
		const t = $theme;
		if (t === lastTheme) return; // no change
		lastTheme = t;
		if (t !== 'default' && $bootAnimationsEnabled) {
			setTimeout(() => triggerBoot(t), 100);
		}
	});

	// Listen for replay-boot signal from ConsoleButtons
	$effect(() => {
		const handler = () => replayBoot();
		document.addEventListener('gizmo:replay-boot', handler);
		return () => document.removeEventListener('gizmo:replay-boot', handler);
	});

	// Cleanup all timers on component teardown
	$effect(() => {
		return () => {
			if (skipTimeout) clearTimeout(skipTimeout);
			if (fadeTimeout) clearTimeout(fadeTimeout);
		};
	});
</script>

{#if visible}
	<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
	<div
		class="boot-overlay fixed inset-0 z-[100] flex items-center justify-center cursor-pointer"
		class:boot-active={animating}
		class:boot-fade-out={!animating && visible}
		data-boot={currentTheme}
		onclick={dismiss}
	>
		<div class="boot-content">
			{#if currentTheme === 'nes'}
				<div class="boot-nes-static"></div>
				<div class="boot-nes-bloom"></div>
				<div class="boot-nes-bg"></div>
				<div class="boot-nes-text">Nintendo</div>
			{:else if currentTheme === 'snes'}
				<div class="boot-snes-stars">
					<span></span><span></span><span></span><span></span>
					<span></span><span></span><span></span><span></span>
				</div>
				<div class="boot-snes-text">Super Nintendo</div>
			{:else if currentTheme === 'gba'}
				<div class="boot-gba-flash"></div>
				<div class="boot-gba-line1">Game Boy</div>
				<div class="boot-gba-line2">ADVANCE</div>
			{:else if currentTheme === 'n64'}
				<div class="boot-n64-cube">
					<div class="boot-n64-face boot-n64-front"></div>
					<div class="boot-n64-face boot-n64-back"></div>
					<div class="boot-n64-face boot-n64-left"></div>
					<div class="boot-n64-face boot-n64-right"></div>
					<div class="boot-n64-face boot-n64-top"></div>
					<div class="boot-n64-face boot-n64-bottom"></div>
				</div>
				<div class="boot-n64-letter">N</div>
			{:else if currentTheme === 'gamecube'}
				<div class="boot-gc-flash"></div>
				<div class="boot-gc-cube"></div>
				<div class="boot-gc-letter">G</div>
			{:else if currentTheme === 'wii'}
				<div class="boot-wii-ring boot-wii-ring-1"></div>
				<div class="boot-wii-ring boot-wii-ring-2"></div>
				<div class="boot-wii-ring boot-wii-ring-3"></div>
				<div class="boot-wii-text">Wii</div>
				<div class="boot-wii-sub">Press anywhere to continue</div>
			{:else if currentTheme === 'switch'}
				<div class="boot-switch-left"></div>
				<div class="boot-switch-right"></div>
				<div class="boot-switch-flash"></div>
				<div class="boot-switch-logo">SWITCH</div>
			{:else if currentTheme === 'ds'}
				<div class="boot-ds-top"></div>
				<div class="boot-ds-bottom"></div>
				<div class="boot-ds-flash"></div>
				<div class="boot-ds-text">Nintendo DS</div>
			{:else if currentTheme === '3ds'}
				<div class="boot-3ds-top"></div>
				<div class="boot-3ds-bottom"></div>
				<div class="boot-3ds-text">Nintendo 3DS</div>
				<div class="boot-3ds-depth">Nintendo 3DS</div>
			{/if}
		</div>
	</div>
{/if}

<style>
	.boot-overlay {
		opacity: 0;
		transition: opacity 300ms ease;
		background: #000;
	}
	.boot-active { opacity: 1; }
	.boot-fade-out { opacity: 0; }

	.boot-content {
		position: relative;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		width: 100%;
		height: 100%;
	}

	/* ═══ NES ═══ */
	[data-boot="nes"] { background: #c0c0c0; }
	[data-boot="nes"] .boot-content { animation: nesFlicker 1.2s steps(1) forwards; }

	.boot-nes-static {
		position: absolute;
		inset: 0;
		background: repeating-linear-gradient(
			0deg,
			transparent 0px,
			rgba(0,0,0,0.03) 1px,
			transparent 2px,
			transparent 4px
		);
		animation: nesStaticFlicker 0.15s steps(3) infinite;
		opacity: 0.6;
		z-index: 0;
	}

	.boot-nes-bloom {
		position: absolute;
		width: 200px;
		height: 200px;
		border-radius: 50%;
		background: radial-gradient(circle, rgba(228,0,0,0.4) 0%, transparent 70%);
		animation: nesBoom 1.2s 0.8s ease-out forwards;
		opacity: 0;
		z-index: 1;
	}

	.boot-nes-bg { position: absolute; inset: 0; background: #c0c0c0; z-index: 0; }
	.boot-nes-text {
		font-family: 'Press Start 2P', monospace;
		font-size: 28px;
		color: #e40000;
		opacity: 0;
		animation: nesFadeIn 0.8s 1.2s ease forwards;
		z-index: 2;
	}

	@keyframes nesFlicker {
		0% { opacity: 0; }
		10% { opacity: 1; }
		20% { opacity: 0; }
		30% { opacity: 1; }
		50% { opacity: 0.3; }
		60% { opacity: 1; }
		100% { opacity: 1; }
	}
	@keyframes nesFadeIn {
		from { opacity: 0; }
		to { opacity: 1; }
	}
	@keyframes nesStaticFlicker {
		0% { opacity: 0.6; transform: translateY(0); }
		33% { opacity: 0.4; transform: translateY(-1px); }
		66% { opacity: 0.7; transform: translateY(1px); }
		100% { opacity: 0.6; transform: translateY(0); }
	}
	@keyframes nesBoom {
		0% { opacity: 0; transform: scale(3); }
		30% { opacity: 1; transform: scale(1.5); }
		100% { opacity: 0; transform: scale(0); }
	}

	/* ═══ SNES ═══ */
	[data-boot="snes"] { background: #000; }

	.boot-snes-stars {
		position: absolute;
		width: 100%;
		height: 100%;
	}
	.boot-snes-stars span {
		position: absolute;
		width: 3px;
		height: 3px;
		background: #c8a0ff;
		border-radius: 50%;
		opacity: 0;
	}
	.boot-snes-stars span:nth-child(1) { top: 15%; left: 10%; animation: snesStarConverge 1.5s 0.2s ease-in forwards; }
	.boot-snes-stars span:nth-child(2) { top: 10%; right: 15%; animation: snesStarConverge 1.5s 0.35s ease-in forwards; }
	.boot-snes-stars span:nth-child(3) { bottom: 20%; left: 20%; animation: snesStarConverge 1.5s 0.1s ease-in forwards; }
	.boot-snes-stars span:nth-child(4) { bottom: 15%; right: 10%; animation: snesStarConverge 1.5s 0.4s ease-in forwards; }
	.boot-snes-stars span:nth-child(5) { top: 30%; left: 5%; animation: snesStarConverge 1.5s 0.25s ease-in forwards; }
	.boot-snes-stars span:nth-child(6) { top: 25%; right: 8%; animation: snesStarConverge 1.5s 0.15s ease-in forwards; }
	.boot-snes-stars span:nth-child(7) { bottom: 30%; left: 12%; animation: snesStarConverge 1.5s 0.3s ease-in forwards; }
	.boot-snes-stars span:nth-child(8) { bottom: 25%; right: 18%; animation: snesStarConverge 1.5s 0.45s ease-in forwards; }

	.boot-snes-text {
		font-family: 'Press Start 2P', monospace;
		font-size: 22px;
		color: #c8a0ff;
		transform: scale(0.3) perspective(500px) rotateX(15deg);
		opacity: 0;
		animation: snesScale 1.5s 0.5s cubic-bezier(0.22, 1, 0.36, 1) forwards;
		z-index: 1;
	}

	@keyframes snesScale {
		0% { opacity: 0; transform: scale(0.3) perspective(500px) rotateX(15deg); }
		40% { opacity: 1; }
		100% { opacity: 1; transform: scale(1) perspective(500px) rotateX(0deg); }
	}
	@keyframes snesStarConverge {
		0% { opacity: 0; transform: translate(0, 0) scale(1); }
		20% { opacity: 1; }
		80% { opacity: 0.6; }
		100% { opacity: 0; transform: translate(calc(50vw - 100%), calc(50vh - 100%)) scale(0); }
	}

	/* ═══ GBA ═══ */
	[data-boot="gba"] { background: #fff; }

	.boot-gba-flash {
		position: absolute;
		inset: 0;
		background: #fff;
		animation: gbaWhiteFlash 0.3s 0.3s ease-out forwards;
		z-index: 3;
	}

	.boot-gba-line1 {
		font-family: 'Press Start 2P', monospace;
		font-size: 20px;
		color: #1a0e40;
		transform: translateY(-40px);
		opacity: 0;
		animation: gbaDropIn 0.6s 0.4s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
		z-index: 1;
	}
	.boot-gba-line2 {
		font-family: 'Press Start 2P', monospace;
		font-size: 14px;
		color: #4a3090;
		letter-spacing: 6px;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.2s ease forwards;
		margin-top: 8px;
		z-index: 1;
	}

	@keyframes gbaWhiteFlash {
		0% { opacity: 1; }
		100% { opacity: 0; }
	}
	@keyframes gbaDropIn {
		0% { opacity: 0; transform: translateY(-40px); }
		100% { opacity: 1; transform: translateY(0); }
	}

	/* ═══ N64 ═══ */
	[data-boot="n64"] { background: #000; perspective: 600px; }

	.boot-n64-cube {
		width: 80px;
		height: 80px;
		position: absolute;
		transform-style: preserve-3d;
		animation: n64CubeSpin 2s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
	}
	.boot-n64-face {
		position: absolute;
		width: 80px;
		height: 80px;
		border: 2px solid rgba(255,255,255,0.15);
	}
	.boot-n64-front  { background: #e04040; transform: translateZ(40px); }
	.boot-n64-back   { background: #4080e0; transform: rotateY(180deg) translateZ(40px); }
	.boot-n64-left   { background: #40b040; transform: rotateY(-90deg) translateZ(40px); }
	.boot-n64-right  { background: #e0c040; transform: rotateY(90deg) translateZ(40px); }
	.boot-n64-top    { background: #e04040; transform: rotateX(90deg) translateZ(40px); }
	.boot-n64-bottom { background: #4080e0; transform: rotateX(-90deg) translateZ(40px); }

	.boot-n64-letter {
		font-family: 'Fredoka', sans-serif;
		font-size: 100px;
		font-weight: 700;
		background: linear-gradient(135deg, #e04040 0%, #40b040 33%, #4080e0 66%, #e0c040 100%);
		-webkit-background-clip: text;
		-webkit-text-fill-color: transparent;
		background-clip: text;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.8s ease forwards;
		z-index: 1;
	}

	@keyframes n64CubeSpin {
		0% { opacity: 0; transform: rotateX(0deg) rotateY(0deg) scale(0.5); }
		10% { opacity: 1; }
		50% { transform: rotateX(720deg) rotateY(540deg) scale(1.2); }
		80% { transform: rotateX(360deg) rotateY(360deg) scale(1); }
		100% { opacity: 0; transform: rotateX(0deg) rotateY(0deg) scale(0.3); }
	}

	/* ═══ GameCube ═══ */
	[data-boot="gamecube"] { background: #000; }

	.boot-gc-flash {
		position: absolute;
		width: 120px;
		height: 120px;
		border-radius: 50%;
		background: radial-gradient(circle, rgba(123,111,207,0.6) 0%, transparent 70%);
		animation: gcImpactFlash 0.6s 1.4s ease-out forwards;
		opacity: 0;
		z-index: 2;
	}

	.boot-gc-cube {
		width: 40px;
		height: 40px;
		border: 3px solid #7b6fcf;
		border-radius: 4px;
		animation: gcDrop 1.2s 0.3s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
		transform: translateY(-200px);
		opacity: 0;
		position: absolute;
	}
	.boot-gc-letter {
		font-family: 'Nunito', sans-serif;
		font-size: 60px;
		font-weight: 700;
		color: #7b6fcf;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.6s ease forwards;
	}

	@keyframes gcDrop {
		0% { opacity: 0; transform: translateY(-200px) rotate(0deg); }
		50% { opacity: 1; transform: translateY(5px) rotate(540deg); }
		70% { transform: translateY(-8px) rotate(700deg); }
		85% { transform: translateY(2px) rotate(715deg); }
		100% { opacity: 0; transform: translateY(0) rotate(720deg) scale(0); }
	}
	@keyframes gcImpactFlash {
		0% { opacity: 0; transform: scale(0); }
		40% { opacity: 1; transform: scale(1.5); }
		100% { opacity: 0; transform: scale(2); }
	}

	/* ═══ Wii ═══ */
	[data-boot="wii"] { background: #fff; }

	.boot-wii-ring {
		position: absolute;
		width: 120px;
		height: 120px;
		border: 3px solid rgba(0,136,204,0.4);
		border-radius: 50%;
		opacity: 0;
	}
	.boot-wii-ring-1 { animation: wiiRingExpand 1.8s 0.3s ease-out infinite; }
	.boot-wii-ring-2 { animation: wiiRingExpand 1.8s 0.9s ease-out infinite; }
	.boot-wii-ring-3 { animation: wiiRingExpand 1.8s 1.5s ease-out infinite; }

	.boot-wii-text {
		font-family: system-ui, sans-serif;
		font-size: 48px;
		font-weight: 300;
		color: #555;
		letter-spacing: 4px;
		opacity: 0;
		animation: nesFadeIn 0.8s 0.8s ease forwards;
		z-index: 1;
	}
	.boot-wii-sub {
		font-family: system-ui, sans-serif;
		font-size: 11px;
		color: #aaa;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.6s ease forwards;
		margin-top: 16px;
		z-index: 1;
	}

	@keyframes wiiRingExpand {
		0% { opacity: 1; transform: scale(0.3); }
		100% { opacity: 0; transform: scale(3); }
	}

	/* ═══ Switch ═══ */
	[data-boot="switch"] { background: #1a1a1a; }

	.boot-switch-left, .boot-switch-right {
		position: absolute;
		top: 0;
		bottom: 0;
		width: 50%;
	}
	.boot-switch-left {
		left: -50%;
		background: linear-gradient(90deg, #e60012, #ff2a3e);
		animation: switchSlideLeft 0.8s 0.5s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
	}
	.boot-switch-right {
		right: -50%;
		background: linear-gradient(90deg, #00c3e3, #009ab8);
		animation: switchSlideRight 0.8s 0.5s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
	}

	.boot-switch-flash {
		position: absolute;
		width: 100%;
		height: 100%;
		background: radial-gradient(circle at center, rgba(255,255,255,0.8) 0%, transparent 50%);
		opacity: 0;
		animation: switchClickFlash 0.4s 1.3s ease-out forwards;
		z-index: 2;
	}

	.boot-switch-logo {
		font-family: system-ui, sans-serif;
		font-size: 28px;
		font-weight: 700;
		letter-spacing: 12px;
		color: #fff;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.6s ease forwards;
		z-index: 3;
	}

	@keyframes switchSlideLeft {
		0% { left: -50%; }
		70% { left: 1%; }
		100% { left: 0%; }
	}
	@keyframes switchSlideRight {
		0% { right: -50%; }
		70% { right: 1%; }
		100% { right: 0%; }
	}
	@keyframes switchClickFlash {
		0% { opacity: 0; }
		30% { opacity: 1; }
		100% { opacity: 0; }
	}

	/* ═══ DS ═══ */
	[data-boot="ds"] { background: #b8b8c0; }
	.boot-ds-top, .boot-ds-bottom {
		width: 200px;
		height: 80px;
		border: 3px solid #888;
		border-radius: 4px;
		background: #111;
	}
	.boot-ds-top {
		opacity: 0;
		animation: dsScreenOn 0.4s 0.5s ease forwards;
		box-shadow: 0 0 20px rgba(48,112,208,0.3);
	}
	.boot-ds-bottom {
		opacity: 0;
		animation: dsScreenOn 0.4s 0.9s ease forwards;
		margin-top: 12px;
		box-shadow: 0 0 20px rgba(48,112,208,0.3);
	}
	.boot-ds-flash {
		position: absolute;
		width: 220px;
		height: 180px;
		background: radial-gradient(ellipse, rgba(48,112,208,0.3) 0%, transparent 70%);
		animation: dsFlashPulse 0.6s 1.0s ease-out forwards;
		opacity: 0;
		z-index: 2;
	}
	.boot-ds-text {
		font-family: system-ui, sans-serif;
		font-size: 14px;
		font-weight: 600;
		color: #555;
		letter-spacing: 2px;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.4s ease forwards;
		margin-top: 16px;
	}

	@keyframes dsScreenOn {
		0% { opacity: 0; box-shadow: none; }
		50% { opacity: 1; box-shadow: 0 0 30px rgba(48,112,208,0.5); }
		100% { opacity: 1; box-shadow: 0 0 15px rgba(48,112,208,0.2); }
	}
	@keyframes dsFlashPulse {
		0% { opacity: 0; transform: scale(0.8); }
		50% { opacity: 1; transform: scale(1.1); }
		100% { opacity: 0; transform: scale(1.3); }
	}

	/* ═══ 3DS ═══ */
	[data-boot="3ds"] { background: #151e28; }
	.boot-3ds-top, .boot-3ds-bottom {
		width: 200px;
		height: 80px;
		border: 2px solid #2a3a4a;
		border-radius: 4px;
		background: #0a1018;
	}
	.boot-3ds-top {
		opacity: 0;
		animation: dsScreenOn 0.4s 0.5s ease forwards;
		box-shadow: 0 0 20px rgba(0,188,212,0.2);
		width: 220px;
	}
	.boot-3ds-bottom {
		opacity: 0;
		animation: dsScreenOn 0.4s 0.9s ease forwards;
		margin-top: 8px;
		box-shadow: 0 0 20px rgba(0,188,212,0.2);
	}
	.boot-3ds-text {
		font-family: 'Nunito', sans-serif;
		font-size: 14px;
		font-weight: 700;
		color: #00bcd4;
		letter-spacing: 2px;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.4s ease forwards;
		margin-top: 12px;
		z-index: 2;
	}
	.boot-3ds-depth {
		font-family: 'Nunito', sans-serif;
		font-size: 14px;
		font-weight: 700;
		color: transparent;
		letter-spacing: 2px;
		opacity: 0;
		position: absolute;
		bottom: calc(50% - 70px);
		animation: boot3dsParallax 1.5s 1.4s ease forwards;
		text-shadow: 4px 0 0 rgba(0,188,212,0.12), -4px 0 0 rgba(255,100,100,0.08);
		z-index: 1;
	}

	@keyframes boot3dsParallax {
		0% { opacity: 1; text-shadow: 4px 0 0 rgba(0,188,212,0.12), -4px 0 0 rgba(255,100,100,0.08); }
		100% { opacity: 0; text-shadow: 0px 0 0 rgba(0,188,212,0.12), 0px 0 0 rgba(255,100,100,0.08); }
	}

	/* Mobile: skip boot */
	@media (max-width: 640px) {
		.boot-overlay { display: none !important; }
	}
</style>
