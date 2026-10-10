package in.smashandclash.minecraft.net;

import in.smashandclash.minecraft.SmashAndClashMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** What the table and the players' clients say to each other. */
public final class Payloads {
	private Payloads() {}

	/** Server -> client: open the table's menu (its state as JSON: seats, status, room code, your record). */
	public record TableMenu(BlockPos pos, String json) implements CustomPacketPayload {
		public static final Type<TableMenu> TYPE = new Type<>(SmashAndClashMod.id("table_menu"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TableMenu> CODEC =
			StreamCodec.composite(BlockPos.STREAM_CODEC, TableMenu::pos, ByteBufCodecs.STRING_UTF8, TableMenu::json, TableMenu::new);

		@Override
		public Type<TableMenu> type() {
			return TYPE;
		}
	}

	/** Client -> server: a choice from the table's menu (house, quick, room, join <code>, seat, resign, leave, ruleset <name>). */
	public record TableAction(BlockPos pos, String action, String arg) implements CustomPacketPayload {
		public static final Type<TableAction> TYPE = new Type<>(SmashAndClashMod.id("table_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TableAction> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, TableAction::pos, ByteBufCodecs.STRING_UTF8, TableAction::action, ByteBufCodecs.STRING_UTF8, TableAction::arg, TableAction::new);

		@Override
		public Type<TableAction> type() {
			return TYPE;
		}
	}

	/** Server -> client: you sit at a table (music: matchmaking | battle | victory | defeat, or "" when you leave). */
	public record Seated(String music, String hint) implements CustomPacketPayload {
		public static final Type<Seated> TYPE = new Type<>(SmashAndClashMod.id("seated"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Seated> CODEC =
			StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Seated::music, ByteBufCodecs.STRING_UTF8, Seated::hint, Seated::new);

		@Override
		public Type<Seated> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TableMenu.TYPE, TableMenu.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Seated.TYPE, Seated.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TableAction.TYPE, TableAction.CODEC);
	}
}
