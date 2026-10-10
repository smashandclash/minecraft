package in.smashandclash.minecraft;

import in.smashandclash.minecraft.table.TableBlock;
import in.smashandclash.minecraft.table.TableBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.Set;

/** The table (a block you place, with the game on it) and the card (an item in your hand). */
public final class ModContent {
	private ModContent() {}

	public static Block TABLE;
	public static Item TABLE_ITEM;
	public static Item CARD;
	public static BlockEntityType<TableBlockEntity> TABLE_ENTITY;

	public static void register() {
		ResourceKey<Block> tableKey = ResourceKey.create(Registries.BLOCK, SmashAndClashMod.id("table"));
		TABLE = Registry.register(BuiltInRegistries.BLOCK, tableKey, new TableBlock(BlockBehaviour.Properties.of()
			.setId(tableKey)
			.mapColor(MapColor.SAND)
			.strength(1.5f)
			.sound(SoundType.WOOD)
			.noOcclusion()));
		ResourceKey<Item> tableItemKey = ResourceKey.create(Registries.ITEM, SmashAndClashMod.id("table"));
		TABLE_ITEM = Registry.register(BuiltInRegistries.ITEM, tableItemKey, new BlockItem(TABLE, new Item.Properties().setId(tableItemKey).useBlockDescriptionPrefix()));
		ResourceKey<Item> cardKey = ResourceKey.create(Registries.ITEM, SmashAndClashMod.id("card"));
		CARD = Registry.register(BuiltInRegistries.ITEM, cardKey, new Item(new Item.Properties().setId(cardKey).stacksTo(1)));
		TABLE_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, SmashAndClashMod.id("table"), new BlockEntityType<>(TableBlockEntity::new, Set.of(TABLE)));
	}
}
