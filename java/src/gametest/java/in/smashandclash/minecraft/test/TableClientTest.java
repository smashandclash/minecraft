package in.smashandclash.minecraft.test;

import in.smashandclash.minecraft.ModContent;
import in.smashandclash.minecraft.api.Game;
import in.smashandclash.minecraft.api.Links;
import in.smashandclash.minecraft.api.Model;
import in.smashandclash.minecraft.api.SmashAndClash;
import in.smashandclash.minecraft.table.Hands;
import in.smashandclash.minecraft.table.TableBlock;
import in.smashandclash.minecraft.table.TableBlockEntity;
import in.smashandclash.minecraft.table.TableSession;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The table in a real client, screenshot by screenshot: it unfolds; a house game with its clashes;
 * every effect animation (flip, boulder, freeze, swap); a room-code game and a quick match against a
 * second player on the network (the API port, playing from this test); the villagers watching.
 */
public class TableClientTest implements FabricClientGameTest {
	static final BlockPos TABLE = new BlockPos(0, 100, 0);
	int shot = 0;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (Boolean.getBoolean("snc.media")) return;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			TestServerContext server = sp.getServer();
			server.runCommand("gamemode creative @a");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("gamerule advance_time false");
			server.runCommand("fill -10 99 -10 10 99 10 minecraft:grass_block");
			server.runCommand("fill -10 100 -10 10 106 10 minecraft:air");
			server.runCommand("summon minecraft:villager 7 100 -3");
			server.runCommand("summon minecraft:villager -7 100 2");
			server.runCommand("summon minecraft:villager 6 100 6");
			seatA(server);
			ctx.waitTicks(40);
			sp.getConnection().waitForChunksRender();

			// 1. a new table unfolds; its footprint is solid
			server.runOnServer(s -> s.overworld().setBlockAndUpdate(TABLE, ModContent.TABLE.defaultBlockState().setValue(TableBlock.FACING, Direction.NORTH)));
			ctx.waitTicks(10);
			shot(ctx, "unfold");
			ctx.waitTicks(40);
			shot(ctx, "table");
			int barriers = server.computeOnServer(s -> {
				int n = 0;
				for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (s.overworld().getBlockState(TABLE.offset(x, 0, z)).is(Blocks.BARRIER)) n++;
				return n;
			});
			System.out.println("[TableClientTest] barriers around the table: " + barriers);
			server.runOnServer(s -> session(s).debugPop("SMASH!"));
			ctx.waitTicks(12);
			shot(ctx, "pops");

			// 2. a house game: clashes, then every effect
			server.runOnServer(s -> session(s).action(player(s), "house", ""));
			waitPlaying(server);
			ctx.waitTicks(90);
			shot(ctx, "house-started");
			in.smashandclash.minecraft.table.Tween.TIME_SCALE = 3;
			for (int turn = 0; turn < 3; turn++) {
				if (!myTurn(server)) break;
				String played = server.computeOnServer(TableClientTest::playOne);
				if (played == null) break;
				burst(ctx, server, "house-" + played);
			}
			in.smashandclash.minecraft.table.Tween.TIME_SCALE = 1;
			waitIdle(server);
			// the effects at a quarter of the speed, so the frames between land in the screenshots
			in.smashandclash.minecraft.table.Tween.TIME_SCALE = 4;
			for (String fx : new String[] {"flip", "boulder", "freeze", "swap"}) {
				server.runOnServer(s -> session(s).debugEffect(fx));
				server.waitFor(s -> session(s).animating(), 100);
				for (int k = 0; k < 8; k++) {
					ctx.waitTicks(fx.equals("flip") ? 14 : 10);
					shot(ctx, "fx-" + fx + "-" + k);
				}
				waitIdle(server);
			}
			in.smashandclash.minecraft.table.Tween.TIME_SCALE = 1;
			// third person, mid-game, card in hand
			server.runOnServer(TableClientTest::holdFirst);
			ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
			ctx.waitTicks(10);
			shot(ctx, "third-person-front");
			ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
			// the villagers, from above
			server.runCommand("tp @a 0.5 104 6.5 180 40");
			ctx.waitTicks(60);
			shot(ctx, "crowd-above");
			seatA(server);
			server.runOnServer(s -> session(s).action(player(s), "resign", ""));
			server.waitFor(s -> !"playing".equals(session(s).status()), 400);
			ctx.waitTicks(40);
			shot(ctx, "house-over");

			// 3. a room code: a second player joins over the network and plays from here
			server.runOnServer(s -> session(s).action(player(s), "room", ""));
			server.waitFor(s -> session(s).roomCode() != null, 400);
			String code = server.computeOnServer(s -> session(s).roomCode());
			System.out.println("[TableClientTest] room code " + code);
			Thread remote = remotePlayer(() -> TableSession.SC.games.joinDuel(code, new SmashAndClash.Opts().name("RoomFriend").as("person").rating(1200)));
			waitPlaying(server);
			ctx.waitTicks(100);
			shot(ctx, "room-started");
			for (int turn = 0; turn < 2; turn++) {
				server.waitFor(s -> myTurnNow(s) || !"playing".equals(session(s).status()), 1200);
				if (!myTurn(server)) break;
				String played = server.computeOnServer(TableClientTest::playOne);
				if (played == null) break;
				ctx.waitTicks(40);
				shot(ctx, "room-" + played);
			}
			server.runCommand("tp @a 0.5 100.6 4.4 180 20");
			ctx.waitTicks(40);
			shot(ctx, "room-opponent");
			seatA(server);
			server.runOnServer(s -> session(s).action(player(s), "resign", ""));
			server.waitFor(s -> !"playing".equals(session(s).status()), 400);
			remote.interrupt();
			ctx.waitTicks(40);

			// 4. quick match: both join the queue, the queue pairs them
			Thread queued = remotePlayer(() -> {
				Thread.sleep(1500);
				return TableSession.SC.games.quickMatch(new SmashAndClash.Opts().name("QueueFriend").as("person").rating(1200).opponent("any"));
			});
			server.runOnServer(s -> session(s).action(player(s), "quick", ""));
			server.waitFor(s -> "playing".equals(session(s).status()), 1200);
			ctx.waitTicks(80);
			shot(ctx, "quick-match");
			System.out.println("[TableClientTest] quick match status " + server.computeOnServer(s -> session(s).status()));
			server.runOnServer(s -> {
				if ("playing".equals(session(s).status())) session(s).action(player(s), "resign", "");
				else session(s).action(player(s), "cancel", "");
			});
			queued.interrupt();
			ctx.waitTicks(60);
		}
	}

	/** The second player, somewhere else on the network: plays the first legal move whenever it is their turn. */
	static Thread remotePlayer(java.util.concurrent.Callable<Game> open) {
		Thread t = new Thread(() -> {
			try {
				Game g = open.call();
				while (!g.over() && !Thread.currentThread().isInterrupted()) {
					g.waitForTurn(20);
					if (g.over()) break;
					if (g.yourTurn() && !g.legalMoves().isEmpty()) {
						Thread.sleep(1200);
						g.play(g.legalMoves().get(0));
					}
				}
			} catch (Exception e) {
				System.out.println("[TableClientTest] remote player: " + e);
			}
		}, "remote-player");
		t.setDaemon(true);
		t.start();
		return t;
	}

	void shot(ClientGameTestContext ctx, String name) {
		ctx.takeScreenshot(String.format("%02d-%s", shot++, name.replaceAll("[^A-Za-z0-9-]", "_")));
	}

	/** Through a move's animation: the place, the clash and its reasons, the flips. */
	void burst(ClientGameTestContext ctx, TestServerContext server, String name) {
		server.waitFor(s -> session(s).animating(), 200);
		int[] at = {4, 10, 10, 10, 12, 14, 16};
		for (int k = 0; k < at.length; k++) {
			ctx.waitTicks(at[k]);
			shot(ctx, name + "-" + k);
		}
	}

	static void seatA(TestServerContext server) {
		server.runCommand("tp @a 0.5 100.8 3.0 180 30");
	}

	static void waitPlaying(TestServerContext server) {
		server.waitFor(s -> "playing".equals(session(s).status()) && session(s).viewOf(player(s)) != null, 1200);
	}

	static void waitIdle(TestServerContext server) {
		server.waitFor(s -> !session(s).animating(), 600);
	}

	static boolean myTurn(TestServerContext server) {
		server.waitFor(s -> myTurnNow(s) || !"playing".equals(session(s).status()), 1200);
		return server.computeOnServer(TableClientTest::myTurnNow);
	}

	static boolean myTurnNow(MinecraftServer s) {
		var v = session(s).viewOf(player(s));
		return "playing".equals(session(s).status()) && !session(s).animating() && v != null && v.yourTurn;
	}

	static TableBlockEntity be(MinecraftServer s) {
		return (TableBlockEntity) s.overworld().getBlockEntity(TABLE);
	}

	static TableSession session(MinecraftServer s) {
		return be(s).session();
	}

	static ServerPlayer player(MinecraftServer s) {
		return s.getPlayerList().getPlayers().get(0);
	}

	/** Put the first playable card in the main hand (as a player picks it from the hotbar). */
	static void holdFirst(MinecraftServer s) {
		ServerPlayer p = player(s);
		Model.PlayerView v = session(s).viewOf(p);
		if (v == null || v.hand == null) return;
		for (int i = 0; i < 9; i++) if (Hands.cardOf(p.getInventory().getItem(i)) >= 0) {
			p.getInventory().setSelectedSlot(i);
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(i));
			return;
		}
	}

	/** Play one placement (or the hop) the way a player would. Returns its name. */
	static String playOne(MinecraftServer s) {
		ServerPlayer p = player(s);
		Model.PlayerView v = session(s).viewOf(p);
		if (v == null || !v.yourTurn || v.moves == null) return null;
		Model.MoveDetail pick = null;
		for (Model.MoveDetail m : v.moves) if ("hop".equals(m.type) || "place".equals(m.type)) { pick = m; break; }
		if (pick == null) return null;
		ItemStack held = ItemStack.EMPTY;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (pick.cardId != null && Hands.cardOf(st) == pick.cardId) {
				held = st;
				if (i < 9) {
					p.getInventory().setSelectedSlot(i);
					p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(i));
				}
			}
		}
		String cell = "hop".equals(pick.type) ? (pick.dest != null ? pick.dest : v.pendingHop.from) : pick.cell;
		int[] rc = Links.cellToRC(cell);
		session(s).onTile(p, rc[0], rc[1], held);
		return pick.name;
	}
}
