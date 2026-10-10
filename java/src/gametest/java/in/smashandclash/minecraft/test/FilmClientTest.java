package in.smashandclash.minecraft.test;

import in.smashandclash.minecraft.Deck;
import in.smashandclash.minecraft.ModContent;
import in.smashandclash.minecraft.api.Links;
import in.smashandclash.minecraft.api.Model;
import in.smashandclash.minecraft.table.Hands;
import in.smashandclash.minecraft.table.TableBlock;
import in.smashandclash.minecraft.table.TableBlockEntity;
import in.smashandclash.minecraft.table.TableSession;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Films a real game on the table, frame by frame: the server's clock is frozen and stepped one tick
 * at a time, and each tick is drawn three times (a third of a tick apart) at 1920 x 1080 - 60 fps
 * with nothing dropped. Each scene has its own camera, moved on the client between frames.
 * Frames land in run/film/<scene>/; tools/film.sh turns them into clips.
 *
 *   ./gradlew runClientGameTest -Pmedia
 */
public class FilmClientTest implements FabricClientGameTest {
	static final BlockPos TABLE = new BlockPos(0, 100, 0);
	static final double CX = 0.5, CY = 101.2, CZ = 0.5; // the board's centre
	ClientGameTestContext ctx;
	TestServerContext server;
	Path root;
	String scene;
	int frame;
	double time; // seconds into the scene

	interface Cam {
		double[] at(double t); // x, y, z, yaw, pitch
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!Boolean.getBoolean("snc.media")) return;
		this.ctx = ctx;
		root = Path.of("film");
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			server = sp.getServer();
			for (String c : new String[] {"gamemode creative @a", "time set 6000", "weather clear", "gamerule advance_time false", "gamerule advance_weather false",
				"fill -14 98 -14 14 98 14 minecraft:dirt", "fill -14 99 -14 14 99 14 minecraft:grass_block", "fill -14 100 -14 14 108 14 minecraft:air",
				// a little garden for the shots
				"fill -14 100 -14 14 100 -12 minecraft:oak_leaves", "setblock -6 100 -6 minecraft:poppy", "setblock 5 100 -7 minecraft:dandelion", "setblock -8 100 4 minecraft:cornflower", "setblock 7 100 5 minecraft:oxeye_daisy",
				"setblock -9 100 -9 minecraft:lantern", "setblock 9 100 -9 minecraft:lantern",
				"summon minecraft:villager 9 100 -2", "summon minecraft:villager -9 100 1", "summon minecraft:villager 8 100 4", "summon minecraft:villager -8 100 -4"})
				server.runCommand(c);
			ctx.runOnClient(mc -> {
				hud(mc, true);
				mc.options.fov().set(70);
			});
			server.runCommand("tp @a 0.5 100 3.6 180 20");
			ctx.waitTicks(60);
			sp.getConnection().waitForChunksRender();
			server.runCommand("tick freeze");
			ctx.waitTicks(5);

			// 1. the table: placed, it unfolds - a slow orbit
			begin("01-unfold");
			server.runOnServer(s -> s.overworld().setBlockAndUpdate(TABLE, ModContent.TABLE.defaultBlockState().setValue(TableBlock.FACING, Direction.NORTH)));
			film(80, t -> orbit(205 - 22 * t, 3.3, 1.9, 0.0));

			// 2. a game against the house: the deal, first person
			server.runOnServer(s -> session(s).action(player(s), "house", ""));
			waitFor(s -> "playing".equals(session(s).status()) && session(s).animating(), 30000);
			begin("02-deal");
			ctx.runOnClient(mc -> hud(mc, false));
			film(60, t -> seat(0.12 * t));
			filmWhile(s -> session(s).animating(), 160, t -> seat(0.12 + 0.03 * t));

			// 3. the moves: hold a card (the tiles you can play glow), play it, the clash and the captures
			for (int turn = 0; turn < 9; turn++) {
				if (!waitMyTurn()) break;
				begin(String.format(Locale.ROOT, "03-turn%02d", turn + 1));
				server.runOnServer(FilmClientTest::holdBest);
				film(30, t -> seat(0));
				String played = server.computeOnServer(FilmClientTest::playBest);
				if (played == null) break;
				waitFor(s -> session(s).animating(), 30000);
				boolean side = turn % 3 == 1;
				if (side) ctx.runOnClient(mc -> hud(mc, true));
				Cam cam = side ? t -> sideShot(t) : (turn % 3 == 2 ? t -> lowShot(t) : t -> seat(0.05 * Math.sin(t)));
				filmWhile(s -> session(s).animating(), 220, cam);
				// the clash comes back from the network after the card lands: keep the same shot through it
				for (int k = 0; k < 2; k++) {
					waitFor(s -> session(s).animating() || !"playing".equals(session(s).status()), 2500);
					if (!server.computeOnServer(s -> session(s).animating())) break;
					filmWhile(s -> session(s).animating(), 220, cam);
				}
				film(10, cam);
				ctx.runOnClient(mc -> hud(mc, false));
				// the house answers (its move is drawn too)
				waitFor(s -> session(s).animating() || myTurnNow(s) || !"playing".equals(session(s).status()), 30000);
				if (server.computeOnServer(s -> session(s).animating())) {
					begin(String.format(Locale.ROOT, "04-reply%02d", turn + 1));
					ctx.runOnClient(mc -> hud(mc, true));
					filmWhile(s -> session(s).animating(), 220, t -> opponentShot(t));
					ctx.runOnClient(mc -> hud(mc, false));
				}
				if (turn == 4) effects();
			}
			// 5. the crowd, from above
			begin("06-crowd");
			ctx.runOnClient(mc -> hud(mc, true));
			film(90, t -> new double[] {CX + 4.5 * Math.sin(0.25 * t), CY + 3.6, CZ + 5.5 - 0.4 * t, 180 - 30 * Math.sin(0.25 * t), 34});
			// 6. play it out to the end, filming the finish
			for (int turn = 0; turn < 12 && "playing".equals(server.computeOnServer(s -> session(s).status())); turn++) {
				if (!waitMyTurn()) break;
				server.runOnServer(FilmClientTest::playBest);
				waitFor(s -> session(s).animating(), 30000);
				step(1);
				while (server.computeOnServer(s -> session(s).animating())) step(1);
				waitFor(s -> session(s).animating() || myTurnNow(s) || !"playing".equals(session(s).status()), 30000);
				while (server.computeOnServer(s -> session(s).animating())) step(1);
			}
			begin("07-finish");
			ctx.runOnClient(mc -> hud(mc, false));
			film(140, t -> seat(0.05 * t));
			server.runCommand("tick unfreeze");
		}
	}

	/** Every effect, in turn (flip, boulder, freeze, swap), from a low side angle. */
	void effects() {
		ctx.runOnClient(mc -> hud(mc, true));
		String[] fx = {"flip", "boulder", "freeze", "swap"};
		for (int i = 0; i < fx.length; i++) {
			String kind = fx[i];
			server.runOnServer(s -> session(s).debugEffect(kind));
			begin("05-fx-" + kind);
			int k = i;
			filmWhile(s -> session(s).animating(), 260, t -> fxShot(t, k));
			film(8, t -> fxShot(1, k));
		}
		ctx.runOnClient(mc -> hud(mc, false));
	}

	/** Hide or show the HUD (F1): cinematic shots without it, the player's view with it. */
	static void hud(net.minecraft.client.Minecraft mc, boolean hidden) {
		if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
	}

	/* --------------------------------- cameras --------------------------------- */

	/** Around the table at `deg` (0 = south of it, the player's side), radius r, height h above the board. */
	static double[] orbit(double deg, double r, double h, double lookDown) {
		double a = Math.toRadians(deg);
		double x = CX + Math.sin(a) * r, z = CZ - Math.cos(a) * r;
		return look(x, CY + h, z, CX, CY - 0.1 + lookDown, CZ);
	}

	/** Seat A, first person: just behind the board's near edge. */
	static double[] seat(double drift) {
		return look(CX + drift, 101.62, 2.35, CX, CY - 0.05, CZ - 0.1);
	}

	static double[] sideShot(double t) {
		return look(CX - 1.55 + 0.12 * t, CY + 0.85, CZ + 0.75, CX, CY, CZ - 0.05);
	}

	static double[] lowShot(double t) {
		return look(CX + 1.25 - 0.1 * t, CY + 0.55, CZ + 1.15, CX - 0.05, CY + 0.02, CZ - 0.05);
	}

	static double[] opponentShot(double t) {
		return look(CX + 0.65 - 0.08 * t, CY + 1.0, CZ + 1.45, CX - 0.05, CY + 0.05, CZ - 0.3);
	}

	/** The effects: a close, high three-quarter view, so the whole board reads as it turns. */
	static double[] fxShot(double t, int k) {
		double side = k % 2 == 0 ? 1 : -1;
		return look(CX + side * (1.05 - 0.1 * t), CY + 1.3, CZ + 1.2, CX, CY - 0.05, CZ - 0.05);
	}

	static double[] look(double x, double y, double z, double tx, double ty, double tz) {
		double dx = tx - x, dy = ty - y, dz = tz - z;
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		return new double[] {x, y, z, yaw, pitch};
	}

	/* --------------------------------- frames --------------------------------- */

	void begin(String name) {
		scene = name;
		frame = 0;
		time = 0;
		try {
			Files.createDirectories(root.resolve(name));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** One server tick, drawn three times (0, 1/3, 2/3 of the tick) with the camera where `cam` says. */
	void step(int ticks, Cam cam) {
		for (int i = 0; i < ticks; i++) {
			server.runCommand("tick step 1");
			ctx.waitTick();
			for (int sub = 0; sub < 3; sub++) {
				if (cam != null) {
					double[] p = cam.at(time);
					ctx.runOnClient(mc -> {
						var pl = mc.player;
						if (pl == null) return;
						// eye height 1.62: the camera is where the player's eyes are
						pl.snapTo(p[0], p[1] - 1.62, p[2], (float) p[3], (float) p[4]);
						pl.setOldPosAndRot();
						pl.setYHeadRot((float) p[3]);
					});
					float delta = sub / 3f;
					ctx.takeScreenshot(TestScreenshotOptions.of(String.format(Locale.ROOT, "%05d", frame++)).withSize(1920, 1080).withDeltaTicks(delta).disableCounterPrefix().withDestinationDir(root.resolve(scene).toAbsolutePath()));
				}
				time += 1 / 60.0;
			}
		}
	}

	void step(int ticks) {
		for (int i = 0; i < ticks; i++) {
			server.runCommand("tick step 1");
			ctx.waitTick();
		}
	}

	void film(int ticks, Cam cam) {
		step(ticks, cam);
	}

	/** Film while the table is animating (at most `max` ticks). */
	void filmWhile(java.util.function.Predicate<MinecraftServer> on, int max, Cam cam) {
		for (int i = 0; i < max && server.computeOnServer(on::test); i++) step(1, cam);
	}

	/** Wait (in real time: the clock is frozen) for something the network brings, e.g. the house's reply. */
	void waitFor(java.util.function.Predicate<MinecraftServer> p, long ms) {
		long until = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < until && !server.computeOnServer(p::test)) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	boolean waitMyTurn() {
		waitFor(s -> myTurnNow(s) || !"playing".equals(session(s).status()), 40000);
		return server.computeOnServer(FilmClientTest::myTurnNow);
	}

	/* ---------------------------------- play ---------------------------------- */

	static boolean myTurnNow(MinecraftServer s) {
		var v = session(s).viewOf(player(s));
		return "playing".equals(session(s).status()) && !session(s).animating() && v != null && v.yourTurn;
	}

	static TableSession session(MinecraftServer s) {
		return ((TableBlockEntity) s.overworld().getBlockEntity(TABLE)).session();
	}

	static ServerPlayer player(MinecraftServer s) {
		return s.getPlayerList().getPlayers().get(0);
	}

	/** The placement that captures the most (a simple greedy player), or the hop. */
	static Model.MoveDetail best(Model.PlayerView v) {
		if (v == null || v.moves == null) return null;
		Model.MoveDetail best = null;
		int bestGain = -1;
		for (Model.MoveDetail m : v.moves) {
			if ("hop".equals(m.type)) return m;
			if (!"place".equals(m.type) || m.cardId == null) continue;
			Deck.Card c = Deck.card(m.cardId);
			if (c == null) continue;
			int[] rc = Links.cellToRC(m.cell);
			int[] mine = Links.boardSides(c.top, c.right, c.bottom, c.left, "A");
			int gain = 0;
			int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
			for (int k = 0; k < 4; k++) {
				String cell = (rc[0] + dirs[k][0] >= 0 && rc[0] + dirs[k][0] <= 2 && rc[1] + dirs[k][1] >= 0 && rc[1] + dirs[k][1] <= 4) ? Links.rcToCell(rc[0] + dirs[k][0], rc[1] + dirs[k][1]) : null;
				if (cell == null) continue;
				for (Model.BoardTile t : v.board) {
					if (!cell.equals(t.cell) || !"opponent".equals(t.owner) || t.frozen || t.sides == null) continue;
					int[] theirs = {t.sides.north, t.sides.east, t.sides.south, t.sides.west};
					if (mine[k] >= theirs[(k + 2) % 4]) gain++;
				}
			}
			if (gain > bestGain) {
				bestGain = gain;
				best = m;
			}
		}
		return best;
	}

	static void holdBest(MinecraftServer s) {
		ServerPlayer p = player(s);
		Model.MoveDetail m = best(session(s).viewOf(p));
		if (m == null || m.cardId == null) return;
		for (int i = 0; i < 9; i++) if (Hands.cardOf(p.getInventory().getItem(i)) == m.cardId) {
			p.getInventory().setSelectedSlot(i);
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(i));
		}
	}

	static String playBest(MinecraftServer s) {
		ServerPlayer p = player(s);
		Model.PlayerView v = session(s).viewOf(p);
		Model.MoveDetail m = best(v);
		if (m == null) return null;
		ItemStack held = ItemStack.EMPTY;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (m.cardId != null && Hands.cardOf(p.getInventory().getItem(i)) == m.cardId) held = p.getInventory().getItem(i);
		String cell = "hop".equals(m.type) ? (m.dest != null ? m.dest : v.pendingHop.from) : m.cell;
		int[] rc = Links.cellToRC(cell);
		session(s).onTile(p, rc[0], rc[1], held);
		return m.name;
	}
}
