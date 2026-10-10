package in.smashandclash.minecraft.client;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jspecify.annotations.Nullable;

/**
 * The rim under a card on the table, coloured for whoever looks at it: your cards blue, theirs
 * orange (the website's viewer-relative colours). Someone watching sees seat A blue and seat B orange.
 */
public record SeatTint() implements ItemTintSource {
	public static final MapCodec<SeatTint> MAP_CODEC = MapCodec.unit(new SeatTint());
	static final int YOU = 0x2173e8, THEM = 0xf4581f;

	@Override
	public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner) {
		CustomData d = stack.get(DataComponents.CUSTOM_DATA);
		if (d == null) return ARGB.opaque(0xffffff);
		CompoundTag t = d.copyTag();
		String seat = t.getStringOr("seat", "A");
		var me = Minecraft.getInstance().player;
		String mine = null;
		if (me != null) {
			String id = me.getUUID().toString();
			if (id.equals(t.getStringOr("a", ""))) mine = "A";
			else if (id.equals(t.getStringOr("b", ""))) mine = "B";
		}
		boolean blue = mine != null ? seat.equals(mine) : "A".equals(seat);
		return ARGB.opaque(blue ? YOU : THEM);
	}

	@Override
	public MapCodec<SeatTint> type() {
		return MAP_CODEC;
	}
}
