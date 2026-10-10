package in.smashandclash.minecraft.table;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Each player's record in this world (data/smashandclash_players.json): rating, games, wins, the ruleset they pick. */
public final class Players {
	public static final class Rec {
		public int rating = 1200;
		public int played;
		public int won;
		/** "mutators" (the default) | "classic". */
		public String ruleset = "mutators";
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Map<String, Rec> all;
	private static MinecraftServer loadedFor;

	private Players() {}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("smashandclash_players.json");
	}

	public static synchronized Rec get(MinecraftServer server, UUID player) {
		if (all == null || loadedFor != server) {
			loadedFor = server;
			all = new HashMap<>();
			try {
				Path f = file(server);
				if (Files.exists(f)) all = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<Map<String, Rec>>() {}.getType());
			} catch (Exception ignored) {
			}
			if (all == null) all = new HashMap<>();
		}
		return all.computeIfAbsent(player.toString(), k -> new Rec());
	}

	public static synchronized void save(MinecraftServer server) {
		try {
			Path f = file(server);
			Files.createDirectories(f.getParent());
			Files.writeString(f, GSON.toJson(all), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}

	/** The ELO update: score 1 / 0.5 / 0 against an opponent rated `opp`, K = 32. */
	public static int elo(int rating, int opp, double score) {
		double expected = 1 / (1 + Math.pow(10, (opp - rating) / 400.0));
		return (int) Math.round(rating + 32 * (score - expected));
	}
}
