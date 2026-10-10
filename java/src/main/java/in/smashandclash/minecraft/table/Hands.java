package in.smashandclash.minecraft.table;

import in.smashandclash.minecraft.Deck;
import in.smashandclash.minecraft.ModContent;
import in.smashandclash.minecraft.api.Model;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Your hand is in your inventory: one card item per card you hold, showing its face to you and
 * its back to everyone else. The table keeps the items in step with the hand the game says you
 * have (a card you play, a card you draw, a swap), and takes them back when the game ends.
 */
public final class Hands {
	private Hands() {}

	public static final String KEY = "snc_card";
	public static final String TABLE = "snc_table";

	/** The card id a stack stands for, or -1. */
	public static int cardOf(ItemStack stack) {
		if (stack.isEmpty() || stack.getItem() != ModContent.CARD) return -1;
		CustomData d = stack.get(DataComponents.CUSTOM_DATA);
		if (d == null) return -1;
		CompoundTag t = d.copyTag();
		return t.getIntOr(KEY, -1);
	}

	public static String tableOf(ItemStack stack) {
		CustomData d = stack.get(DataComponents.CUSTOM_DATA);
		return d == null ? "" : d.copyTag().getStringOr(TABLE, "");
	}

	/** A card from your hand, as an item. */
	public static ItemStack stack(Model.HandCard h, String table) {
		ItemStack s = new ItemStack(ModContent.CARD);
		s.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("smashandclash", "card/" + h.cardId));
		Deck.Card c = Deck.card(h.cardId);
		String name = c != null ? c.name : h.card;
		s.set(DataComponents.ITEM_NAME, Component.literal(name).withStyle(ChatFormatting.WHITE));
		List<Component> lore = new ArrayList<>();
		if ("effect".equals(h.kind)) {
			lore.add(Component.literal(c != null ? c.does : h.does == null ? "" : h.does).withStyle(ChatFormatting.GRAY));
		} else {
			int top = c != null ? c.top : h.top, right = c != null ? c.right : h.right, bottom = c != null ? c.bottom : h.bottom, left = c != null ? c.left : h.left;
			lore.add(Component.literal("▲ " + top + "   ▶ " + right + "   ▼ " + bottom + "   ◀ " + left).withStyle(ChatFormatting.GRAY));
			if (c != null && c.color != null) lore.add(Component.literal(cap(c.color)).withStyle(ChatFormatting.DARK_GRAY));
		}
		lore.add(Component.literal("Right-click a glowing tile on the table to play it").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		s.set(DataComponents.LORE, new ItemLore(lore));
		CompoundTag tag = new CompoundTag();
		tag.putInt(KEY, h.cardId);
		tag.putString(TABLE, table);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** Make the player's card items match `hand` (null: take them all back). */
	public static void sync(ServerPlayer player, List<Model.HandCard> hand, String table) {
		Inventory inv = player.getInventory();
		List<Integer> want = new ArrayList<>();
		if (hand != null) for (Model.HandCard h : hand) want.add(h.cardId);
		boolean changed = false;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			int id = cardOf(s);
			if (id < 0) continue;
			// a card from this table you still hold stays where you put it; anything else goes
			if (table.equals(tableOf(s)) && want.remove((Integer) id)) continue;
			inv.setItem(i, ItemStack.EMPTY);
			changed = true;
		}
		if (hand != null) {
			for (int id : want) {
				for (Model.HandCard h : hand) {
					if (h.cardId != id) continue;
					ItemStack s = stack(h, table);
					if (!inv.add(s)) player.sendSystemMessage(Component.literal("Your inventory is full: make room for your Smash&Clash cards.").withStyle(ChatFormatting.GOLD), true);
					changed = true;
					break;
				}
			}
		}
		if (changed) inv.setChanged();
	}

	/** Take every Smash&Clash card out of a player's inventory (they left the table, or the game is over). */
	public static void clear(ServerPlayer player) {
		sync(player, null, "");
	}
}
