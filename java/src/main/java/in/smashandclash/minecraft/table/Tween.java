package in.smashandclash.minecraft.table;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * The website's tweens, run on the server one tick (50 ms) at a time: every tick a tween
 * evaluates its curve and moves its displays, and each client interpolates the 50 ms between.
 * Durations are the site's milliseconds; the easing functions are the site's.
 */
public final class Tween {
	public interface Ease {
		double at(double t);
	}

	public static final Ease LINEAR = t -> t;
	public static final Ease OUT_CUBIC = t -> 1 - Math.pow(1 - t, 3);
	public static final Ease IN_CUBIC = t -> t * t * t;
	public static final Ease IN_OUT_CUBIC = t -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
	public static final Ease OUT_BACK = t -> {
		double c1 = 1.70158, c3 = c1 + 1;
		return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
	};

	private record Job(int start, int ticks, Ease ease, DoubleConsumer update, Runnable done) {}

	/** Testing: play every animation this many times slower (1 = the website's speed). */
	public static volatile double TIME_SCALE = 1;

	private final List<Job> jobs = new ArrayList<>();
	private final List<Job> added = new ArrayList<>();
	private int now;

	/** Ticks for the site's milliseconds (at least one). */
	public static int ticks(double ms) {
		return Math.max(1, (int) Math.round(ms * TIME_SCALE / 50.0));
	}

	/** Run `update(eased t)` over `ms`, starting after `delayMs`; then `done`. */
	public void run(double delayMs, double ms, Ease ease, DoubleConsumer update, Runnable done) {
		added.add(new Job(now + (int) Math.round(delayMs * TIME_SCALE / 50.0), ticks(ms), ease, update, done));
	}

	public void run(double delayMs, double ms, Ease ease, DoubleConsumer update) {
		run(delayMs, ms, ease, update, null);
	}

	/** Do something after `delayMs`. */
	public void at(double delayMs, Runnable r) {
		added.add(new Job(now + (int) Math.round(delayMs * TIME_SCALE / 50.0), 0, LINEAR, null, r));
	}

	public boolean busy() {
		return !jobs.isEmpty() || !added.isEmpty();
	}

	/** One server tick. */
	public void tick() {
		jobs.addAll(added);
		added.clear();
		for (Iterator<Job> it = jobs.iterator(); it.hasNext(); ) {
			Job j = it.next();
			if (now < j.start) continue;
			if (j.ticks == 0) {
				it.remove();
				if (j.done != null) j.done.run();
				continue;
			}
			double t = Math.min(1, (now - j.start + 1) / (double) j.ticks);
			if (j.update != null) j.update.accept(j.ease.at(t));
			if (t >= 1) {
				it.remove();
				if (j.done != null) j.done.run();
			}
		}
		now++;
	}

	public void clear() {
		jobs.clear();
		added.clear();
	}
}
