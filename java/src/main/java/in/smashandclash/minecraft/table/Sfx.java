package in.smashandclash.minecraft.table;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The game's sounds, played from the table: everyone nearby hears the cards, the clash and the
 * characters' voices (the sound files are mono, so they come from the table's spot in the world).
 * Event names are the kit's, as sounds.json has them: sfx.card_place, voice.1.capture.2, announcer.smash2, ...
 */
public final class Sfx {
	private Sfx() {}

	static Holder<SoundEvent> event(String name) {
		return Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("smashandclash", name)));
	}

	/** A sound at the table, heard up to ~24 blocks away. */
	public static void play(ServerLevel level, Vec3 at, String name, float volume) {
		level.playSeededSound(null, at.x, at.y, at.z, event(name), SoundSource.BLOCKS, volume, 1f, level.getRandom().nextLong());
	}

	public static void play(ServerLevel level, Vec3 at, String name) {
		play(level, at, name, 1.5f);
	}

	/** A sound for one player only (their turn chime, their result). */
	public static void to(ServerPlayer player, String name, float volume) {
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
			event(name), SoundSource.MASTER, player.getX(), player.getEyeY(), player.getZ(), volume, 1f, player.getRandom().nextLong()));
	}
}
