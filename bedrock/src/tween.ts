// The website's tweens, one game tick (50 ms) at a time: each tick a tween evaluates its curve and
// moves its entities; clients smooth the movement between ticks. Durations are the site's ms.

export type Ease = (t: number) => number;
export const LINEAR: Ease = (t) => t;
export const OUT_CUBIC: Ease = (t) => 1 - Math.pow(1 - t, 3);
export const IN_CUBIC: Ease = (t) => t * t * t;
export const IN_OUT_CUBIC: Ease = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
export const OUT_BACK: Ease = (t) => {
	const c1 = 1.70158, c3 = c1 + 1;
	return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
};

interface Job {
	start: number;
	ticks: number;
	ease: Ease;
	update?: (t: number) => void;
	done?: () => void;
}

export class Tween {
	private jobs: Job[] = [];
	private added: Job[] = [];
	private now = 0;

	static ticks(ms: number): number {
		return Math.max(1, Math.round(ms / 50));
	}

	run(delayMs: number, ms: number, ease: Ease, update: (t: number) => void, done?: () => void): void {
		this.added.push({ start: this.now + Math.round(delayMs / 50), ticks: Tween.ticks(ms), ease, update, done });
	}

	at(delayMs: number, fn: () => void): void {
		this.added.push({ start: this.now + Math.round(delayMs / 50), ticks: 0, ease: LINEAR, done: fn });
	}

	busy(): boolean {
		return this.jobs.length > 0 || this.added.length > 0;
	}

	clear(): void {
		this.jobs = [];
		this.added = [];
	}

	tick(): void {
		this.jobs.push(...this.added);
		this.added = [];
		const keep: Job[] = [];
		for (const j of this.jobs) {
			if (this.now < j.start) {
				keep.push(j);
				continue;
			}
			try {
				if (j.ticks === 0) {
					j.done?.();
					continue;
				}
				const t = Math.min(1, (this.now - j.start + 1) / j.ticks);
				j.update?.(j.ease(t));
				if (t >= 1) j.done?.();
				else keep.push(j);
			} catch (e) {
				console.warn('[Smash&Clash] tween: ' + String(e));
			}
		}
		this.jobs = keep;
		this.now++;
	}
}
