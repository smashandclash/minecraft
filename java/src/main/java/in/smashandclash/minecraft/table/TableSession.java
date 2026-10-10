package in.smashandclash.minecraft.table;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.math.Transformation;
import in.smashandclash.minecraft.Deck;
import in.smashandclash.minecraft.Persona;
import in.smashandclash.minecraft.SmashAndClashMod;
import in.smashandclash.minecraft.api.Game;
import in.smashandclash.minecraft.api.Links;
import in.smashandclash.minecraft.api.Model;
import in.smashandclash.minecraft.api.SmashAndClash;
import in.smashandclash.minecraft.api.SmashAndClashError;
import in.smashandclash.minecraft.net.Payloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * One table's game. The server holds the seats of the players at this table (their player tokens)
 * and plays them on the Smash&Clash games API; the other seat is a player at this same table, the
 * house, or anyone on any client (the website, the apps, the CLI, Telegram...). Every move is
 * drawn on the table with the website's own animation beats, for everyone nearby to see, and each
 * seated player's hand lives in their inventory.
 */
public final class TableSession {
	public static final SmashAndClash SC = new SmashAndClash("minecraft/" + SmashAndClashMod.VERSION);
	private static final ExecutorService NET = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "smashandclash-net");
		t.setDaemon(true);
		return t;
	});
	private static final Gson GSON = new Gson();
	/** One website unit (a card's width) in blocks. */
	static final float U = 0.48f * Board.S;
	static final int BLUE = 0x2173e8, ORANGE = 0xf4581f, INK = 0x0d1a4a;
	static final FontDescription DISPLAY_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("smashandclash", "display"));
	private static final String[][] COMIC = {
		{"SMASH!", "BAM!", "WHACK!", "POW!", "CRUNCH!"},
		{"DOUBLE SMASH!", "TWO DOWN!", "DOUBLE TROUBLE!", "ONE-TWO!"},
		{"TRIPLE SMASH!", "THREE IN A ROW!", "HAT-TRICK!", "TRIPLE THREAT!"},
		{"OVERKILL!!", "TOTAL WIPEOUT!!", "CLEAN SWEEP!!", "ALL FOUR!!"},
	};

	/* ------------------------------- the seats ------------------------------- */

	/** A seat held by a player at this table. */
	static final class LocalSeat {
		UUID player;
		String name;
		Game game;
		int rating;
		Model.PlayerView view;
		/** RECRUIT: the enemy card picked, waiting for the empty tile. */
		String recruitFrom;
		/** FLIP / SWAP: armed until (game time), a second click plays it. */
		String armed;
		long armedUntil;
		/** Private glow rings: cell -> fake entity id (only this player sees them). */
		final Map<String, Integer> glows = new HashMap<>();
		int glowColor;
	}

	/** What a table saves: enough to pick the game up again after a restart. */
	static final class Saved {
		String mode;
		String ruleset = "mutators";
		String gameId;
		int strength;
		String code;
		SavedSeat[] seats = new SavedSeat[2];
	}

	static final class SavedSeat {
		String player, name, token;
		int rating;
	}

	/** A card on the table: its displays and its pose in the board frame (the website's Pose). */
	final class CardView {
		final int cardId;
		String owner;
		boolean frozen;
		Display.ItemDisplay card;
		Display.ItemDisplay rim;
		Display.BlockDisplay ice;
		final Vector3f pos = new Vector3f();
		float lift, scale = 1, spin, tilt;

		CardView(int cardId, String owner) {
			this.cardId = cardId;
			this.owner = owner;
		}

		Transformation cardT() {
			Quaternionf q = board.frameCopy();
			q.rotateY(("B".equals(owner) ? (float) Math.PI : 0) + spin);
			q.rotateX((float) (-Math.PI / 2) + tilt);
			q.rotateY((float) Math.PI); // an item display turns its item round: face up again
			Vector3f t = board.toWorld(new Vector3f(pos).add(0, 0.013f + lift, 0));
			return new Transformation(t, q, new Vector3f(Board.CARD_SCALE * scale), new Quaternionf());
		}

		Transformation rimT() {
			Quaternionf q = board.frameCopy();
			q.rotateY(("B".equals(owner) ? (float) Math.PI : 0) + spin);
			q.rotateX((float) (-Math.PI / 2) + tilt);
			Vector3f t = board.toWorld(new Vector3f(pos).add(0, 0.006f + lift, 0));
			return new Transformation(t, q, new Vector3f(0.53f * Board.S * scale, 0.705f * Board.S * scale, 1), new Quaternionf());
		}

		Transformation iceT() {
			Quaternionf q = board.frameCopy();
			Vector3f size = new Vector3f(0.52f * Board.S * scale, 0.03f, 0.69f * Board.S * scale);
			Vector3f corner = new Quaternionf(q).transform(new Vector3f(-size.x / 2, 0, -size.z / 2));
			Vector3f t = board.toWorld(new Vector3f(pos).add(0, 0.012f + lift, 0)).add(corner);
			return new Transformation(t, q, size, new Quaternionf());
		}

		/** Put the displays where the pose says (interpolated over one tick). */
		void apply() {
			if (card != null) Board.animate(card, cardT(), 1);
			if (rim != null) Board.animate(rim, rimT(), 1);
			if (ice != null) Board.animate(ice, iceT(), 1);
		}

		void showRim() {
			if (rim == null) rim = board.item(Board.rim(owner, seatPlayer(0), seatPlayer(1)), rimT());
			else rim.setItemStack(Board.rim(owner, seatPlayer(0), seatPlayer(1)));
		}

		void remove() {
			if (card != null) card.discard();
			if (rim != null) rim.discard();
			if (ice != null) ice.discard();
			card = null;
			rim = null;
			ice = null;
		}
	}

	/* -------------------------------- the state -------------------------------- */

	private final TableBlockEntity be;
	private final ServerLevel level;
	private final MinecraftServer server;
	final Board board;
	private final Tween tween = new Tween();
	private final String tableKey;

	private Saved saved = new Saved();
	private final LocalSeat[] local = new LocalSeat[2];
	/** idle | starting | waiting | playing | over */
	private String status = "idle";
	private String message = "";
	private Model.GameState state;
	private Model.SeatSync lastSync;
	private final ArrayDeque<Model.SeatMove> queue = new ArrayDeque<>();
	private int appliedSince;
	private boolean animating;
	/** The board as drawn (advances one animated move at a time). */
	private Model.SeatState shown;
	private final CardView[][] cells = new CardView[3][5];
	private final List<Display> markers = new ArrayList<>();
	private String markerKey = "";
	private Display.TextDisplay sign;
	private String signText = "";
	private boolean built;
	private int pumpGen;
	private boolean disposed;
	private boolean finished;
	private boolean firstBlood;
	private boolean started;
	private long quietUntil;
	private int attackerCardId = -1;
	private Crowd crowd;
	private net.minecraft.world.entity.Entity opponent;
	private String opponentSeat;
	private final List<Display.ItemDisplay> deckPile = new ArrayList<>();
	private Display.TextDisplay deckLabel;
	private Display.ItemDisplay deckStand;
	private int deckShown = -1;
	private Display.ItemDisplay halfLeft, halfRight;
	private Display.BlockDisplay tabletop;
	private Display.ItemDisplay turnStrip;
	private String turnStripFor = "";
	private Display.TextDisplay thinking;
	private boolean unfolding;
	private int[] lastTile;
	private long lastHint;
	private Display.ItemDisplay showcase;
	/** Which card attacked last this turn, and how many times (a hop chain). */
	private int attackerCell = -1;
	private int chainLinks;

	TableSession(TableBlockEntity be, ServerLevel level) {
		this.be = be;
		this.level = level;
		this.server = level.getServer();
		this.board = new Board(level, be.getBlockPos(), be.getBlockState().getValue(TableBlock.FACING));
		this.tableKey = board.tableTag();
		this.crowd = new Crowd(level, board);
		if (be.saved != null && !be.saved.isEmpty()) {
			try {
				saved = GSON.fromJson(be.saved, Saved.class);
			} catch (Exception e) {
				saved = new Saved();
			}
			if (saved.seats == null) saved.seats = new SavedSeat[2];
		}
	}

	/* -------------------------------- lifecycle -------------------------------- */

	void tick() {
		if (disposed) return;
		if (!built) build();
		tween.tick();
		if (!animating && !queue.isEmpty()) playNext();
		long t = level.getGameTime();
		if (t % 4 == 0) updateGlows();
		if (t % 20 == 0) hints();
		if (t % 40 == 0) crowd.tick("playing".equals(status));
		if ("playing".equals(status)) crowd.look(turnTarget());
		if (t % 10 == 0) opponentUpkeep();
		cleanDroppedCards();
	}

	/** First tick: clear anything left from before, lay out the board, resume a saved game. */
	private void build() {
		built = true;
		boolean fresh = board.clearAll() == 0 && saved.gameId == null;
		tabletop = board.block(Blocks.DARK_OAK_PLANKS.defaultBlockState(), tabletopT());
		footprint(true);
		halfLeft = board.item(Board.modelStack("board_left"), halfT(-1, 0, 0, 0));
		halfRight = board.item(Board.modelStack("board_right"), halfT(1, fresh ? (float) Math.PI : 0, 0, 0));
		for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) board.tileHitBox(r, c);
		if (fresh) unfold();
		else labels();
		renderDeck(0);
		sign = board.text(Component.empty(), new Transformation(new Vector3f(0, 0.95f, 0), null, new Vector3f(0.6f), null), argb(0xd0, INK), true);
		if (saved.gameId != null) resume();
		else refreshSign();
	}

	/**
	 * One half of the folding board. The right half turns `theta` about the hinge (the seam down the
	 * middle, at the board's top): pi = folded over the left half, 0 = open. `yaw` turns the whole
	 * board (FLIP), `lift` raises it.
	 */
	private Transformation halfT(int side, float theta, float yaw, float lift) {
		Quaternionf turn = board.frameCopy().rotateY(yaw);
		Quaternionf hinge = new Quaternionf().rotateZ(side > 0 ? theta : 0);
		Vector3f c = hinge.transform(new Vector3f(side * Board.WIDTH / 4, -Board.THICK / 2, 0)).add(0, lift, 0);
		Quaternionf q = new Quaternionf(turn).mul(hinge).rotateY((float) Math.PI);
		return new Transformation(new Quaternionf(turn).transform(c), q, new Vector3f(Board.WIDTH / 2, Board.THICK, Board.DEPTH), new Quaternionf());
	}

	/**
	 * The tabletop is drawn, not built, so nothing would stop a villager (or a player) walking
	 * through it: fill the ground under it with invisible barriers, the way a real table takes up
	 * room. They come out with the table.
	 */
	private void footprint(boolean place) {
		float hw = (Board.WIDTH + 0.9f) / 2, hd = (Board.DEPTH + 0.24f) / 2;
		Vec3 a = board.world(new Vector3f(-hw, 0, -hd)), b = board.world(new Vector3f(hw, 0, hd));
		double x0 = Math.min(a.x, b.x), x1 = Math.max(a.x, b.x), z0 = Math.min(a.z, b.z), z1 = Math.max(a.z, b.z);
		for (int x = (int) Math.floor(x0); x <= (int) Math.floor(x1); x++) {
			for (int z = (int) Math.floor(z0); z <= (int) Math.floor(z1); z++) {
				BlockPos bp = new BlockPos(x, board.pos.getY(), z);
				if (bp.equals(board.pos)) continue;
				// the block's own square must be mostly under the tabletop
				double ox = Math.min(x + 1, x1) - Math.max(x, x0), oz = Math.min(z + 1, z1) - Math.max(z, z0);
				if (ox < 0.5 || oz < 0.5) continue;
				var st = level.getBlockState(bp);
				if (place && st.isAir()) level.setBlockAndUpdate(bp, Blocks.BARRIER.defaultBlockState());
				else if (!place && st.is(Blocks.BARRIER)) level.setBlockAndUpdate(bp, Blocks.AIR.defaultBlockState());
			}
		}
	}

	/** The dark oak tabletop: wide enough for the board and the deck beside it. */
	private Transformation tabletopT() {
		Quaternionf q = board.frameCopy();
		Vector3f size = new Vector3f(Board.WIDTH + 0.9f, Board.TOP_H, Board.DEPTH + 0.24f);
		Vector3f corner = new Quaternionf(q).transform(new Vector3f(-size.x / 2, 0, -size.z / 2));
		return new Transformation(board.toWorld(new Vector3f(0, -Board.THICK - Board.TOP_H, 0)).add(corner), q, size, new Quaternionf());
	}

	/** A new table: the board lands folded, then opens on its hinge (the website's board unfolds the same way). */
	private void unfold() {
		unfolding = true;
		at(0, () -> Sfx.play(level, board.center, "sfx.whoosh"));
		tween.run(0, 300, Tween.OUT_BACK, e -> {
			float lift = (float) (0.45 * (1 - e));
			Board.animate(halfLeft, halfT(-1, 0, 0, lift), 1);
			Board.animate(halfRight, halfT(1, (float) Math.PI, 0, lift), 1);
		}, () -> Sfx.play(level, board.center, "sfx.card_place", 0.8f));
		tween.run(350, 750, Tween.IN_OUT_CUBIC, e -> Board.animate(halfRight, halfT(1, (float) (Math.PI * (1 - e)), 0, 0), 1), () -> {
			Sfx.play(level, board.center, "sfx.card_place");
			particles(new DustParticleOptions(0xe8d8b8, 0.7f), new int[] {1, 2}, 16, 0.4);
		});
		tween.run(1100, 240, Tween.LINEAR, e -> {
			double w = Math.exp(-4.5 * e) * Math.sin(12 * e);
			Board.animate(halfRight, halfT(1, (float) (-0.08 * w), 0, 0), 1);
		}, () -> {
			labels();
			unfolding = false;
		});
	}

	private final List<Display> boardLabels = new ArrayList<>();

	private void labels() {
		for (Display l : boardLabels) l.discard();
		boardLabels.clear();
		for (int c = 0; c < 5; c++) {
			String col = String.valueOf("ABCDE".charAt(c));
			flatText(col, new Vector3f((c - 2) * Board.COL, 0.004f, Board.DEPTH / 2 - 0.115f * Board.S), 0.45f * Board.S, "A");
			flatText(col, new Vector3f((c - 2) * Board.COL, 0.004f, -Board.DEPTH / 2 + 0.115f * Board.S), 0.45f * Board.S, "B");
		}
		for (int r = 0; r < 3; r++) {
			String row = String.valueOf(r + 1);
			flatText(row, new Vector3f(-Board.WIDTH / 2 + 0.09f * Board.S, 0.004f, (1 - r) * Board.ROW + 0.05f * Board.S), 0.45f * Board.S, "A");
			flatText(row, new Vector3f(Board.WIDTH / 2 - 0.09f * Board.S, 0.004f, (1 - r) * Board.ROW - 0.05f * Board.S), 0.45f * Board.S, "B");
		}
	}

	/** Text lying on the board, upright for the seat it faces. */
	private Display.TextDisplay flatText(String s, Vector3f local, float scale, String facing) {
		Quaternionf q = board.frameCopy();
		if ("B".equals(facing)) q.rotateY((float) Math.PI);
		q.rotateX((float) (-Math.PI / 2));
		Display.TextDisplay d = board.text(Component.literal(s).withStyle(Style.EMPTY.withFont(DISPLAY_FONT).withColor(0x7a5a32)), new Transformation(board.toWorld(local), q, new Vector3f(scale), null), 0, false);
		if (s.length() == 1 && Character.isLetterOrDigit(s.charAt(0))) boardLabels.add(d);
		return d;
	}

	/** The chunk unloaded or the server stops: stop polling; the game resumes when the table loads. */
	void unload() {
		disposed = true;
		pumpGen++;
		for (LocalSeat s : local) if (s != null) clearGlows(s);
	}

	/** The table was broken: leave or resign the game, take the cards back, clear the board. */
	void destroy() {
		unload();
		for (LocalSeat s : local) {
			if (s == null || s.game == null) continue;
			Game g = s.game;
			NET.execute(() -> {
				try {
					if (g.waiting()) g.leave();
					else if (!g.over()) g.resign();
				} catch (Exception ignored) {
				}
			});
			ServerPlayer p = player(s);
			if (p != null) {
				Hands.clear(p);
				seated(p, "", "");
			}
		}
		board.clearAll();
		footprint(false);
		saved = new Saved();
	}

	/* ---------------------------------- menu ---------------------------------- */

	/** Open the table's menu on a player's screen. */
	public void openMenu(ServerPlayer p) {
		ServerPlayNetworking.send(p, new Payloads.TableMenu(board.pos, menuJson(p)));
	}

	private String menuJson(ServerPlayer p) {
		JsonObject o = new JsonObject();
		o.addProperty("status", status);
		o.addProperty("mode", saved.mode == null ? "" : saved.mode);
		o.addProperty("message", message);
		o.addProperty("ruleset", Players.get(server, p.getUUID()).ruleset);
		if (saved.code != null) {
			o.addProperty("code", saved.code);
			o.addProperty("joinLink", Links.joinLink(saved.code, SmashAndClash.DEFAULT_BASE_URL));
		}
		JsonArray seats = new JsonArray();
		for (int i = 0; i < 2; i++) {
			JsonObject s = new JsonObject();
			s.addProperty("seat", i == 0 ? "A" : "B");
			s.addProperty("name", seatName(i));
			s.addProperty("you", local[i] != null && local[i].player.equals(p.getUUID()));
			s.addProperty("here", local[i] != null);
			seats.add(s);
		}
		o.add("seats", seats);
		o.addProperty("seated", seatOf(p) >= 0);
		o.addProperty("canTake", "waiting".equals(status) && "room".equals(saved.mode) && seatOf(p) < 0 && saved.code != null);
		Players.Rec rec = Players.get(server, p.getUUID());
		o.addProperty("rating", rec.rating);
		o.addProperty("played", rec.played);
		o.addProperty("won", rec.won);
		if (state != null && state.score != null) {
			o.addProperty("scoreA", state.score.A);
			o.addProperty("scoreB", state.score.B);
		}
		return o.toString();
	}

	/** A choice from the menu. */
	public void action(ServerPlayer p, String action, String arg) {
		switch (action) {
			case "house", "quick", "room", "join" -> start(p, action, arg);
			case "take" -> takeSeat(p);
			case "cancel" -> cancel(p);
			case "resign" -> resign(p);
			case "ruleset" -> {
				Players.get(server, p.getUUID()).ruleset = "classic".equals(arg) ? "classic" : "mutators";
				Players.save(server);
				openMenu(p);
			}
			default -> {
			}
		}
	}

	private boolean busyTable() {
		return "starting".equals(status) || "waiting".equals(status) || "playing".equals(status);
	}

	private void start(ServerPlayer p, String mode, String arg) {
		if (busyTable()) {
			say(p, "This table is in use. Find another table, or wait for this game to end.");
			return;
		}
		clearGame();
		Players.Rec rec = Players.get(server, p.getUUID());
		String name = nameOf(p);
		String ruleset = rec.ruleset;
		int rating = rec.rating;
		int strength = Math.max(800, Math.min(1600, rating));
		status = "starting";
		message = switch (mode) {
			case "house" -> "Finding an opponent at your level...";
			case "quick" -> "Looking for an opponent...";
			case "room" -> "Opening a room...";
			default -> "Joining...";
		};
		saved.mode = mode;
		saved.ruleset = ruleset;
		refreshSign();
		UUID id = p.getUUID();
		int gen = ++pumpGen;
		call(() -> {
			SmashAndClash.Opts o = new SmashAndClash.Opts().name(name).as("person").rating(rating);
			return switch (mode) {
				case "house" -> SC.games.startHouse(o.ruleset(ruleset).strength(strength));
				case "quick" -> SC.games.quickMatch(o.ruleset(ruleset).opponent("any"));
				case "room" -> SC.games.createDuel(o.ruleset(ruleset));
				default -> {
					SmashAndClash.Opened opened = SC.games.open(arg, o);
					if (!"game".equals(opened.kind()))
						throw new SmashAndClashError(409, "full", "That game is already full: watch it at " + Links.watchLink(opened.watched() != null ? opened.watched().id : "", SmashAndClash.DEFAULT_BASE_URL), null);
					yield opened.game();
				}
			};
		}, g -> {
			if (gen != pumpGen) return;
			saved.strength = strength;
			sit(g, id, name, rating);
			if ("house".equals(mode)) Sfx.play(level, board.center, "sfx.match_found");
			startPump(false);
		}, e -> {
			status = "idle";
			message = errorText(e);
			saved = new Saved();
			persist();
			ServerPlayer pl = server.getPlayerList().getPlayer(id);
			if (pl != null) say(pl, message);
			refreshSign();
		});
	}

	/** Seat a player here with their game. */
	private void sit(Game g, UUID player, String name, int rating) {
		int i = "B".equals(g.seat()) ? 1 : 0;
		LocalSeat s = new LocalSeat();
		s.player = player;
		s.name = name;
		s.game = g;
		s.rating = rating;
		s.view = g.view();
		local[i] = s;
		state = g.state;
		saved.gameId = g.id();
		saved.code = g.code();
		SavedSeat ss = new SavedSeat();
		ss.player = player.toString();
		ss.name = name;
		ss.token = g.playerToken;
		ss.rating = rating;
		saved.seats[i] = ss;
		status = g.waiting() ? "waiting" : "playing";
		message = "";
		persist();
		ServerPlayer p = server.getPlayerList().getPlayer(player);
		if (p != null) {
			seated(p, g.waiting() ? "matchmaking" : "battle", "");
			if (g.waiting() && g.code() != null) {
				p.sendSystemMessage(Component.literal("Room ").append(Component.literal(g.code()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
					.append(Component.literal(": anyone at this table can take the other seat, or join with the code on any Smash&Clash client. "))
					.append(Component.literal("[Copy link]").withStyle(Style.EMPTY.withColor(0x5fa6ff).withClickEvent(new ClickEvent.CopyToClipboard(g.joinLink())))));
			}
		}
		refreshSign();
	}

	/** The name a seat plays under: the player's own (a recording run may set -Dsnc.playerName). */
	static String nameOf(ServerPlayer p) {
		String o = System.getProperty("snc.playerName");
		return o != null && !o.isBlank() ? o : p.getGameProfile().name();
	}

	private void takeSeat(ServerPlayer p) {
		if (!"waiting".equals(status) || saved.code == null || seatOf(p) >= 0) return;
		Players.Rec rec = Players.get(server, p.getUUID());
		String name = nameOf(p);
		UUID id = p.getUUID();
		String code = saved.code;
		call(() -> SC.games.joinDuel(code, new SmashAndClash.Opts().name(name).as("person").rating(rec.rating)), g -> {
			sit(g, id, name, rec.rating);
		}, e -> say(p, errorText(e)));
	}

	private void cancel(ServerPlayer p) {
		int i = seatOf(p);
		if (i < 0 || !"waiting".equals(status)) return;
		Game g = local[i].game;
		call(() -> g.leave(), s -> endGame("Called off."), e -> {
			// someone got in first: the game is on
			if (e instanceof SmashAndClashError se && se.status == 409) say(p, "Someone just joined: the game is on!");
			else say(p, errorText(e));
		});
	}

	private void resign(ServerPlayer p) {
		int i = seatOf(p);
		if (i < 0 || !"playing".equals(status)) return;
		Game g = local[i].game;
		call(() -> g.resign(), s -> {}, e -> say(p, errorText(e)));
	}

	/** Pick a saved game up again (after a restart or the chunk reloading). */
	private void resume() {
		status = "starting";
		message = "Picking the game up again...";
		refreshSign();
		Saved sv = saved;
		int gen = ++pumpGen;
		call(() -> {
			Game[] games = new Game[2];
			for (int i = 0; i < 2; i++) if (sv.seats[i] != null) games[i] = SC.games.resume(sv.gameId, sv.seats[i].token);
			return games;
		}, games -> {
			if (gen != pumpGen) return;
			for (int i = 0; i < 2; i++) {
				if (games[i] == null) continue;
				LocalSeat s = new LocalSeat();
				s.player = UUID.fromString(sv.seats[i].player);
				s.name = sv.seats[i].name;
				s.game = games[i];
				s.rating = sv.seats[i].rating;
				s.view = games[i].view();
				local[i] = s;
				state = games[i].state;
			}
			Game g = primary();
			if (g == null || g.over()) {
				endGame("");
				return;
			}
			status = g.waiting() ? "waiting" : "playing";
			message = "";
			startPump(true);
		}, e -> endGame(""));
	}

	/** The game is over or called off: take the cards back, free the table. */
	private void endGame(String why) {
		for (LocalSeat s : local) {
			if (s == null) continue;
			ServerPlayer p = player(s);
			clearGlows(s);
			if (p != null) Hands.clear(p);
		}
		if (!"over".equals(status)) {
			for (LocalSeat s : local) {
				ServerPlayer p = s == null ? null : player(s);
				if (p != null) seated(p, "", "");
			}
			status = "idle";
		}
		message = why;
		pumpGen++;
		saved = new Saved();
		persist();
		refreshSign();
	}

	/** Clear the last game's board before a new one. */
	private void clearGame() {
		tween.clear();
		queue.clear();
		animating = false;
		for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) {
			if (cells[r][c] != null) cells[r][c].remove();
			cells[r][c] = null;
		}
		clearMarkers();
		if (showcase != null) showcase.discard();
		showcase = null;
		for (int i = 0; i < 2; i++) {
			if (local[i] != null) clearGlows(local[i]);
			local[i] = null;
		}
		crowd.dismiss();
		if (opponent != null) opponent.discard();
		opponent = null;
		state = null;
		lastSync = null;
		shown = null;
		appliedSince = 0;
		finished = false;
		firstBlood = false;
		started = false;
	}

	/* ------------------------------ talking to the API ------------------------------ */

	private interface Call<T> {
		T run() throws Exception;
	}

	/** Run a call off the server thread; land the result (or the error) back on it. */
	private <T> void call(Call<T> work, java.util.function.Consumer<T> ok, java.util.function.Consumer<Exception> fail) {
		NET.execute(() -> {
			try {
				T v = work.run();
				server.execute(() -> {
					if (!disposed) ok.accept(v);
				});
			} catch (Exception e) {
				SmashAndClashMod.LOGGER.warn("Smash&Clash API: {}", e.toString());
				server.execute(() -> {
					if (!disposed) fail.accept(e);
				});
			}
		});
	}

	private Game primary() {
		if (local[0] != null) return local[0].game;
		if (local[1] != null) return local[1].game;
		return null;
	}

	/**
	 * Follow the game: wait for the other player, then long-poll the seat sync for every move
	 * (with its public events) and refresh each local seat's view (hand, legal moves).
	 */
	private void startPump(boolean resuming) {
		int gen = ++pumpGen;
		NET.execute(() -> {
			int since = 0;
			boolean first = true;
			while (!disposed && gen == pumpGen) {
				try {
					Game g = primary();
					if (g == null) return;
					if (g.waiting()) {
						Model.GameState s = g.waitForTurn(20);
						if (!s.status.equals("waiting")) server.execute(() -> {
							if (gen != pumpGen) return;
							status = "playing";
							Sfx.play(level, board.center, "sfx.match_found");
							Sfx.play(level, board.center, "announcer.match_found");
							for (LocalSeat ls : local) {
								ServerPlayer p = ls == null ? null : player(ls);
								if (p != null) seated(p, "battle", "");
							}
							refreshSign();
						});
						continue;
					}
					Model.SeatSync s = g.sync(since, first ? 0 : 20);
					boolean news = first || s.moveCount != since || !Objects.equals(s.status, g.state.status);
					final boolean skip = first && resuming;
					since = s.moveCount;
					first = false;
					List<Model.PlayerView> views = new ArrayList<>();
					Model.GameState st = null;
					if (news) {
						for (LocalSeat ls : local) {
							if (ls == null) {
								views.add(null);
								continue;
							}
							st = ls.game.refresh();
							views.add(ls.game.view());
						}
					}
					final Model.GameState fst = st;
					server.execute(() -> {
						if (gen != pumpGen) return;
						applySync(s, skip);
						if (fst != null) state = fst;
						for (int i = 0; i < 2 && i < views.size(); i++) if (local[i] != null && views.get(i) != null) local[i].view = views.get(i);
						afterViews();
					});
					if ("finished".equals(s.status) || "abandoned".equals(s.status)) return;
				} catch (Exception e) {
					SmashAndClashMod.LOGGER.warn("Smash&Clash sync: {}", e.toString());
					try {
						Thread.sleep(3000);
					} catch (InterruptedException ie) {
						return;
					}
				}
			}
		});
	}

	private void applySync(Model.SeatSync s, boolean skipAnimation) {
		lastSync = s;
		if (skipAnimation || shown == null && s.moves.isEmpty()) {
			shown = s.state;
			appliedSince = s.moveCount;
			reconcile();
		}
		if (shown == null && !s.moves.isEmpty()) {
			// a fresh game where moves came first (the house opened): start from an empty table
			shown = emptyState(s.state);
			reconcile();
		}
		if (!started) {
			started = true;
			if (!skipAnimation) {
				Sfx.play(level, board.center, "sfx.match_start");
				Sfx.play(level, board.center, "announcer.match_start");
				deal(s.firstPlayer == null ? "A" : s.firstPlayer);
			}
		}
		for (Model.SeatMove m : s.moves) {
			if (m.index < appliedSince) continue;
			queue.add(m);
			appliedSince = m.index + 1;
		}
	}

	private static Model.SeatState emptyState(Model.SeatState like) {
		Model.SeatState e = new Model.SeatState();
		e.board = new Model.SeatCard[3][5];
		e.operatorTiles = like.operatorTiles;
		e.powerTiles = List.of();
		e.ruleset = like.ruleset;
		return e;
	}

	/** New views landed: hands, the sign, the end of the game. */
	private void afterViews() {
		if (!queue.isEmpty() || animating) return;
		for (LocalSeat s : local) {
			ServerPlayer p = s == null ? null : player(s);
			if (p != null && s.view != null) Hands.sync(p, s.view.hand, tableKey);
		}
		refreshSign();
		if (lastSync != null && ("finished".equals(lastSync.status) || "abandoned".equals(lastSync.status)) && !finished) finish();
	}

	/* ------------------------------- playing a card ------------------------------- */

	/** A player right-clicked tile (r, c). */
	public void onTile(ServerPlayer p, int r, int c, ItemStack held) {
		lastTile = new int[] {r, c};
		int i = seatOf(p);
		if (i < 0 || !"playing".equals(status)) {
			if (!busyTable() || p.isShiftKeyDown()) openMenu(p);
			else say(p, "You're watching. Right-click the table to see who's playing.");
			return;
		}
		if (p.isShiftKeyDown()) {
			openMenu(p);
			return;
		}
		LocalSeat s = local[i];
		Model.PlayerView v = s.view;
		if (animating || !queue.isEmpty()) return;
		if (v == null || !v.yourTurn) {
			deny(p, theirName(i) + "'s turn.");
			return;
		}
		String cell = Links.rcToCell(r, c);
		List<Model.MoveDetail> moves = v.moves == null ? List.of() : v.moves;
		// a pending hop: pick where it goes, or click the card itself to stay
		if (v.pendingHop != null) {
			for (Model.MoveDetail m : moves) {
				if (!"hop".equals(m.type)) continue;
				if (cell.equals(m.dest) || (m.dest == null && cell.equals(v.pendingHop.from))) {
					play(p, s, m.name);
					return;
				}
			}
			deny(p, "Hop to a glowing tile, or right-click your card to stay.");
			return;
		}
		int cardId = Hands.cardOf(held);
		if (cardId < 0 || !tableKey.equals(Hands.tableOf(held))) {
			deny(p, "Hold a card from your hand, then right-click a glowing tile.");
			return;
		}
		Model.HandCard hc = v.hand.stream().filter(h -> h.cardId == cardId).findFirst().orElse(null);
		if (hc == null) return;
		if (!"effect".equals(hc.kind)) {
			for (Model.MoveDetail m : moves) {
				if (Objects.equals(m.cardId, cardId) && cell.equals(m.cell) && ("place".equals(m.type) || "overrun".equals(m.type))) {
					play(p, s, m.name);
					return;
				}
			}
			deny(p, "That tile isn't open for " + hc.card + ".");
			return;
		}
		Deck.Card dc = Deck.card(cardId);
		String effect = dc != null && dc.effect != null ? dc.effect : "";
		List<Model.MoveDetail> mine = moves.stream().filter(m -> Objects.equals(m.cardId, cardId)).toList();
		if (mine.isEmpty()) {
			deny(p, hc.card + " can't do anything right now.");
			return;
		}
		switch (effect) {
			case "FLIP", "SWAP" -> {
				long now = level.getGameTime();
				if (effect.equals(s.armed) && now < s.armedUntil) {
					s.armed = null;
					play(p, s, mine.get(0).name);
				} else {
					s.armed = effect;
					s.armedUntil = now + 100;
					Sfx.to(p, "sfx.card_pick", 0.8f);
					p.sendSystemMessage(Component.literal(("FLIP".equals(effect) ? "Flip turns the board: every card changes sides." : "Swap trades your hand for theirs.") + " Right-click again to play it."), true);
				}
			}
			case "RECRUIT" -> {
				if (s.recruitFrom == null) {
					if (mine.stream().anyMatch(m -> cell.equals(m.target))) {
						s.recruitFrom = cell;
						Sfx.to(p, "sfx.card_pick", 0.8f);
						p.sendSystemMessage(Component.literal("Now right-click the empty tile to bring it to."), true);
					} else deny(p, "Pick an enemy card to recruit.");
				} else {
					for (Model.MoveDetail m : mine) {
						if (s.recruitFrom.equals(m.target) && cell.equals(m.dest)) {
							s.recruitFrom = null;
							play(p, s, m.name);
							return;
						}
					}
					if (cell.equals(s.recruitFrom)) s.recruitFrom = null;
					else deny(p, "Pick an empty tile for it.");
				}
			}
			default -> {
				for (Model.MoveDetail m : mine) {
					if (cell.equals(m.target)) {
						play(p, s, m.name);
						return;
					}
				}
				deny(p, "BOULDER".equals(effect) ? "Pick a card to smash." : "Pick one of your cards to freeze.");
			}
		}
	}

	private void play(ServerPlayer p, LocalSeat s, String move) {
		s.view.yourTurn = false; // no double plays while it is on its way
		clearGlows(s);
		if (lastTile != null) flash(p, lastTile[0], lastTile[1], RING_PRESS);
		Sfx.to(p, "sfx.card_pick", 0.8f);
		Game g = s.game;
		call(() -> g.play(move), st -> {
			state = st;
			s.view = g.view();
		}, e -> {
			deny(p, errorText(e));
			call(g::refresh, st -> s.view = g.view(), x -> {});
		});
	}

	/** Testing: pop a word over the middle of the board (the clash reasons and capture words use the same). */
	public void debugPop(String text) {
		comicPop(text, new Vector3f(0, 0.3f, 0), 0xffe872, 2.2f, 4000);
		comicPop("5 ≥ 3", new Vector3f(-Board.COL, 0.16f, Board.ROW / 2), 0xfff1c0, 1.8f, 4000);
	}

	/** The room code of a waiting room (null otherwise). */
	public String roomCode() {
		return "waiting".equals(status) ? saved.code : null;
	}

	/**
	 * Testing: animate an effect on the board as it stands (flip | boulder | swap | freeze), as if it
	 * had been played: its events go through the same animation, and the table ends where it began
	 * (the game itself is not touched).
	 */
	public void debugEffect(String kind) {
		if (shown == null || shown.board == null) return;
		Model.SeatMove m = new Model.SeatMove();
		m.index = -1;
		m.seat = "A";
		m.name = kind.toUpperCase();
		m.events = new ArrayList<>();
		m.state = shown;
		String effect = kind.toUpperCase();
		int effectId = 101;
		for (int id = 100; id < 120; id++) {
			Deck.Card d = Deck.card(id);
			if (d != null && effect.equals(d.effect)) effectId = id;
		}
		JsonObject played = new JsonObject();
		played.addProperty("type", "effectPlayed");
		played.addProperty("player", "A");
		played.addProperty("effect", effect);
		played.addProperty("cardId", effectId);
		m.events.add(played);
		int[] any = null;
		for (int r = 0; r < 3 && any == null; r++) for (int c = 0; c < 5; c++) if (shown.board[r][c] != null) { any = new int[] {r, c}; break; }
		JsonObject ev = new JsonObject();
		switch (kind) {
			case "flip" -> ev.addProperty("type", "boardFlipped");
			case "swap" -> ev.addProperty("type", "handsSwapped");
			case "freeze" -> {
				if (any == null) return;
				ev.addProperty("type", "cardFrozen");
				ev.addProperty("cell", Links.rcToCell(any[0], any[1]));
			}
			default -> {
				if (any == null) return;
				ev.addProperty("type", "boulderRemoved");
				ev.addProperty("target", Links.rcToCell(any[0], any[1]));
				JsonArray cellsArr = new JsonArray();
				for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++)
					if (shown.board[r][c] != null && (r == any[0] || c == any[1])) cellsArr.add(Links.rcToCell(r, c));
				ev.add("cells", cellsArr);
			}
		}
		m.events.add(ev);
		queue.add(m);
	}

	/** What a seated player's seat sees now (null when they don't sit here). */
	public Model.PlayerView viewOf(ServerPlayer p) {
		int i = seatOf(p);
		return i < 0 ? null : local[i].view;
	}

	/** True while a move is being drawn. */
	public boolean animating() {
		return animating || !queue.isEmpty();
	}

	public String status() {
		return status;
	}

	/** Left-click a tile: what is on it. */
	public void inspect(ServerPlayer p, int r, int c) {
		CardView cv = cells[r][c];
		if (cv == null) return;
		Deck.Card d = Deck.card(cv.cardId);
		if (d == null) return;
		int seat = seatOf(p);
		String whose = seat >= 0 ? (("A".equals(cv.owner)) == (seat == 0) ? "yours" : "theirs") : "seat " + cv.owner;
		String vals = d.isEffect() ? "" : "  ▲" + d.top + " ▶" + d.right + " ▼" + d.bottom + " ◀" + d.left;
		p.sendSystemMessage(Component.literal(d.name + vals + "  ·  " + whose + (cv.frozen ? "  ·  frozen" : "")), true);
	}

	private void deny(ServerPlayer p, String why) {
		quietUntil = level.getGameTime() + 50;
		if (lastTile != null && seatOf(p) >= 0) flash(p, lastTile[0], lastTile[1], RING_DENY);
		Sfx.to(p, "sfx.deny", 0.7f);
		p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.GOLD), true);
	}

	/* ------------------------------- the animation ------------------------------- */

	/** Animate the next move: its events in order, the website's beats; then match its state exactly. */
	private void playNext() {
		Model.SeatMove m = queue.poll();
		if (m == null) return;
		animating = true;
		double t = 0;
		attackerCell = -1;
		attackerCardId = -1;
		chainLinks = 0;
		List<com.google.gson.JsonObject> evs = m.events == null ? List.of() : m.events;
		// work board: what the table looks like as the events land, for the clash beats
		Model.SeatCard[][] work = copy(shown.board);
		Set<String> removedByBoulder = new HashSet<>();
		for (int k = 0; k < evs.size(); k++) {
			JsonObject ev = evs.get(k);
			JsonObject next = capturedAfter(evs, k);
			t += event(ev, next, work, t, m, removedByBoulder);
		}
		Model.SeatState after = m.state;
		tween.at(t + 50, () -> {
			shown = after;
			reconcile();
			animating = false;
			if (queue.isEmpty()) afterViews();
		});
	}

	/** The cardsCaptured that answers the attack at event k (before the next card moves), or null. */
	private static JsonObject capturedAfter(List<JsonObject> evs, int k) {
		for (int i = k + 1; i < evs.size(); i++) {
			String ty = str(evs.get(i), "type");
			if ("cardsCaptured".equals(ty)) return evs.get(i);
			if (ty != null && ty.matches("cardPlaced|cardHopped|cardRecruited|cardOverrun|turnStarted|effectPlayed")) return null;
		}
		return null;
	}

	/** Schedule one event at `t` ms; returns how long it takes. */
	private double event(JsonObject ev, JsonObject next, Model.SeatCard[][] work, double t, Model.SeatMove move, Set<String> removed) {
		String type = ev.get("type").getAsString();
		switch (type) {
			case "cardPlaced": {
				String player = str(ev, "player");
				int cardId = ev.get("cardId").getAsInt();
				int[] rc = cellOf(ev.get("cell"));
				work[rc[0]][rc[1]] = seatCard(cardId, player);
				Clash plan = clashFor(rc, next, work);
				swingIf(t, player);
				double dur = placed(t, player, cardId, rc, plan, false);
				return dur;
			}
			case "cardsCaptured": {
				List<int[]> cs = cellsOf(ev.getAsJsonArray("cells"));
				String owner = str(ev, "newOwner");
				for (int[] rc : cs) if (work[rc[0]][rc[1]] != null) work[rc[0]][rc[1]].owner = owner;
				return captures(t, cs, owner);
			}
			case "effectPlayed": {
				int cardId = ev.get("cardId").getAsInt();
				swingIf(t, str(ev, "player"));
				at(t + 300, crowd::murmur);
				return effectShowcase(t, cardId);
			}
			case "boulderRemoved": {
				List<int[]> cs = cellsOf(ev.getAsJsonArray("cells"));
				int[] target = ev.has("target") ? cellOf(ev.get("target")) : (cs.isEmpty() ? new int[] {1, 2} : cs.get(0));
				for (int[] rc : cs) work[rc[0]][rc[1]] = null;
				return boulder(t, cs, target) + finishEffect(t + 1100);
			}
			case "boardFlipped": {
				Model.SeatCard[][] f = new Model.SeatCard[3][5];
				for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) {
					Model.SeatCard sc = work[r][c];
					if (sc != null) sc.owner = "A".equals(sc.owner) ? "B" : "A";
					f[2 - r][4 - c] = sc;
				}
				for (int r = 0; r < 3; r++) work[r] = f[r];
				return flip(t) + finishEffect(t + 1350);
			}
			case "cardFrozen": {
				int[] rc = cellOf(ev.get("cell"));
				if (work[rc[0]][rc[1]] != null) work[rc[0]][rc[1]].frozen = true;
				return freeze(t, rc) + finishEffect(t + 600);
			}
			case "cardRecruited": {
				int[] from = cellOf(ev.get("from"));
				int[] to = cellOf(ev.get("to"));
				String owner = str(ev, "newOwner");
				Model.SeatCard sc = work[from[0]][from[1]];
				work[from[0]][from[1]] = null;
				if (sc != null) sc.owner = owner;
				work[to[0]][to[1]] = sc;
				double d = recruit(t, from, to, owner);
				Clash plan = clashFor(to, next, work);
				double strike = plan != null ? clashStrike(t + d, to, plan) : 0;
				return d + strike + finishEffect(t + d + strike);
			}
			case "handsSwapped":
				at(t, () -> {
					Sfx.play(level, board.center, "sfx.swap");
					Sfx.play(level, board.center, "announcer.fx_swap");
					comicPop("SWAP!", new Vector3f(0, 0.3f, 0), 0xffe872, 2.2f, 1000);
				});
				// the two hands cross the table: five cards each way
				for (int k = 0; k < 5; k++) {
					handToHand(t + 100 + k * 90, "A", "B");
					handToHand(t + 100 + k * 90, "B", "A");
				}
				return 1100 + finishEffect(t + 1100);
			case "cardHopped": {
				int[] from = cellOf(ev.get("from"));
				int[] to = cellOf(ev.get("to"));
				Model.SeatCard sc = work[from[0]][from[1]];
				work[from[0]][from[1]] = null;
				work[to[0]][to[1]] = sc;
				boolean knight = shown.operatorTiles != null && shown.operatorTiles.stream().anyMatch(o -> o.cell.r == from[0] && o.cell.c == from[1] && "KNIGHT".equals(o.piece));
				double d = hop(t, from, to, knight);
				Clash plan = clashFor(to, next, work);
				return d + (plan != null ? clashStrike(t + d, to, plan) : 0);
			}
			case "powerBoostApplied": {
				int[] rc = cellOf(ev.get("cell"));
				at(t, () -> {
					Sfx.play(level, board.center, "sfx.power_boost");
					particles(ParticleTypes.HAPPY_VILLAGER, rc, 10, 0.2);
				});
				return 250;
			}
			case "cardOverrun": {
				int[] rc = cellOf(ev.get("cell"));
				int newId = ev.get("newCardId").getAsInt();
				String player = move.seat;
				work[rc[0]][rc[1]] = seatCard(newId, player);
				double fly = overrunOut(t, rc);
				Clash plan = clashFor(rc, next, work);
				return Math.max(fly, 120 + placed(t + 120, player, newId, rc, plan, false));
			}
			case "cardsDrawn": {
				String player = str(ev, "player");
				int count = ev.has("count") ? ev.get("count").getAsInt() : 1;
				for (int i = 0; i < count; i++) flyToSeat(t + i * 150, player);
				return count * 150 + 200;
			}
			case "turnStarted": {
				String player = str(ev, "player");
				at(t, () -> {
					LocalSeat s = local["A".equals(player) ? 0 : 1];
					ServerPlayer p = s == null ? null : player(s);
					if (p != null) Sfx.to(p, "sfx.turn_chime", 0.8f);
				});
				return 0;
			}
			case "gameOver":
				return 0;
			default:
				return 0;
		}
	}

	/* ---- the beats (website durations in ms; distances in website units x U) ---- */

	private void at(double ms, Runnable r) {
		tween.at(ms, r);
	}

	/** animateCardPlaced: an arc down from the owner's side (330 ms), the slam, then the clash beat or a springy settle. */
	private double placed(double t, String player, int cardId, int[] rc, Clash plan, boolean fromHop) {
		CardView cv = new CardView(cardId, player);
		at(t, () -> {
			CardView old = cells[rc[0]][rc[1]];
			if (old != null && old != cv) old.remove();
			cells[rc[0]][rc[1]] = cv;
			Vector3f end = Board.local(rc[0], rc[1]);
			Vector3f start = new Vector3f(end.x * 0.6f, 0, ("A".equals(player) ? 1 : -1) * (Board.DEPTH / 2 + 0.45f));
			cv.pos.set(start);
			cv.lift = 0.6f;
			cv.tilt = 1.1f;
			cv.card = board.item(Board.boardCard(cardId), cv.cardT());
			tween.run(50, 330, Tween.IN_OUT_CUBIC, e -> {
				cv.pos.set(start).lerp(end, (float) e);
				cv.lift = (float) (0.6 * (1 - e) + 0.95 * U * Math.sin(Math.PI * e));
				cv.tilt = (float) (1.1 * (1 - e));
				cv.apply();
			}, () -> {
				cv.lift = 0;
				cv.tilt = 0;
				cv.apply();
				cv.showRim();
				Sfx.play(level, board.center, "sfx.card_place");
				particles(new DustParticleOptions(0xe8d8b8, 0.6f), rc, 10, 0.08);
				absorbCrystal(rc);
				Deck.Card d = Deck.card(cardId);
				if (plan == null && d != null && !d.isEffect() && !fromHop) Sfx.play(level, board.center, "voice." + cardId + ".place");
			});
		});
		double land = t + 380;
		if (plan != null) return 380 + clashStrike(land, rc, plan);
		// the springy overshoot-and-settle (300 ms)
		tween.run(land, 300, Tween.LINEAR, e -> {
			double w = Math.exp(-4.2 * e) * Math.sin(11 * e);
			cv.scale = (float) Math.max(0.001, 1 + 0.08 * w);
			cv.lift = (float) Math.max(0, 0.06 * U * w);
			cv.apply();
		});
		return 380 + 300;
	}

	/** One attack: the cell it comes from, each enemy neighbour it hits, and which of them flip. */
	record Pair(int[] cell, String dir, int atk, int boost, int def, boolean win) {}

	record Clash(int[] attacker, int cardId, List<Pair> pairs) {}

	/** The website's clash plan: the placed card against each orthogonal enemy neighbour (not frozen). */
	private Clash clashFor(int[] rc, JsonObject next, Model.SeatCard[][] work) {
		Model.SeatCard me = work[rc[0]][rc[1]];
		if (me == null) return null;
		Deck.Card mine = Deck.card(me.cardId);
		if (mine == null || mine.isEffect()) return null;
		Set<String> captured = new HashSet<>();
		if (next != null && "cardsCaptured".equals(str(next, "type"))) for (int[] c : cellsOf(next.getAsJsonArray("cells"))) captured.add(c[0] + "," + c[1]);
		int[] sides = Links.boardSides(mine.top, mine.right, mine.bottom, mine.left, me.owner); // n e s w
		int boost = 0;
		if (shown.powerTiles != null) for (Model.PowerTile pt : shown.powerTiles)
			if (pt.cell.r == rc[0] && pt.cell.c == rc[1] && pt.color.equals(mine.color)) boost = pt.boost;
		List<Pair> pairs = new ArrayList<>();
		int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}; // north (toward row 3), east, south, west
		String[] names = {"north", "east", "south", "west"};
		for (int k = 0; k < 4; k++) {
			int r = rc[0] + dirs[k][0], c = rc[1] + dirs[k][1];
			if (r < 0 || r > 2 || c < 0 || c > 4) continue;
			Model.SeatCard o = work[r][c];
			if (o == null || o.owner.equals(me.owner) || o.frozen) continue;
			Deck.Card theirs = Deck.card(o.cardId);
			if (theirs == null || theirs.isEffect()) continue;
			int[] ts = Links.boardSides(theirs.top, theirs.right, theirs.bottom, theirs.left, o.owner);
			int def = ts[(k + 2) % 4];
			pairs.add(new Pair(new int[] {r, c}, names[k], sides[k], boost, def, captured.contains(r + "," + c)));
		}
		if (pairs.isEmpty()) return null;
		return new Clash(rc, me.cardId, pairs);
	}

	/** playClashStrike: the lunge (one target) or rise-and-stomp (several), the impact, the reasons, the recoils, the settle. */
	private double clashStrike(double t, int[] rc, Clash plan) {
		// the attacker was put down in this same move: find it when each beat runs
		Supplier<CardView> ref = () -> cells[rc[0]][rc[1]];
		boolean multi = plan.pairs.size() > 1;
		boolean anyWin = plan.pairs.stream().anyMatch(Pair::win);
		Vector3f a = Board.local(rc[0], rc[1]);
		Vector3f focus = new Vector3f(a);
		for (Pair p : plan.pairs) focus.add(Board.local(p.cell[0], p.cell[1]));
		focus.div(plan.pairs.size() + 1);
		Vector3f d = multi ? new Vector3f() : new Vector3f(focus).sub(a).mul(0.55f);
		float rise = (multi ? 0.5f : 0.22f) * U;
		float grow = multi ? 0.14f : 0.08f;
		// the beat opens (the site's 340 ms dolly-in: here, the character stands up out of its card)
		double t0 = t + 120;
		String kind = !anyWin ? "blocked" : multi ? "stomp" : "strike";
		attackFigure(t, rc, plan.cardId, kind, multi ? 200 + 110 + 70 + 200 + 220 : 150 + 70 + 200 + 220, null);
		double lunge = multi ? 200 : 150;
		tween.run(t0, lunge, Tween.OUT_CUBIC, e -> {
			CardView cv = ref.get();
				if (cv == null) return;
			cv.pos.set(a).add(new Vector3f(d).mul((float) e));
			cv.lift = rise * (float) e;
			cv.scale = 1 + grow * (float) e;
			cv.apply();
		});
		double impact = t0 + lunge;
		if (multi) {
			tween.run(impact, 110, Tween.IN_OUT_CUBIC, e -> {
				CardView cv = ref.get();
				if (cv == null) return;
				cv.lift = rise * (float) (1 - e);
				cv.scale = 1 + grow * (float) (1 - e * 0.5);
				cv.apply();
			});
			impact += 110;
			double ip = impact;
			at(ip, () -> particles(ParticleTypes.SWEEP_ATTACK, rc, 3, 0.3));
		}
		double hit = impact;
		at(hit, () -> {
			if (anyWin) {
				Sfx.play(level, board.center, "sfx.clash_hit");
				Sfx.play(level, board.center, chainLinks >= 2 ? "sfx.capture_crit" : "sfx.capture_hit");
			} else {
				crowd.groan();
				Sfx.play(level, board.center, "sfx.block_thud");
				Sfx.play(level, board.center, "sfx.block_shield");
				Deck.Card dc = Deck.card(plan.cardId);
				if (dc != null && dc.voice) Sfx.play(level, board.center, "voice." + plan.cardId + ".blocked");
				else Sfx.play(level, board.center, "announcer.blocked");
			}
			for (int i = 0; i < plan.pairs.size(); i++) {
				Pair p = plan.pairs.get(i);
				Vector3f mid = new Vector3f(a).add(Board.local(p.cell[0], p.cell[1])).mul(0.5f);
				String atk = p.boost > 0 ? p.atk + "+" + p.boost : String.valueOf(p.atk);
				comicPop(atk + (p.win ? " ≥ " : " < ") + p.def, mid.add(0, 0.16f + i * 0.08f, 0), p.win ? 0xfff1c0 : 0xff6b6b, multi ? 1.5f : 1.8f, 1000);
				particles(p.win ? ParticleTypes.CRIT : ParticleTypes.SMOKE, p.cell, 8, 0.15);
			}
		});
		// defenders recoil (a flip is coming: 260 ms) or stand their ground (300 ms)
		for (Pair p : plan.pairs) {
			CardView dv = cells[p.cell[0]][p.cell[1]];
			if (dv == null) continue;
			Vector3f home = Board.local(p.cell[0], p.cell[1]);
			if (p.win) {
				float k = 0.12f * (multi ? 1.4f : 1);
				Vector3f rv = new Vector3f(home).sub(a).mul(k);
				tween.run(hit, 260, Tween.LINEAR, e -> {
					float w = (float) Math.sin(Math.PI * e);
					dv.pos.set(home).add(new Vector3f(rv).mul(w));
					dv.apply();
				}, () -> {
					dv.pos.set(home);
					dv.apply();
				});
			} else {
				tween.run(hit, 300, Tween.LINEAR, e -> {
					float w = (float) Math.sin(Math.PI * e);
					dv.scale = 1 + 0.09f * w;
					dv.lift = 0.06f * U * w;
					dv.apply();
				}, () -> {
					dv.scale = 1;
					dv.lift = 0;
					dv.apply();
				});
			}
		}
		double settle = hit + 70;
		tween.run(settle, 200, Tween.IN_OUT_CUBIC, e -> {
			CardView cv = ref.get();
				if (cv == null) return;
			cv.pos.set(a).add(new Vector3f(d).mul((float) (1 - e)));
			cv.lift = multi ? 0 : rise * (float) (1 - e);
			cv.scale = 1 + (multi ? grow * 0.5f : grow) * (float) (1 - e);
			cv.apply();
		}, () -> {
			CardView cv = ref.get();
				if (cv == null) return;
			cv.pos.set(a);
			cv.lift = 0;
			cv.scale = 1;
			cv.apply();
		});
		double end = settle + 200;
		if (!anyWin) {
			// nothing flips: the attacker trembles in front of the bigger number (520 ms)
			tween.run(end, 520, Tween.LINEAR, e -> {
				CardView cv = ref.get();
				if (cv == null) return;
				double env = e < 0.15 ? e / 0.15 : Math.exp(-2.6 * (e - 0.15));
				cv.pos.set(a).add((float) (Math.sin(e * Math.PI * 22) * 0.035 * U * env), 0, (float) (Math.cos(e * Math.PI * 17) * 0.025 * U * env));
				cv.lift = (float) (0.02 * U * env);
				cv.scale = (float) (1 - 0.05 * env);
				cv.spin = (float) (Math.sin(e * Math.PI * 22) * 0.07 * env);
				cv.apply();
			}, () -> {
				CardView cv = ref.get();
				if (cv == null) return;
				cv.pos.set(a);
				cv.lift = 0;
				cv.scale = 1;
				cv.spin = 0;
				cv.apply();
			});
			end += 520;
		}
		attackerCell = rc[0] * 5 + rc[1];
		attackerCardId = plan.cardId;
		chainLinks++;
		return end - t;
	}

	/**
	 * The character's own attack: its rest art, then its windup and strike drawings, standing up out
	 * of its card over the beat (the kit's timing for strike / stomp / blocked), then back down.
	 */
	private void attackFigure(double t, int[] rc, int cardId, String kind, double ms, CardView cv) {
		Deck.Card d = Deck.card(cardId);
		if (d == null || !d.art) return;
		boolean frames = d.animation != null;
		double[][] timing = switch (kind) {
			case "stomp" -> new double[][] {{0, 0.17}, {0.17, 0.5}, {0.5, 0.72}, {0.72, 1}};
			case "blocked" -> new double[][] {{0, 0.15}, {0.15, 0.46}, {0.46, 1}, {1, 1}};
			default -> new double[][] {{0, 0.15}, {0.15, 0.34}, {0.34, 0.62}, {0.62, 1}};
		};
		String[] frame = "blocked".equals(kind) ? new String[] {"rest", "windup", "rest", "rest"} : new String[] {"rest", "windup", "strike", "rest"};
		float size = 0.62f * Board.S;
		Display.ItemDisplay[] fig = new Display.ItemDisplay[1];
		String[] showing = {""};
		at(t, () -> {
			Display.ItemDisplay f = board.item(Board.modelStack("art/" + cardId), figureT(rc, size, 0.01f, d, "rest"));
			f.setBillboardConstraints(Display.BillboardConstraints.VERTICAL);
			Vec3 on = board.world(Board.local(rc[0], rc[1]));
			f.setPos(on.x, on.y, on.z);
			fig[0] = f;
			showing[0] = "rest";
		});
		tween.run(t + 50, ms, Tween.LINEAR, e -> {
			Display.ItemDisplay f = fig[0];
			if (f == null) return;
			// rise out of the card over the first 15%, sink back over the last 15%
			float up = (float) Math.min(1, Math.min(e / 0.15, (1 - e) / 0.15));
			String want = "rest";
			for (int i = 0; i < 4; i++) if (e >= timing[i][0] && e < timing[i][1]) want = frame[i];
			if (!frames) want = "rest";
			if (!want.equals(showing[0])) {
				f.setItemStack(Board.modelStack("rest".equals(want) ? "art/" + cardId : "attack/" + cardId + "_" + want));
				showing[0] = want;
			}
			Board.animate(f, figureT(rc, size * Math.max(0.05f, Tween.OUT_BACK.at(up) > 0 ? (float) Tween.OUT_BACK.at(up) : 0.05f), 0.01f, d, want), 1);
		}, () -> {
			if (fig[0] != null) fig[0].discard();
		});
	}

	/** The figure standing on a tile: the art (or an attack drawing at artScale, about its pivot) with its feet on the card. */
	private Transformation figureT(int[] rc, float size, float y, Deck.Card d, String frame) {
		float s = size;
		float lift = 0;
		if (!"rest".equals(frame) && d.animation != null) {
			// an attack drawing: drawn at artScale of the art, its feet (the pivot) where the art's feet are
			s = size * d.animation.artScale;
			lift = (0.92f - d.animation.pivot.y) * s;
		}
		// the figure stands on its tile (the entity is there); a billboard turns before it is offset, so offset only upward
		return new Transformation(new Vector3f(0, y + s / 2 + lift + 0.03f, 0), new Quaternionf(), new Vector3f(s), new Quaternionf());
	}

	/** animateCaptures: sparks, the comic word, a hit-stop, then each card flips over (550 ms; 880 for four) with a hop and a ring-down. */
	private double captures(double t, List<int[]> cs, String owner) {
		int n = cs.size();
		if (n == 0) return 0;
		int color = owner.equals("A") ? BLUE : ORANGE;
		Vector3f centroid = new Vector3f();
		for (int[] rc : cs) centroid.add(Board.local(rc[0], rc[1]).div(n));
		int clashId = attackerCardId;
		at(t, () -> {
			for (int[] rc : cs) particles(new DustParticleOptions(color, 1.4f), rc, 14, 0.2);
			comicPop(COMIC[Math.min(4, n) - 1][level.getRandom().nextInt(COMIC[Math.min(4, n) - 1].length)], new Vector3f(centroid).add(0, 0.3f, 0), 0xffe872, 2.2f, 1100);
			if (n >= 3) particles(ParticleTypes.TOTEM_OF_UNDYING, cs.get(0), 30, 0.6);
		});
		double start = t + (n >= 2 ? 80 : 0);
		double flipDur = n >= 4 ? 880 : 550;
		double stagger = n >= 4 ? 130 : 90;
		for (int k = 0; k < n; k++) {
			int[] rc = cs.get(k);
			CardView cv = cells[rc[0]][rc[1]];
			if (cv == null) continue;
			double delay = start + k * stagger;
			int kk = k;
			// the flip: half a turn on the way to its new owner, with a hop and a pulse
			// the card's angle, measured from its first owner's side (the owner switches at the apex)
			float base = yaw(cv.owner) + cv.spin;
			String before = cv.owner;
			tween.run(delay, flipDur, Tween.IN_OUT_CUBIC, e -> {
				cv.spin = base + (float) (Math.PI * e) - yaw(cv.owner);
				cv.lift = (float) (0.7 * U * Math.sin(Math.PI * e));
				cv.scale = (float) (1 + 0.08 * Math.sin(Math.PI * e));
				cv.apply();
			}, () -> {
				cv.owner = owner;
				cv.spin = 0;
				cv.lift = 0;
				cv.apply();
			});
			// the apex: the owner outline switches, the flash, the capture sound and the callouts
			at(delay + Math.round(flipDur * 0.44), () -> {
				cv.owner = owner;
				cv.showRim();
				particles(ParticleTypes.END_ROD, rc, 10, 0.12);
				if (kk == 0) {
					crowd.cheer(n);
					Sfx.play(level, board.center, "sfx.capture");
					if (n >= 2) Sfx.play(level, board.center, "sfx.combo");
					if (!firstBlood) {
						firstBlood = true;
						Sfx.play(level, board.center, "announcer.first_blood");
					} else if (clashId > 0 && Deck.card(clashId) != null && Deck.card(clashId).voice) {
						if (chainLinks >= 2) {
							Sfx.play(level, board.center, "voice." + clashId + ".chain." + Math.min(4, chainLinks));
							Sfx.play(level, board.center, "announcer." + (chainLinks == 2 ? "chain2" : chainLinks == 3 ? "chain3" : "chain_max"));
						} else Sfx.play(level, board.center, "voice." + clashId + ".capture." + Math.min(4, n));
					} else Sfx.play(level, board.center, "announcer.smash" + Math.min(4, n));
					int hurtId = cells[cs.get(n - 1)[0]][cs.get(n - 1)[1]] != null ? cells[cs.get(n - 1)[0]][cs.get(n - 1)[1]].cardId : -1;
					if (hurtId > 0 && Deck.card(hurtId) != null && Deck.card(hurtId).voice) at(300, () -> Sfx.play(level, board.center, "voice." + hurtId + ".hurt"));
				}
			});
			// the spring ring-down after it lands (240 ms)
			tween.run(delay + flipDur, 240, Tween.LINEAR, e -> {
				double w = Math.exp(-4.5 * e) * Math.sin(12 * e);
				cv.scale = (float) Math.max(0.001, 1 + 0.07 * w);
				cv.apply();
			}, () -> {
				cv.scale = 1;
				cv.apply();
			});
			if (before == null) continue;
		}
		return (start - t) + (n - 1) * stagger + flipDur + 240;
	}

	/** animateEffectPlayed: the effect card flies up big over the table (300 ms), pulses (160 ms), holds (220 ms). */
	private double effectShowcase(double t, int cardId) {
		Deck.Card d = Deck.card(cardId);
		at(t, () -> {
			if (showcase != null) showcase.discard();
			Display.ItemDisplay s = board.item(Board.boardCard(cardId), showcaseT(0.05f, 0));
			s.setBillboardConstraints(Display.BillboardConstraints.VERTICAL);
			showcase = s;
			Sfx.play(level, board.center, "sfx.whoosh");
			if (d != null && d.effect != null) Sfx.play(level, board.center, "announcer.fx_" + d.effect.toLowerCase());
		});
		tween.run(t + 50, 300, Tween.OUT_CUBIC, e -> {
			if (showcase != null) Board.animate(showcase, showcaseT((float) e, (float) e), 1);
		});
		tween.run(t + 350, 160, Tween.OUT_CUBIC, e -> {
			if (showcase != null) Board.animate(showcase, showcaseT((float) (1 + 0.08 * Math.sin(Math.PI * e)), 1), 1);
		});
		return 300 + 160 + 220;
	}

	private Transformation showcaseT(float s, float rise) {
		// an item display turns its item round: face the card toward whoever looks (it is billboarded)
		Quaternionf q = new Quaternionf().rotateY((float) Math.PI);
		// low over the middle of the board, under the sign
		return new Transformation(new Vector3f(0, 0.12f + 0.38f * rise, 0), q, new Vector3f(1.5f * Board.CARD_SCALE * s), new Quaternionf());
	}

	/** finishEffect: the showcased card dives into the table (300 ms), then the shuffle. */
	private double finishEffect(double t) {
		tween.run(t, 300, Tween.IN_CUBIC, e -> {
			if (showcase != null) Board.animate(showcase, showcaseT((float) (1 - e * 0.9), (float) (1 - e)), 1);
		}, () -> {
			if (showcase != null) showcase.discard();
			showcase = null;
			Sfx.play(level, board.center, "sfx.shuffle");
		});
		return 300;
	}

	/** animateBoulder: the rock falls on its target (360 ms); the shockwave sweeps the cards off outward (470 ms each, 70 ms a tile further). */
	private double boulder(double t, List<int[]> cs, int[] target) {
		Display.BlockDisplay[] rock = new Display.BlockDisplay[1];
		Vector3f tc = Board.local(target[0], target[1]);
		float rs = 0.45f * Board.S;
		at(t, () -> rock[0] = board.block(Blocks.COBBLESTONE.defaultBlockState(), rockT(tc, rs, 1.5f)));
		tween.run(t + 50, 360, Tween.IN_CUBIC, e -> {
			if (rock[0] != null) Board.animate(rock[0], rockSpinT(tc, rs, (float) (1.5 * (1 - e)), (float) (e * 4)), 1);
		});
		double impact = t + 410;
		at(impact, () -> {
			Sfx.play(level, board.center, "sfx.boulder");
			Sfx.play(level, board.center, "announcer.fx_boulder");
			particles(ParticleTypes.EXPLOSION, target, 2, 0.2);
			comicPop("BOULDER!", Board.local(target[0], target[1]).add(0, 0.22f, 0), 0xd9c9a8, 2.2f, 1000);
			Vector3f tl = Board.local(target[0], target[1]);
			for (int k = 0; k < 24; k++) {
				double ang = Math.PI * 2 * k / 24;
				Vec3 at = board.world(new Vector3f(tl).add((float) Math.cos(ang) * 0.35f, 0.05f, (float) Math.sin(ang) * 0.35f));
				level.sendParticles(new DustParticleOptions(0xd9c9a8, 0.9f), at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
			particles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, Blocks.COBBLESTONE.defaultBlockState()), target, 30, 0.4);
		});
		double last = 0;
		for (int[] rc : cs) {
			CardView cv = cells[rc[0]][rc[1]];
			if (cv == null) continue;
			int dist = Math.abs(rc[0] - target[0]) + Math.abs(rc[1] - target[1]);
			double delay = impact + 40 + dist * 70;
			Vector3f home = Board.local(rc[0], rc[1]);
			Vector3f away = new Vector3f(home).sub(tc);
			if (away.lengthSquared() < 1e-4) away.set(0, 0, 0.01f);
			away.normalize().mul(1.4f * Board.S);
			tween.run(delay, 470, Tween.OUT_CUBIC, e -> {
				cv.pos.set(home).add(new Vector3f(away).mul((float) e));
				cv.lift = (float) (0.5 * Math.sin(Math.PI * e * 0.8));
				cv.spin = (float) (2.5 * e);
				cv.scale = (float) Math.max(0.01, 1 - e);
				cv.apply();
			}, () -> {
				cv.remove();
				if (cells[rc[0]][rc[1]] == cv) cells[rc[0]][rc[1]] = null;
			});
			last = Math.max(last, delay + 470);
		}
		tween.run(impact + 130, 460, Tween.IN_CUBIC, e -> {
			if (rock[0] != null) Board.animate(rock[0], rockT(tc, (float) (rs * (1 - e)), 0), 1);
		}, () -> {
			if (rock[0] != null) rock[0].discard();
		});
		return Math.max(last, impact + 590) - t;
	}

	private Transformation rockSpinT(Vector3f local, float size, float y, float spin) {
		Quaternionf q = board.frameCopy().rotateY(0.6f + spin).rotateX(0.3f + spin * 0.6f);
		Vector3f corner = new Quaternionf(q).transform(new Vector3f(-size / 2, -size / 2, -size / 2));
		Vector3f t = board.toWorld(new Vector3f(local).add(0, y + size / 2, 0)).add(corner);
		return new Transformation(t, q, new Vector3f(size), new Quaternionf());
	}

	private Transformation rockT(Vector3f local, float size, float y) {
		Quaternionf q = board.frameCopy().rotateY(0.6f).rotateX(0.3f);
		Vector3f corner = new Quaternionf(q).transform(new Vector3f(-size / 2, -size / 2, -size / 2));
		Vector3f t = board.toWorld(new Vector3f(local).add(0, y + size / 2, 0)).add(corner);
		return new Transformation(t, q, new Vector3f(size), new Quaternionf());
	}

	/** animateBoardFlip: the whole board turns 180 degrees (1250 ms); every card ends up facing the other player. */
	private double flip(double t) {
		at(t, () -> {
			Sfx.play(level, board.center, "sfx.flip");
			Sfx.play(level, board.center, "announcer.fx_flip");
		});
		at(t + 40, () -> {
			clearMarkers();
			for (Display l : boardLabels) l.discard();
			boardLabels.clear();
			if (turnStrip != null) turnStrip.discard();
			turnStrip = null;
			turnStripFor = "";
		});
		List<CardView> all = new ArrayList<>();
		for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) if (cells[r][c] != null) all.add(cells[r][c]);
		Map<CardView, Vector3f> homes = new HashMap<>();
		for (CardView cv : all) homes.put(cv, new Vector3f(cv.pos));
		tween.run(t + 50, 1250, Tween.IN_OUT_CUBIC, e -> {
			float a = (float) (Math.PI * e);
			float bl = (float) (0.25 * Math.sin(Math.PI * e));
			if (halfLeft != null) Board.animate(halfLeft, halfT(-1, 0, a, bl), 1);
			if (halfRight != null) Board.animate(halfRight, halfT(1, 0, a, bl), 1);
			for (CardView cv : all) {
				Vector3f h = homes.get(cv);
				cv.pos.set((float) (h.x * Math.cos(a) + h.z * Math.sin(a)), 0, (float) (-h.x * Math.sin(a) + h.z * Math.cos(a)));
				cv.spin = a;
				cv.lift = (float) (0.25 * Math.sin(Math.PI * e));
				cv.apply();
			}
		}, () -> {
			if (halfLeft != null) Board.animate(halfLeft, halfT(-1, 0, 0, 0), 1);
			if (halfRight != null) Board.animate(halfRight, halfT(1, 0, 0, 0), 1);
			CardView[][] f = new CardView[3][5];
			for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) {
				CardView cv = cells[r][c];
				if (cv == null) continue;
				cv.owner = "A".equals(cv.owner) ? "B" : "A";
				cv.spin = 0;
				cv.lift = 0;
				cv.pos.set(Board.local(2 - r, 4 - c));
				cv.apply();
				cv.showRim();
				f[2 - r][4 - c] = cv;
			}
			for (int r = 0; r < 3; r++) System.arraycopy(f[r], 0, cells[r], 0, 5);
			markerKey = "";
			labels();
			particles(new DustParticleOptions(0xe8d8b8, 0.7f), new int[] {1, 2}, 20, 0.5);
		});
		return 1300;
	}

	/** animateFreeze: the freeze burst and the ice forming over the card (520 ms). */
	private double freeze(double t, int[] rc) {
		CardView cv = cells[rc[0]][rc[1]];
		at(t, () -> {
			Sfx.play(level, board.center, "sfx.freeze");
			Sfx.play(level, board.center, "announcer.fx_freeze");
			particles(ParticleTypes.SNOWFLAKE, rc, 30, 0.25);
			particles(ParticleTypes.ITEM_SNOWBALL, rc, 12, 0.15);
			comicPop("FROZEN!", Board.local(rc[0], rc[1]).add(0, 0.2f, 0), 0x8adfff, 1.6f, 1000);
			if (cv != null) {
				cv.frozen = true;
				if (cv.ice == null) cv.ice = board.block(Blocks.ICE.defaultBlockState(), cv.iceT());
			}
		});
		tween.run(t + 50, 520, Tween.OUT_BACK, e -> {
			if (cv == null || cv.ice == null) return;
			float s = cv.scale;
			cv.scale = (float) Math.max(0.05, e);
			Board.animate(cv.ice, cv.iceT(), 1);
			cv.scale = s;
		});
		return 600;
	}

	/** animateRecruit: the beam, the lift (220 ms), the arc to its new tile (480 ms), the landing. */
	private double recruit(double t, int[] from, int[] to, String owner) {
		CardView cv = cells[from[0]][from[1]];
		at(t, () -> {
			Sfx.play(level, board.center, "sfx.recruit");
			Sfx.play(level, board.center, "announcer.fx_recruit");
			particles(new DustParticleOptions(owner.equals("A") ? BLUE : ORANGE, 1.2f), from, 16, 0.2);
			// the beam from the recruiter's side to the card it takes
			Vector3f a0 = handLocal(owner), a1 = Board.local(from[0], from[1]);
			for (int k = 0; k <= 16; k++) {
				Vec3 at = board.world(new Vector3f(a0).lerp(a1, k / 16f).add(0, 0.05f, 0));
				level.sendParticles(new DustParticleOptions(owner.equals("A") ? BLUE : ORANGE, 0.9f), at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
			comicPop("RECRUIT!", Board.local(from[0], from[1]).add(0, 0.22f, 0), owner.equals("A") ? 0x5fa6ff : 0xff9a62, 1.8f, 900);
		});
		if (cv == null) return 700;
		Vector3f a = Board.local(from[0], from[1]);
		Vector3f b = Board.local(to[0], to[1]);
		tween.run(t + 50, 220, Tween.OUT_CUBIC, e -> {
			cv.lift = (float) (0.3 * U * e);
			cv.apply();
		});
		tween.run(t + 270, 480, Tween.IN_OUT_CUBIC, e -> {
			cv.pos.set(a).lerp(b, (float) e);
			cv.lift = (float) (0.3 * U * (1 - e) + 0.7 * U * Math.sin(Math.PI * e));
			cv.spin = "A".equals(cv.owner) == "A".equals(owner) ? 0 : (float) (Math.PI * e);
			cv.apply();
		}, () -> {
			cells[from[0]][from[1]] = null;
			cells[to[0]][to[1]] = cv;
			cv.owner = owner;
			cv.spin = 0;
			cv.lift = 0;
			cv.pos.set(b);
			cv.apply();
			cv.showRim();
			particles(new DustParticleOptions(0xe8d8b8, 0.6f), to, 10, 0.08);
			Sfx.play(level, board.center, "sfx.card_place");
		});
		return 800;
	}

	/** animateCardHopped: the chess hop (460 ms for a knight, 380 otherwise), the crystal re-forming behind it. */
	private double hop(double t, int[] from, int[] to, boolean knight) {
		CardView cv = cells[from[0]][from[1]];
		double dur = knight ? 460 : 380;
		at(t, () -> {
			Sfx.play(level, board.center, "sfx.chess_hop");
			reformCrystal(from);
		});
		if (cv == null) return dur;
		Vector3f a = Board.local(from[0], from[1]);
		Vector3f b = Board.local(to[0], to[1]);
		tween.run(t + 50, dur, Tween.IN_OUT_CUBIC, e -> {
			cv.pos.set(a).lerp(b, (float) e);
			cv.lift = (float) ((knight ? 1.0 : 0.55) * U * Math.sin(Math.PI * e));
			cv.apply();
		}, () -> {
			cells[from[0]][from[1]] = null;
			cells[to[0]][to[1]] = cv;
			cv.pos.set(b);
			cv.lift = 0;
			cv.apply();
			absorbCrystal(to);
			Sfx.play(level, board.center, "sfx.card_place");
		});
		return dur + 50;
	}

	/** animateCardOverrun: the covered card flies off the table (420 ms) as the new one comes down on it. */
	private double overrunOut(double t, int[] rc) {
		CardView old = cells[rc[0]][rc[1]];
		if (old == null) return 0;
		Vector3f home = new Vector3f(old.pos);
		Vector3f out = new Vector3f(Board.WIDTH / 2 + 0.4f, 0, 0);
		tween.run(t, 420, Tween.IN_CUBIC, e -> {
			old.pos.set(home).lerp(out, (float) e);
			old.lift = (float) (1.1 * U * Math.sin(Math.PI * e));
			old.scale = (float) (1 - 0.5 * e);
			old.apply();
		}, old::remove);
		return 420;
	}

	/** A card lands on a chess tile: the crystal shatters into it. */
	private void absorbCrystal(int[] rc) {
		if (shown == null || shown.operatorTiles == null) return;
		if (shown.operatorTiles.stream().noneMatch(o -> o.cell.r == rc[0] && o.cell.c == rc[1])) return;
		Sfx.play(level, board.center, "sfx.crystal_shatter");
		particles(new DustParticleOptions(0xb241ec, 0.55f), rc, 14, 0.12);
	}

	private void reformCrystal(int[] rc) {
		if (shown == null || shown.operatorTiles == null) return;
		if (shown.operatorTiles.stream().noneMatch(o -> o.cell.r == rc[0] && o.cell.c == rc[1])) return;
		Sfx.play(level, board.center, "sfx.crystal_form");
		particles(ParticleTypes.ENCHANT, rc, 20, 0.2);
	}

	/** fx.comicPop: a word that pops up over the table, grows in and fades. */
	private void comicPop(String text, Vector3f local, int color, float scale, double ms) {
		// a billboard turns before it is offset: stand the entity where the word is, and offset it only upward
		Display.TextDisplay d = board.text(Component.literal(text).withStyle(Style.EMPTY.withFont(DISPLAY_FONT).withColor(color)),
			new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.05f), new Quaternionf()), 0, true);
		Vec3 at = board.world(local);
		d.setPos(at.x, at.y, at.z);
		d.setTextOpacity((byte) -1);
		d.setFlags((byte) 0x01); // shadowed (see-through text is drawn faint, like a name tag behind a wall)
		Vector3f w = new Vector3f();
		tween.run(50, 200, Tween.OUT_BACK, e -> Board.animate(d, new Transformation(new Vector3f(w).add(0, (float) (0.1 * e), 0), new Quaternionf(), new Vector3f((float) (0.3 * scale * e)), new Quaternionf()), 1));
		tween.run(ms - 250, 250, Tween.LINEAR, e -> Board.animate(d, new Transformation(new Vector3f(w).add(0, (float) (0.1 + 0.15 * e), 0), new Quaternionf(), new Vector3f((float) (0.3 * scale * (1 - 0.3 * e))), new Quaternionf()), 1), d::discard);
	}

	private void particles(ParticleOptions p, int[] rc, int count, double spread) {
		Vec3 at = board.world(Board.local(rc[0], rc[1]).add(0, 0.1f, 0));
		level.sendParticles(p, at.x, at.y, at.z, count, spread, 0.1, spread, 0.02);
	}

	/* --------------------------- the table, exactly --------------------------- */

	/** Make the table match `shown`: every card, its owner, frost, and the special tiles. */
	private void reconcile() {
		if (shown == null || shown.board == null) return;
		for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) {
			Model.SeatCard want = shown.board[r][c];
			CardView cv = cells[r][c];
			if (want == null) {
				if (cv != null) cv.remove();
				cells[r][c] = null;
				continue;
			}
			if (cv == null || cv.cardId != want.cardId || cv.card == null) {
				if (cv != null) cv.remove();
				cv = new CardView(want.cardId, want.owner);
				cv.pos.set(Board.local(r, c));
				cv.card = board.item(Board.boardCard(want.cardId), cv.cardT());
				cv.showRim();
				cells[r][c] = cv;
			}
			boolean ownerChanged = !want.owner.equals(cv.owner);
			cv.owner = want.owner;
			cv.pos.set(Board.local(r, c));
			cv.lift = 0;
			cv.scale = 1;
			cv.spin = 0;
			cv.tilt = 0;
			if (ownerChanged || cv.rim == null) cv.showRim();
			if (want.frozen && cv.ice == null) cv.ice = board.block(Blocks.ICE.defaultBlockState(), cv.iceT());
			if (!want.frozen && cv.ice != null) {
				cv.ice.discard();
				cv.ice = null;
			}
			cv.frozen = want.frozen;
			cv.apply();
		}
		markers();
		renderDeck(shown.deck);
		refreshSign();
	}

	/** The chess (operator) tiles and the power tiles, drawn on their tiles. */
	private void markers() {
		StringBuilder key = new StringBuilder();
		if (shown.operatorTiles != null) for (Model.OperatorTile o : shown.operatorTiles) key.append(o.cell.r).append(o.cell.c).append(o.piece);
		key.append('|');
		if (shown.powerTiles != null) for (Model.PowerTile p : shown.powerTiles) key.append(p.cell.r).append(p.cell.c).append(p.color).append(p.boost);
		if (key.toString().equals(markerKey)) return;
		markerKey = key.toString();
		clearMarkers();
		if (shown.operatorTiles != null) {
			for (Model.OperatorTile o : shown.operatorTiles) {
				String glyph = switch (o.piece) {
					case "KNIGHT" -> "♞";
					case "BISHOP" -> "♝";
					case "ROOK" -> "♜";
					default -> "♛";
				};
				markers.add(board.item(Board.glow(0xb241ec), board.flatAt(o.cell.r, o.cell.c, 0.5f * Board.S, 0.68f * Board.S, 0.002f, "A")));
				markers.add(flatText(glyph, Board.local(o.cell.r, o.cell.c).add(0, 0.003f, 0.12f * Board.S), 2.4f * Board.S, "A"));
				((Display.TextDisplay) markers.get(markers.size() - 1)).setText(Component.literal(glyph).withStyle(Style.EMPTY.withColor(0x5b25d6)));
			}
		}
		if (shown.powerTiles != null) {
			for (Model.PowerTile p : shown.powerTiles) {
				int col = Deck.color(p.color);
				markers.add(board.item(Board.glow(col), board.flatAt(p.cell.r, p.cell.c, 0.5f * Board.S, 0.68f * Board.S, 0.002f, "A")));
				Display.TextDisplay txt = flatText("+" + p.boost, Board.local(p.cell.r, p.cell.c).add(0, 0.003f, 0.08f * Board.S), 1.6f * Board.S, "A");
				txt.setText(Component.literal("+" + p.boost).withStyle(Style.EMPTY.withFont(DISPLAY_FONT).withColor(col)));
				markers.add(txt);
			}
		}
	}

	private void clearMarkers() {
		for (Display d : markers) d.discard();
		markers.clear();
		markerKey = "";
	}

	/** The sign over the table: the players, the score, whose turn. */
	private void refreshSign() {
		if (sign == null) return;
		MutableComponent c = Component.empty();
		String a = seatName(0), b = seatName(1);
		int[] score = score();
		if ("idle".equals(status) || saved.gameId == null && !"starting".equals(status)) {
			c.append(Component.literal("Smash&Clash").withStyle(Style.EMPTY.withFont(DISPLAY_FONT).withColor(0xffc21a)));
			c.append(Component.literal("\nRight-click the table to play").withStyle(ChatFormatting.WHITE));
			if (!message.isEmpty()) c.append(Component.literal("\n" + message).withStyle(ChatFormatting.GRAY));
		} else {
			c.append(Component.literal(a + "  ").withStyle(Style.EMPTY.withFont(DISPLAY_FONT).withColor(0xffffff)));
			c.append(Component.literal(score[0] + " – " + score[1]).withStyle(Style.EMPTY.withFont(DISPLAY_FONT).withColor(0xffc21a)));
			c.append(Component.literal("  " + b).withStyle(Style.EMPTY.withFont(DISPLAY_FONT).withColor(0xffffff)));
			String line;
			if ("starting".equals(status)) line = message;
			else if ("waiting".equals(status)) line = saved.code != null ? "Room " + saved.code + "  ·  waiting for a player" : "Looking for an opponent...";
			else if ("over".equals(status)) line = winnerLine();
			else {
				String cur = lastSync != null && lastSync.state != null ? lastSync.state.current : null;
				int left = 15 - score[0] - score[1];
				line = (cur == null ? "" : ("A".equals(cur) ? a : b) + "'s turn  ·  ") + left + (left == 1 ? " tile left" : " tiles left");
			}
			c.append(Component.literal("\n" + line).withStyle(ChatFormatting.WHITE));
		}
		turnLight();
		String txt = c.getString();
		if (txt.equals(signText)) return;
		signText = txt;
		sign.setText(c);
	}

	private int[] score() {
		int[] s = {0, 0};
		if (shown == null || shown.board == null) return s;
		for (Model.SeatCard[] row : shown.board) for (Model.SeatCard sc : row) if (sc != null) s["A".equals(sc.owner) ? 0 : 1]++;
		return s;
	}

	private String winnerLine() {
		if (lastSync == null || lastSync.winner == null) return "Game over";
		if ("draw".equals(lastSync.winner)) return "A draw!";
		return seatName("A".equals(lastSync.winner) ? 0 : 1) + " wins!";
	}

	/* ------------------------------ the end ------------------------------ */

	private void finish() {
		finished = true;
		status = "over";
		String winner = lastSync.winner;
		int[] sc = score();
		boolean anyRemote = local[0] == null || local[1] == null;
		for (int i = 0; i < 2; i++) {
			LocalSeat s = local[i];
			if (s == null) continue;
			ServerPlayer p = player(s);
			String seat = i == 0 ? "A" : "B";
			double result = winner == null ? 0.5 : "draw".equals(winner) ? 0.5 : seat.equals(winner) ? 1 : 0;
			boolean resigned = "abandoned".equals(lastSync.status);
			Players.Rec rec = Players.get(server, s.player);
			int before = rec.rating;
			Integer opp = null;
			if ("house".equals(saved.mode)) opp = saved.strength;
			else if (local[1 - i] != null) opp = local[1 - i].rating;
			else if (state != null && state.ratings != null) opp = i == 0 ? state.ratings.B : state.ratings.A;
			if (opp != null) rec.rating = Players.elo(rec.rating, opp, result);
			rec.played++;
			if (result == 1) rec.won++;
			Players.save(server);
			if (p == null) continue;
			boolean won = result == 1;
			Hands.clear(p);
			clearGlows(s);
			int mine = sc[i], theirs = sc[1 - i];
			int delta = rec.rating - before;
			p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 60, 15));
			p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(won ? "VICTORY!" : result == 0.5 ? "DRAW" : "DEFEAT").withStyle(Style.EMPTY.withFont(DISPLAY_FONT).withColor(won ? 0xffc21a : 0xff5a5a))));
			p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(mine + " – " + theirs + "   ·   Rating " + rec.rating + (delta == 0 ? "" : " (" + (delta > 0 ? "+" : "") + delta + ")")).withStyle(ChatFormatting.WHITE)));
			Sfx.to(p, won ? "sfx.win" : "sfx.lose", 1f);
			Sfx.to(p, won ? "announcer.victory" : "announcer.defeat", 1f);
			Sfx.to(p, delta >= 0 ? "sfx.elo_up" : "sfx.elo_down", 0.6f);
			seated(p, won ? "victory" : "defeat", "");
			if (resigned) p.sendSystemMessage(Component.literal(won ? "Your opponent resigned." : "You resigned."));
		}
		crowd.applaud();
		at(15000, crowd::dismiss);
		String id = saved.gameId;
		String replay = lastSync.replayUrl;
		call(() -> SC.games.review(id), review -> {
			for (int i = 0; i < 2; i++) {
				LocalSeat s = local[i];
				ServerPlayer p = s == null ? null : player(s);
				if (p == null) continue;
				String me = i == 0 ? "A" : "B", them = i == 0 ? "B" : "A";
				MutableComponent msg = Component.literal("Game Review: ").withStyle(ChatFormatting.GOLD);
				if (review.accuracy != null)
					msg.append(Component.literal("your accuracy " + Math.round(review.accuracy.getOrDefault(me, 0d)) + "%, theirs " + Math.round(review.accuracy.getOrDefault(them, 0d)) + "%  ").withStyle(ChatFormatting.WHITE));
				if (replay != null)
					msg.append(Component.literal("[Watch the replay]").withStyle(Style.EMPTY.withColor(0x5fa6ff).withClickEvent(new ClickEvent.OpenUrl(URI.create(replay)))));
				p.sendSystemMessage(msg);
			}
		}, e -> {});
		if (anyRemote) {
			// nothing to add
		}
		saved = new Saved();
		persist();
		refreshSign();
		// the music fades back to Minecraft's after the result
		at(9000, () -> {
			for (LocalSeat s : local) {
				ServerPlayer p = s == null ? null : player(s);
				if (p != null && "over".equals(status)) seated(p, "", "");
			}
		});
	}

	/* --------------------------- the deck and the deal --------------------------- */

	/** The draw pile sits beside the board, on seat A's left. */
	private static Vector3f deckLocal() {
		return new Vector3f(-(Board.WIDTH / 2 + 0.2f), -Board.THICK, 0);
	}

	/** A card back lying the way the board's cards lie (the deck, a card on its way to a hand). */
	private Transformation backT(Vector3f local, float lift, float scale, float tilt, float spin) {
		Quaternionf q = board.frameCopy();
		q.rotateY(spin);
		q.rotateX((float) (-Math.PI / 2) + tilt);
		q.rotateY((float) Math.PI);
		return new Transformation(board.toWorld(new Vector3f(local).add(0, 0.013f + lift, 0)), q, new Vector3f(Board.CARD_SCALE * scale), new Quaternionf());
	}

	/** The pile: one card back per few cards left, and the count. */
	private void renderDeck(int count) {
		int layers = count <= 0 ? 0 : Math.min(10, 1 + count / 5);
		if (layers != deckShown) {
			deckShown = layers;
			for (Display d : deckPile) d.discard();
			deckPile.clear();
			for (int i = 0; i < layers; i++)
				deckPile.add(board.item(Board.modelStack("card_back"), backT(deckLocal(), i * 0.007f, 1, 0, (i % 3 - 1) * 0.04f)));
		}
		Component label = count <= 0 ? Component.empty() : Component.literal("DECK " + count).withStyle(Style.EMPTY.withFont(DISPLAY_FONT).withColor(0xffffff));
		if (deckLabel == null) {
			deckLabel = board.text(label, new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.28f), new Quaternionf()), argb(0xc0, INK), true);
			Vec3 at = board.world(deckLocal().add(0, 0.2f, 0));
			deckLabel.setPos(at.x, at.y, at.z);
		} else deckLabel.setText(label);
	}

	/** Where a seat's hand is: the player sitting there (at their chest), the figure across the board, or that edge of the board. */
	private Vector3f handLocal(String seat) {
		LocalSeat s = local["A".equals(seat) ? 0 : 1];
		ServerPlayer p = player(s);
		if (p != null && p.distanceToSqr(board.center) < 36) return board.toLocal(p.position().add(0, 1.1, 0));
		if (opponent != null && seat.equals(opponentSeat)) return board.toLocal(opponent.position().add(0, 1.1, 0));
		return new Vector3f(0, 0.35f, ("A".equals(seat) ? 1 : -1) * (Board.DEPTH / 2 + 0.45f));
	}

	/** One card off the top of the deck into a hand: a 380 ms arc, then it is in the hand. */
	private void flyToSeat(double t, String seat) {
		at(t, () -> {
			Vector3f from = deckLocal().add(0, deckShown * 0.007f + 0.01f, 0);
			Vector3f to = handLocal(seat);
			Display.ItemDisplay d = board.item(Board.modelStack("card_back"), backT(from, 0, 1, 0, 0));
			Sfx.play(level, board.center, "sfx.draw", 0.9f);
			tween.run(50, 380, Tween.IN_OUT_CUBIC, e -> {
				Vector3f p = new Vector3f(from).lerp(to, (float) e);
				Board.animate(d, backT(p, (float) (0.9 * U * Math.sin(Math.PI * e)), (float) (1 - 0.25 * e), (float) (1.2 * e), (float) (-Math.PI / 2 * e)), 1);
			}, () -> tween.run(0, 150, Tween.IN_CUBIC,
				e -> Board.animate(d, backT(to, 0, (float) Math.max(0.02, 0.75 * (1 - e)), 1.2f, (float) (-Math.PI / 2)), 1), d::discard));
		});
	}

	/** A card back from one hand to the other (SWAP). */
	private void handToHand(double t, String from, String to) {
		at(t, () -> {
			Vector3f a = handLocal(from), b = handLocal(to);
			Display.ItemDisplay d = board.item(Board.modelStack("card_back"), backT(a, 0, 0.8f, 1.2f, 0));
			tween.run(50, 520, Tween.IN_OUT_CUBIC, e -> Board.animate(d, backT(new Vector3f(a).lerp(b, (float) e).add(("A".equals(from) ? 1 : -1) * (float) (0.25 * Math.sin(Math.PI * e)), 0, 0),
				(float) (0.5 * Math.sin(Math.PI * e)), 0.8f, 1.2f, (float) (Math.PI * 2 * e)), 1), d::discard);
		});
	}

	/** The deal: five cards each, one at a time, the first player first. */
	private void deal(String first) {
		animating = true;
		String other = "A".equals(first) ? "B" : "A";
		at(0, () -> Sfx.play(level, board.center, "sfx.shuffle"));
		for (int k = 0; k < 10; k++) flyToSeat(300 + k * 130, k % 2 == 0 ? first : other);
		at(300 + 10 * 130 + 600, () -> {
			animating = false;
			if (queue.isEmpty()) afterViews();
		});
	}

	/** Where the watchers look: the player whose turn it is (here, or the figure across the board), else the board. */
	private Vec3 turnTarget() {
		String cur = lastSync != null && lastSync.state != null ? lastSync.state.current : null;
		if (cur != null) {
			ServerPlayer p = player(local["A".equals(cur) ? 0 : 1]);
			if (p != null) return p.getEyePosition();
			if (opponent != null && cur.equals(opponentSeat)) return opponent.getEyePosition();
		}
		return board.center;
	}

	/** A light along the edge of the board on the side whose turn it is. */
	private void turnLight() {
		String cur = "playing".equals(status) && lastSync != null && lastSync.state != null && !lastSync.state.gameOver ? lastSync.state.current : "";
		if (cur == null) cur = "";
		if (cur.equals(turnStripFor)) return;
		turnStripFor = cur;
		if (turnStrip != null) turnStrip.discard();
		turnStrip = null;
		if (cur.isEmpty()) return;
		float z = ("A".equals(cur) ? 1 : -1) * (Board.DEPTH / 2 - 0.035f);
		Quaternionf q = board.frameCopy();
		q.rotateX((float) (-Math.PI / 2));
		turnStrip = board.item(Board.glow(0xffc21a), new Transformation(board.toWorld(new Vector3f(0, 0.004f, z)), q, new Vector3f(Board.WIDTH * 0.86f, 0.05f, 1), new Quaternionf()));
		Sfx.play(level, board.center, "sfx.whoosh", 0.4f);
	}

	/** Dots over the head of the figure across the board while it thinks. */
	private void thinkingBubble(boolean on) {
		if (!on || opponent == null) {
			if (thinking != null) thinking.discard();
			thinking = null;
			return;
		}
		Vec3 head = opponent.position().add(0, 2.25, 0);
		Vector3f local = board.toLocal(head);
		String dots = switch ((int) (level.getGameTime() / 8 % 3)) {
			case 0 -> "•";
			case 1 -> "• •";
			default -> "• • •";
		};
		Component text = Component.literal(dots).withStyle(Style.EMPTY.withFont(DISPLAY_FONT).withColor(INK));
		if (thinking == null) {
			thinking = board.text(text, new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.5f), new Quaternionf()), argb(0xf0, 0xffffff), true);
			thinking.setPos(head.x, head.y, head.z);
		}
		else thinking.setText(text);
	}

	/* ------------------------------ the other player ------------------------------ */

	/**
	 * Across the board from you when the other seat plays from somewhere else (the house, the
	 * queue, a room code): a figure in a player skin with their name, holding a card while it is
	 * their turn and swinging it down when they play.
	 */
	private void opponentUpkeep() {
		int remote = -1;
		if (("playing".equals(status) || "over".equals(status)) && state != null) {
			if (local[0] == null && local[1] != null) remote = 0;
			else if (local[1] == null && local[0] != null) remote = 1;
		}
		if (remote < 0) {
			if (opponent != null) opponent.discard();
			opponent = null;
			thinkingBubble(false);
			return;
		}
		String seat = remote == 0 ? "A" : "B";
		if (opponent == null || opponent.isRemoved() || !seat.equals(opponentSeat)) {
			if (opponent != null) opponent.discard();
			opponent = spawnOpponent(seat, seatName(remote));
			opponentSeat = seat;
		}
		if (opponent instanceof net.minecraft.world.entity.LivingEntity le) {
			boolean theirTurn = "playing".equals(status) && lastSync != null && lastSync.state != null && seat.equals(lastSync.state.current);
			ItemStack want = theirTurn ? Board.modelStack("card_back") : ItemStack.EMPTY;
			if (!ItemStack.matches(le.getMainHandItem(), want)) le.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, want);
			thinkingBubble(theirTurn && !animating && queue.isEmpty());
		}
	}

	private net.minecraft.world.entity.Entity spawnOpponent(String seat, String name) {
		Vec3 at = board.world(new Vector3f(0, 0, ("A".equals(seat) ? 1 : -1) * (Board.DEPTH / 2 + 0.75f)));
		double dx = board.center.x - at.x, dz = board.center.z - at.z;
		float yaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
		UUID skin = UUID.nameUUIDFromBytes((saved.gameId == null ? name : saved.gameId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		long hi = skin.getMostSignificantBits(), lo = skin.getLeastSignificantBits();
		String ints = (int) (hi >> 32) + "," + (int) hi + "," + (int) (lo >> 32) + "," + (int) lo;
		String safe = name.replaceAll("[\"\\\\]", "");
		String cmd = String.format(java.util.Locale.ROOT,
			"summon minecraft:mannequin %.3f %d %.3f {Rotation:[%.1ff,32f],immovable:1b,Invulnerable:1b,description:{text:\"%s\"},profile:{id:[I;%s]},Tags:[\"%s\",\"%s\",\"snc_opponent\"]}",
			at.x, board.pos.getY(), at.z, yaw, safe, ints, Board.TAG, tableKey);
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput().withLevel(level), cmd);
		var found = level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new net.minecraft.world.phys.AABB(board.pos).inflate(4),
			e -> e.entityTags().contains("snc_opponent") && e.entityTags().contains(tableKey));
		for (int i = 1; i < found.size(); i++) found.get(i).discard();
		return found.isEmpty() ? null : found.get(0);
	}

	/** The figure across the board plays its card. */
	private void swingIf(double t, String seat) {
		if (seat == null || !seat.equals(opponentSeat)) return;
		at(Math.max(0, t - 100), () -> {
			if (opponent instanceof net.minecraft.world.entity.LivingEntity le)
				le.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		});
	}


	/* ------------------------------ glowing tiles ------------------------------ */

	/** Where the player to move can play the card they hold: rings only they can see. */
	static final int RING_YOU = 0x5fa6ff, RING_PICK = 0xff9a62, RING_ARMED = 0xffc21a, RING_DENY = 0xff3b3b, RING_PRESS = 0xffe872;

	private void updateGlows() {
		for (int i = 0; i < 2; i++) {
			LocalSeat s = local[i];
			if (s == null) continue;
			ServerPlayer p = player(s);
			Map<String, Integer> want = new HashMap<>();
			if (p != null && "playing".equals(status) && !animating && queue.isEmpty() && s.view != null && s.view.yourTurn && s.view.moves != null) {
				if (s.view.pendingHop != null) {
					for (Model.MoveDetail m : s.view.moves) if ("hop".equals(m.type)) want.put(m.dest != null ? m.dest : s.view.pendingHop.from, m.dest != null ? RING_YOU : RING_PICK);
				} else {
					int id = Hands.cardOf(p.getMainHandItem());
					if (id < 0) id = Hands.cardOf(p.getOffhandItem());
					boolean armed = s.armed != null && level.getGameTime() < s.armedUntil;
					for (Model.MoveDetail m : s.view.moves) {
						if (!Objects.equals(m.cardId, id)) continue;
						if (m.cell != null) want.put(m.cell, RING_YOU);
						else if ("RECRUIT".equals(m.effect)) {
							if (s.recruitFrom == null) want.put(m.target, RING_YOU);
							else if (s.recruitFrom.equals(m.target)) want.put(m.dest, RING_YOU);
						} else if (m.target != null) want.put(m.target, RING_YOU);
						else for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) want.put(Links.rcToCell(r, c), armed ? RING_ARMED : RING_YOU);
					}
					// the enemy card picked for RECRUIT stays marked while you choose where it goes
					if (s.recruitFrom != null) want.put(s.recruitFrom, RING_PICK);
					want.remove(null);
				}
			}
			syncGlows(p, s, want);
		}
	}

	/** A ring only one player sees (a fake entity sent to them alone); returns its entity id. */
	private int privateRing(ServerPlayer p, String cell, int color) {
		int[] rc = Links.cellToRC(cell);
		Display.ItemDisplay d = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
		d.setPos(board.center.x, board.center.y, board.center.z);
		d.setItemStack(Board.glow(color));
		d.setViewRange(0.75f);
		d.setItemTransform(ItemDisplayContext.NONE);
		d.setTransformation(board.flatAt(rc[0], rc[1], 0.52f * Board.S, 0.70f * Board.S, cells[rc[0]][rc[1]] != null ? 0.025f : 0.004f, "A"));
		d.setBrightnessOverride(new Brightness(15, 15));
		p.connection.send(new ClientboundAddEntityPacket(d.getId(), d.getUUID(), board.center.x, board.center.y, board.center.z, 0, 0, EntityTypes.ITEM_DISPLAY, 0, Vec3.ZERO, 0));
		var data = d.getEntityData().getNonDefaultValues();
		if (data != null) p.connection.send(new ClientboundSetEntityDataPacket(d.getId(), data));
		return d.getId();
	}

	/** A quick flash on one tile for one player: where they clicked (yellow) or a move that is not allowed (red). */
	private void flash(ServerPlayer p, int r, int c, int color) {
		int id = privateRing(p, Links.rcToCell(r, c), color);
		at(color == RING_DENY ? 450 : 300, () -> p.connection.send(new ClientboundRemoveEntitiesPacket(id)));
	}

	private void syncGlows(ServerPlayer p, LocalSeat s, Map<String, Integer> want) {
		for (var it = s.glows.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			String cell = e.getKey().split("\\|")[0];
			int color = Integer.parseInt(e.getKey().split("\\|")[1]);
			if (!Objects.equals(want.get(cell), color)) {
				if (p != null) p.connection.send(new ClientboundRemoveEntitiesPacket(e.getValue()));
				it.remove();
			}
		}
		if (p == null) return;
		for (var w : want.entrySet()) {
			String key = w.getKey() + "|" + w.getValue();
			if (s.glows.containsKey(key)) continue;
			s.glows.put(key, privateRing(p, w.getKey(), w.getValue()));
		}
	}

	private void clearGlows(LocalSeat s) {
		ServerPlayer p = player(s);
		syncGlows(p, s, Map.of());
		s.glows.clear();
	}

	/* --------------------------------- helpers --------------------------------- */

	/** The action-bar line for each seated player, once a second. */
	private void hints() {
		if (level.getGameTime() < quietUntil) return;
		for (int i = 0; i < 2; i++) {
			LocalSeat s = local[i];
			ServerPlayer p = s == null ? null : player(s);
			if (p == null) continue;
			String line = null;
			if ("waiting".equals(status)) line = saved.code != null ? "Room " + saved.code + ": waiting for a player" : "Looking for an opponent...";
			else if ("playing".equals(status) && !animating && queue.isEmpty() && s.view != null) {
				if (s.view.yourTurn) {
					if (s.view.pendingHop != null) line = "Hop to a glowing tile, or right-click your card to stay";
					else if (Hands.cardOf(p.getMainHandItem()) < 0) line = "Your turn: hold a card from your hand";
					else line = "Your turn: right-click a glowing tile";
				} else line = theirName(i) + " is thinking...";
			}
			if (line != null) p.sendSystemMessage(Component.literal(line), true);
		}
	}

	/** A dropped card item from this table is not a card any more. */
	private void cleanDroppedCards() {
		if (level.getGameTime() % 10 != 0) return;
		for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(board.pos).inflate(16),
			e -> Hands.cardOf(e.getItem()) >= 0)) item.discard();
		for (LocalSeat s : local) {
			ServerPlayer p = s == null ? null : player(s);
			if (p != null && s.view != null && "playing".equals(status) && !animating && queue.isEmpty()) Hands.sync(p, s.view.hand, tableKey);
		}
	}

	private void seated(ServerPlayer p, String music, String hint) {
		ServerPlayNetworking.send(p, new Payloads.Seated(music, hint));
	}

	private void say(ServerPlayer p, String text) {
		quietUntil = level.getGameTime() + 50;
		p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD), true);
	}

	private ServerPlayer player(LocalSeat s) {
		return s == null ? null : server.getPlayerList().getPlayer(s.player);
	}

	private int seatOf(ServerPlayer p) {
		for (int i = 0; i < 2; i++) if (local[i] != null && local[i].player.equals(p.getUUID())) return i;
		return -1;
	}

	private UUID seatPlayer(int i) {
		return local[i] == null ? null : local[i].player;
	}

	/** A seat's name: the player here, the opponent's name, or a persona for the house (never "CPU"). */
	private String seatName(int i) {
		if (local[i] != null) return local[i].name;
		if (state == null || state.players == null) return "...";
		String kind = state.playerKinds == null ? null : i == 0 ? state.playerKinds.A : state.playerKinds.B;
		if ("house".equals(kind)) return Persona.name(state.id);
		String n = i == 0 ? state.players.A : state.players.B;
		return n == null ? "..." : n;
	}

	private String theirName(int i) {
		return seatName(1 - i);
	}

	private void persist() {
		be.save(saved.gameId == null ? "" : GSON.toJson(saved));
	}

	private static String errorText(Throwable e) {
		if (e instanceof SmashAndClashError se) return se.getMessage();
		return "Couldn't reach Smash&Clash. Check your connection.";
	}

	private static float yaw(String owner) {
		return "B".equals(owner) ? (float) Math.PI : 0;
	}

	private static int argb(int a, int rgb) {
		return (a << 24) | (rgb & 0xffffff);
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
	}

	private static int[] cellOf(com.google.gson.JsonElement e) {
		if (e == null || e.isJsonNull()) return new int[] {1, 2};
		if (e.isJsonObject()) return new int[] {e.getAsJsonObject().get("r").getAsInt(), e.getAsJsonObject().get("c").getAsInt()};
		int[] rc = Links.cellToRC(e.getAsString());
		return rc == null ? new int[] {1, 2} : rc;
	}

	private static List<int[]> cellsOf(JsonArray a) {
		List<int[]> out = new ArrayList<>();
		if (a != null) for (var e : a) out.add(cellOf(e));
		return out;
	}

	private static Model.SeatCard seatCard(int id, String owner) {
		Model.SeatCard s = new Model.SeatCard();
		s.cardId = id;
		s.owner = owner;
		return s;
	}

	private static Model.SeatCard[][] copy(Model.SeatCard[][] b) {
		Model.SeatCard[][] out = new Model.SeatCard[3][5];
		if (b == null) return out;
		for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) {
			Model.SeatCard s = b[r][c];
			if (s == null) continue;
			Model.SeatCard n = seatCard(s.cardId, s.owner);
			n.frozen = s.frozen;
			out[r][c] = n;
		}
		return out;
	}

	@SuppressWarnings("unused")
	private static <T> T orNull(Supplier<T> s) {
		try {
			return s.get();
		} catch (Exception e) {
			return null;
		}
	}
}
