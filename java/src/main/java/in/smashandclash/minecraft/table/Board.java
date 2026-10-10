package in.smashandclash.minecraft.table;

import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

/**
 * The table's geometry and the display entities that draw it: the board, a card on each tile
 * (with the rim that says whose it is), the special tiles, the labels and the score sign.
 * Every entity sits at the board's centre and is placed by its transformation, so moving a card
 * is one interpolated change that every player nearby sees.
 *
 * Board frame: x = seat A's right (column A on the left, E on the right), y = up,
 * z = toward seat A (row 1 is nearest seat A, row 3 nearest seat B).
 */
public final class Board {
	/** The board's size: a folding board you set on a table (about 1.7 x 1.4 blocks). */
	public static final float S = 0.55f;
	/** Texture pixels per block (the pipeline draws the board at 512 x 416). */
	static final float PX = 165f / S;
	public static final float WIDTH = 512 / PX;
	public static final float DEPTH = 416 / PX;
	public static final float COL = 92 / PX;
	public static final float ROW = 122 / PX;
	static final float THICK = 0.07f;
	/** The tabletop the board and the deck rest on (dark oak), on top of the pedestal. */
	static final float TOP_H = 0.08f;
	/** A card on the table is 0.48 x 0.655 blocks at full size (S = 1): the card model (11 x 15 px of 16) at this scale. */
	static final float CARD_SCALE = 0.698f * S;
	static final String TAG = "smashandclash";

	public final ServerLevel level;
	public final BlockPos pos;
	/** From seat A toward seat B. */
	public final Direction forward;
	public final Vec3 center;
	private final Quaternionf frame;

	public Board(ServerLevel level, BlockPos pos, Direction forward) {
		this.level = level;
		this.pos = pos;
		this.forward = forward;
		this.center = new Vec3(pos.getX() + 0.5, pos.getY() + 1 + TOP_H + THICK, pos.getZ() + 0.5);
		Direction right = forward.getClockWise();
		Vector3f r = new Vector3f(right.getStepX(), 0, right.getStepZ());
		Vector3f up = new Vector3f(0, 1, 0);
		Vector3f back = new Vector3f(-forward.getStepX(), 0, -forward.getStepZ());
		this.frame = new Quaternionf().setFromNormalized(new Matrix3f(r, up, back));
	}

	/** The board's orientation (board frame -> world). */
	public Quaternionf frameCopy() {
		return new Quaternionf(frame);
	}

	/** The tag every entity of this table carries (to clear them all when the table goes). */
	public String tableTag() {
		return "snc@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	/* ------------------------------- geometry ------------------------------- */

	/** A tile's centre in the board frame. */
	public static Vector3f local(int r, int c) {
		return new Vector3f((c - 2) * COL, 0, (1 - r) * ROW);
	}

	/** A board-frame point -> the transformation translation (relative to the centre). */
	public Vector3f toWorld(Vector3f local) {
		return frame.transform(new Vector3f(local));
	}

	/** A board-frame point -> a world position. */
	public Vec3 world(Vector3f local) {
		Vector3f w = toWorld(local);
		return center.add(w.x, w.y, w.z);
	}

	/** A world position -> the board frame. */
	public Vector3f toLocal(Vec3 world) {
		Vector3f d = new Vector3f((float) (world.x - center.x), (float) (world.y - center.y), (float) (world.z - center.z));
		return frame.conjugate(new Quaternionf()).transform(d);
	}

	/** The world box of a tile (for clicks). */
	public AABB tileBox(int r, int c) {
		Vec3 p = world(local(r, c));
		return new AABB(p.x - 0.3, p.y - 0.1, p.z - 0.3, p.x + 0.3, p.y + 0.25, p.z + 0.3);
	}

	/** The tile under a world point on the board, or null. */
	public int[] tileAt(Vec3 hit) {
		Vector3f d = new Vector3f((float) (hit.x - center.x), (float) (hit.y - center.y), (float) (hit.z - center.z));
		frame.conjugate(new Quaternionf()).transform(d);
		int c = Math.round(d.x / COL) + 2;
		int r = 1 - Math.round(d.z / ROW);
		if (r < 0 || r > 2 || c < 0 || c > 4) return null;
		if (Math.abs(d.x - (c - 2) * COL) > COL / 2 || Math.abs(d.z - (1 - r) * ROW) > ROW / 2) return null;
		return new int[] {r, c};
	}

	/** A card lying on tile (r, c) facing its owner (cards face the player they belong to), lifted by `lift`. */
	public Transformation cardAt(int r, int c, String owner, float lift, float scale, float spin) {
		Quaternionf q = new Quaternionf(frame);
		if ("B".equals(owner)) q.rotateY((float) Math.PI);
		if (spin != 0) q.rotateY(spin);
		q.rotateX((float) (-Math.PI / 2));
		Vector3f t = toWorld(local(r, c).add(0, 0.013f + lift, 0));
		return new Transformation(t, q, new Vector3f(CARD_SCALE * scale), new Quaternionf());
	}

	/** Something flat on a tile (a rim, a glow, a marker), w x h blocks, `y` above the board. */
	public Transformation flatAt(int r, int c, float w, float h, float y, String facing) {
		Quaternionf q = new Quaternionf(frame);
		if ("B".equals(facing)) q.rotateY((float) Math.PI);
		q.rotateX((float) (-Math.PI / 2));
		return new Transformation(toWorld(local(r, c).add(0, y, 0)), q, new Vector3f(w, h, 1), new Quaternionf());
	}

	/** A sprite standing on tile (r, c): `size` blocks tall, bottom `y` above the board. */
	public Transformation standingAt(int r, int c, float size, float y) {
		Vector3f t = toWorld(local(r, c).add(0, y + size / 2, 0));
		return new Transformation(t, new Quaternionf(), new Vector3f(size), new Quaternionf());
	}

	/* -------------------------------- entities -------------------------------- */

	private <T extends Entity> T tag(T e) {
		e.addTag(TAG);
		e.addTag(tableTag());
		return e;
	}

	/** An item display at the board centre showing `stack`, placed by `t`. */
	public Display.ItemDisplay item(ItemStack stack, Transformation t) {
		Display.ItemDisplay d = tag(new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level));
		d.setPos(center.x, center.y, center.z);
		d.setItemStack(stack);
		d.setItemTransform(ItemDisplayContext.NONE);
		d.setTransformation(t);
		d.setBrightnessOverride(new Brightness(15, 15));
		d.setViewRange(0.75f);
		level.addFreshEntity(d);
		return d;
	}

	public Display.TextDisplay text(Component text, Transformation t, int background, boolean billboard) {
		Display.TextDisplay d = tag(new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level));
		d.setPos(center.x, center.y, center.z);
		d.setText(text);
		d.setBackgroundColor(background);
		d.setLineWidth(400);
		d.setTransformation(t);
		d.setBrightnessOverride(new Brightness(15, 15));
		if (billboard) d.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		level.addFreshEntity(d);
		return d;
	}

	public Display.BlockDisplay block(net.minecraft.world.level.block.state.BlockState state, Transformation t) {
		Display.BlockDisplay d = tag(new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level));
		d.setPos(center.x, center.y, center.z);
		d.setBlockState(state);
		d.setTransformation(t);
		d.setBrightnessOverride(new Brightness(15, 15));
		level.addFreshEntity(d);
		return d;
	}

	/** An invisible hit box over tile (r, c): right-click it to play there. */
	public Interaction tileHitBox(int r, int c) {
		Interaction i = tag(new Interaction(EntityTypes.INTERACTION, level));
		Vec3 p = world(local(r, c));
		i.setPos(p.x, center.y - 0.02, p.z);
		i.setWidth(Math.min(COL, ROW) - 0.02f);
		i.setHeight(0.12f);
		i.setResponse(true);
		i.addTag("snc_tile_" + r + "_" + c);
		level.addFreshEntity(i);
		return i;
	}

	/** Move a display to `t` over `ticks` (every client nearby interpolates it). */
	public static void animate(Display d, Transformation t, int ticks) {
		d.setTransformation(t);
		d.setTransformationInterpolationDuration(ticks);
		d.setTransformationInterpolationDelay(0);
	}

	/** Remove every entity of this table (and any left from before a restart). */
	public int clearAll() {
		AABB area = new AABB(pos).inflate(4);
		List<Entity> all = level.getEntitiesOfClass(Entity.class, area, e -> e.entityTags().contains(tableTag()));
		for (Entity e : all) e.discard();
		return all.size();
	}

	/* --------------------------------- stacks --------------------------------- */

	static ItemStack modelStack(String model) {
		ItemStack s = new ItemStack(Items.PAPER);
		s.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("smashandclash", model));
		return s;
	}

	/** A card on the table (always face up). */
	public static ItemStack boardCard(int cardId) {
		return modelStack("board/" + cardId);
	}

	/**
	 * The rim under a card: tinted on each client (blue for your cards, orange for theirs; a
	 * spectator sees seat A blue and seat B orange). It carries both seats' player ids.
	 */
	public static ItemStack rim(String seat, UUID a, UUID b) {
		ItemStack s = modelStack("rim");
		CompoundTag tag = new CompoundTag();
		tag.putString("seat", seat);
		if (a != null) tag.putString("a", a.toString());
		if (b != null) tag.putString("b", b.toString());
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	/** A glowing tile ring in a colour (0xRRGGBB). */
	public static ItemStack glow(int rgb) {
		ItemStack s = modelStack("glow");
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(), List.of(rgb)));
		return s;
	}
}
