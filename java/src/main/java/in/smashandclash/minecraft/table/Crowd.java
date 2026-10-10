package in.smashandclash.minecraft.table;

import in.smashandclash.minecraft.SmashAndClashMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The villagers who come to watch. While a game is on, villagers already close by (16 blocks)
 * walk to a spot beside the board - out on its long sides, clear of both seats and of each
 * player's view - and stay there: their walking speed is held at zero so they never wander into
 * a player. They turn to whoever's turn it is and react on the spot: a hop and a cheer for a big
 * capture, a head shake when an attack is held off, a murmur at an effect card, applause at the
 * end. When the game is over they go back to their day.
 */
final class Crowd {
	private static final Identifier HOLD = SmashAndClashMod.id("watching");
	private static final int MAX = 6;
	private final ServerLevel level;
	private final Board board;
	private final List<Villager> watching = new ArrayList<>();
	private final List<Boolean> settled = new ArrayList<>();

	Crowd(ServerLevel level, Board board) {
		this.level = level;
		this.board = board;
	}

	/** Spot i: out on the board's left (even) or right (odd) side, spread along it, on the ground. */
	private Vec3 spot(int i) {
		float side = i % 2 == 0 ? -1 : 1;
		float along = new float[] {0f, -1.0f, 1.0f}[(i / 2) % 3];
		Vec3 w = board.world(new Vector3f(side * (Board.WIDTH / 2 + 2.6f), 0, along));
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(w.x), (int) Math.floor(w.z));
		return new Vec3(Math.floor(w.x) + 0.5, y, Math.floor(w.z) + 0.5);
	}

	/** Every two seconds while a game is on: gather the villagers close by and send them to their spots. */
	void tick(boolean gameOn) {
		for (int i = watching.size() - 1; i >= 0; i--) {
			Villager v = watching.get(i);
			if (v.isRemoved() || !v.isAlive()) {
				hold(v, false);
				watching.remove(i);
				settled.remove(i);
			}
		}
		if (!gameOn) return;
		if (watching.size() < MAX) {
			for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(board.pos).inflate(16), v -> !v.isBaby() && !v.isSleeping())) {
				if (watching.size() >= MAX) break;
				if (!watching.contains(v)) {
					watching.add(v);
					settled.add(false);
				}
			}
		}
		for (int i = 0; i < watching.size(); i++) {
			if (settled.get(i)) continue;
			Villager v = watching.get(i);
			v.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
			v.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
			v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(spot(i), 0.6f, 0));
		}
	}

	/** Every tick: a villager at its spot stays put and watches `target` (the player whose turn it is). */
	void look(Vec3 target) {
		for (int i = 0; i < watching.size(); i++) {
			Villager v = watching.get(i);
			Vec3 s = spot(i);
			if (!settled.get(i)) {
				// on the way: keep it heading for its spot, not for whatever caught its eye
				v.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
				v.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
				if (v.position().distanceToSqr(s) < 1.2) {
					settled.set(i, true);
					hold(v, true);
				} else {
					// their own routines keep re-planning their walk: ask again every tick until they are there
					v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(s, 0.6f, 0));
					continue;
				}
			}
			v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			v.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
			v.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
			// nudged off its spot (a player walked into it)? back it goes
			if (v.onGround() && v.position().distanceToSqr(s) > 0.5) v.snapTo(s.x, s.y, s.z, v.getYRot(), v.getXRot());
			double dx = target.x - v.getX(), dy = target.y - v.getEyeY(), dz = target.z - v.getZ();
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			v.setYRot(yaw);
			v.setYHeadRot(yaw);
			v.setYBodyRot(yaw);
			v.setXRot(pitch);
			v.getLookControl().setLookAt(target.x, target.y, target.z);
		}
	}

	/** Hold a villager on its spot (no walking) or let it go. */
	private static void hold(Villager v, boolean on) {
		AttributeInstance speed = v.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) return;
		if (on) speed.addOrUpdateTransientModifier(new AttributeModifier(HOLD, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		else speed.removeModifier(HOLD);
	}

	private boolean near(Villager v) {
		return v.distanceToSqr(board.center) < 100;
	}

	private void hop(Villager v) {
		if (v.onGround()) v.setDeltaMovement(v.getDeltaMovement().add(0, 0.38, 0));
	}

	/** A capture: cheers (and hops, for a big one). */
	void cheer(int captures) {
		for (Villager v : watching) {
			if (!near(v)) continue;
			if (captures >= 2 || level.getRandom().nextFloat() < 0.5f) {
				level.playSound(null, v.getX(), v.getY(), v.getZ(), captures >= 3 ? SoundEvents.VILLAGER_CELEBRATE : SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.8f, 0.9f + level.getRandom().nextFloat() * 0.3f);
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getEyeY() + 0.4, v.getZ(), 4, 0.25, 0.15, 0.25, 0);
				if (captures >= 2 || level.getRandom().nextFloat() < 0.3f) hop(v);
			}
		}
	}

	/** An attack held off: they shake their heads. */
	void groan() {
		for (Villager v : watching) {
			if (!near(v) || level.getRandom().nextFloat() > 0.6f) continue;
			v.setUnhappyCounter(40);
			level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 0.7f, 1f);
		}
	}

	/** An effect card: a murmur, and the odd surprised hop. */
	void murmur() {
		for (Villager v : watching) {
			if (!near(v) || level.getRandom().nextFloat() > 0.6f) continue;
			level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.7f, 1.1f);
			if (level.getRandom().nextFloat() < 0.5f) hop(v);
		}
	}

	/** The end: applause and hops. */
	void applaud() {
		for (Villager v : watching) {
			level.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.9f, 1f);
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getEyeY() + 0.4, v.getZ(), 8, 0.3, 0.2, 0.3, 0);
			hop(v);
		}
	}

	/** The game is over: they go back to their day. */
	void dismiss() {
		for (Villager v : watching) {
			hold(v, false);
			v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		}
		watching.clear();
		settled.clear();
	}
}
