package in.smashandclash.minecraft.client;

import in.smashandclash.minecraft.SmashAndClashMod;
import in.smashandclash.minecraft.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.color.item.ItemTintSources;
import net.minecraft.core.Holder;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;

/** The client side: the table's menu, the viewer-relative card rims, and the game's music while you play. */
public class SmashAndClashClient implements ClientModInitializer {
	/** The music state the table you sit at asked for ("" = Minecraft's own music). */
	public static volatile String music = "";
	private static String musicFor = "";
	private static Music current;

	@Override
	public void onInitializeClient() {
		ItemTintSources.ID_MAPPER.put(SmashAndClashMod.id("seat"), SeatTint.MAP_CODEC);
		ClientPlayNetworking.registerGlobalReceiver(Payloads.TableMenu.TYPE, (payload, ctx) ->
			ctx.client().gui.setScreen(new TableScreen(payload.pos(), payload.json())));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Seated.TYPE, (payload, ctx) -> music = payload.music());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> music = "");
	}

	/** The game's music for the state the table asked for (matchmaking, battle, victory, defeat), or null. */
	public static Music situational() {
		String m = music;
		if (m == null || m.isEmpty()) return null;
		if (!m.equals(musicFor)) {
			musicFor = m;
			current = new Music(Holder.direct(SoundEvent.createVariableRangeEvent(SmashAndClashMod.id("music." + m))), 0, 20, true);
		}
		return current;
	}
}
