package in.smashandclash.minecraft;

import in.smashandclash.minecraft.net.Payloads;
import in.smashandclash.minecraft.table.TableBlockEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.CreativeModeTabs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smash&Clash inside Minecraft: a table you place in the world, the game on it for everyone
 * nearby to see, your hand in your inventory. The table plays on the Smash&Clash games API, so
 * a game here can be against a player at the same table, the house, or anyone on any client.
 */
public class SmashAndClashMod implements ModInitializer {
	public static final String MOD_ID = "smashandclash";
	public static final Logger LOGGER = LoggerFactory.getLogger("Smash&Clash");
	public static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		Deck.load();
		ModContent.register();
		Payloads.register();
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(out -> out.accept(ModContent.TABLE_ITEM.getDefaultInstance(), net.minecraft.world.item.CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS));

		// right-click a tile on a table: play there (or open the menu)
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			int[] tile = tileOf(entity);
			if (tile == null) return InteractionResult.PASS;
			if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
				TableBlockEntity be = tableOf(sl, entity);
				if (be != null) be.session().onTile(sp, tile[0], tile[1], sp.getMainHandItem());
			}
			return InteractionResult.SUCCESS;
		});
		// left-click a tile: what is on it
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			int[] tile = tileOf(entity);
			if (tile == null) return InteractionResult.PASS;
			if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
				TableBlockEntity be = tableOf(sl, entity);
				if (be != null) be.session().inspect(sp, tile[0], tile[1]);
			}
			return InteractionResult.SUCCESS;
		});
		// a choice from a table's menu
		ServerPlayNetworking.registerGlobalReceiver(Payloads.TableAction.TYPE, (payload, ctx) -> {
			ServerPlayer p = ctx.player();
			BlockPos pos = payload.pos();
			if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 12 * 12) return;
			if (p.level().getBlockEntity(pos) instanceof TableBlockEntity be) be.session().action(p, payload.action(), payload.arg());
		});
		LOGGER.info("Smash&Clash {} ready", VERSION);
	}

	/** The tile a table's hit box stands for, or null. */
	private static int[] tileOf(Entity e) {
		if (!(e instanceof Interaction)) return null;
		for (String tag : e.entityTags()) {
			if (tag.startsWith("snc_tile_")) {
				String[] rc = tag.substring(9).split("_");
				return new int[] {Integer.parseInt(rc[0]), Integer.parseInt(rc[1])};
			}
		}
		return null;
	}

	private static TableBlockEntity tableOf(ServerLevel level, Entity e) {
		for (String tag : e.entityTags()) {
			if (tag.startsWith("snc@")) {
				String[] xyz = tag.substring(4).split(",");
				BlockPos pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
				if (level.getBlockEntity(pos) instanceof TableBlockEntity be) return be;
			}
		}
		return null;
	}
}
