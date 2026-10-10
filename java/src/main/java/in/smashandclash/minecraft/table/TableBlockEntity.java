package in.smashandclash.minecraft.table;

import in.smashandclash.minecraft.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** The table's block entity: keeps the game in progress (seat tokens) across restarts and runs the session. */
public class TableBlockEntity extends BlockEntity {
	/** The session's saved state (JSON). */
	String saved = "";
	private TableSession session;

	public TableBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.TABLE_ENTITY, pos, state);
	}

	public TableSession session() {
		if (session == null) session = new TableSession(this, (ServerLevel) level);
		return session;
	}

	public static void tick(Level level, BlockPos pos, BlockState state, TableBlockEntity be) {
		be.session().tick();
	}

	void save(String json) {
		if (!json.equals(saved)) {
			saved = json;
			setChanged();
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		saved = input.getStringOr("session", "");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putString("session", saved);
	}

	/** The block is going away (broken): end the game and clear the table. */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (session != null) session.destroy();
		else if (level instanceof ServerLevel sl) new Board(sl, pos, state.getValue(TableBlock.FACING)).clearAll();
		session = null;
	}

	/** Unloaded (chunk unload, server stop): stop talking to the API; the game resumes on load. */
	@Override
	public void setRemoved() {
		super.setRemoved();
		if (session != null) session.unload();
		session = null;
	}
}
