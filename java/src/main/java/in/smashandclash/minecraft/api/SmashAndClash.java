package in.smashandclash.minecraft.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import in.smashandclash.minecraft.api.Model.GameState;

import java.net.URI;
import java.util.Map;

/**
 * The Smash&Clash client: the calls of @smashandclash/sdk 0.5.0 under the same names, in Java
 * (Minecraft Java Edition runs Java, so the TypeScript SDK can't load here; this follows it call
 * for call, as the DS and PSP clients' C port does). Every call blocks: run it off the render thread.
 *
 *   SmashAndClash sc = new SmashAndClash("minecraft/1.0.0");
 *   Game game = sc.games.startHouse(new Opts().name("Steve").strength(1200));
 *   while (!game.over()) game.play(game.legalMoves().get(0));
 */
public final class SmashAndClash {
	public static final String DEFAULT_BASE_URL = "https://www.smashandclash.in";
	/** The SDK version these calls follow. */
	public static final String SDK_VERSION = "0.5.0";

	public final Http http;
	public final Games games = new Games();

	/** @param client names your app, e.g. "minecraft/1.0.0": games show the other side where you play from. */
	public SmashAndClash(String client) {
		this(DEFAULT_BASE_URL, client);
	}

	public SmashAndClash(String baseUrl, String client) {
		// the X-SDK header: these are the SDK's calls (its version, ported to Java), then the app
		String app = client != null && client.trim().matches("^[\\w.+/-]{1,64}$") ? " " + client.trim() : "";
		this.http = new Http(baseUrl, "smashandclash-sdk/" + SDK_VERSION + "-java" + app, 2);
	}

	/** Options for starting or joining a game (the SDK's StartHouseOptions / DuelOptions / SeatOptions / QuickMatchOptions). */
	public static final class Opts {
		final JsonObject o = new JsonObject();

		public Opts name(String v) {
			if (v != null) o.addProperty("name", v);
			return this;
		}

		/** "mutators" | "classic". */
		public Opts ruleset(String v) {
			if (v != null) o.addProperty("ruleset", v);
			return this;
		}

		/** Who plays your seat: "agent" | "person". */
		public Opts as(String v) {
			if (v != null) o.addProperty("as", v);
			return this;
		}

		/** A house game: how strong the house plays, as an ELO (800-1600). */
		public Opts strength(int v) {
			o.addProperty("strength", v);
			return this;
		}

		/** Your player's rating (their ELO, as your app keeps it). */
		public Opts rating(int v) {
			o.addProperty("rating", v);
			return this;
		}

		/** A duel: "agent" | "person"; quick match: "any" | "agent" | "person". */
		public Opts opponent(String v) {
			if (v != null) o.addProperty("opponent", v);
			return this;
		}

		public Opts opponentName(String v) {
			if (v != null) o.addProperty("opponentName", v);
			return this;
		}

		JsonObject with(String key, String value) {
			JsonObject copy = o.deepCopy();
			copy.addProperty(key, value);
			return copy;
		}
	}

	/** The rules, short. */
	public String rules() {
		return http.get("/api/v1/games/rules", null, null).getAsJsonObject().get("rules").getAsString();
	}

	private Game game(JsonElement r) {
		JsonObject o = r.getAsJsonObject();
		GameState s = Http.GSON.fromJson(o.get("game"), GameState.class);
		String invite = o.has("inviteUrl") && !o.get("inviteUrl").isJsonNull() ? o.get("inviteUrl").getAsString() : null;
		return new Game(http, s, o.get("playerToken").getAsString(), invite);
	}

	/** What games.open() opened: kind is game (a seat) | watch | replay | challenge. */
	public record Opened(String kind, Links.Link link, Game game, GameState watched) {}

	public final class Games {
		/** Play against the Smash&Clash house opponent. */
		public Game startHouse(Opts o) {
			return game(http.post("/api/v1/games", o.with("mode", "house"), null, false));
		}

		/** Open a duel you play: another player joins with game.code(); opponent "person" gives an invite link. */
		public Game createDuel(Opts o) {
			return game(http.post("/api/v1/games", o.with("mode", "duel"), null, false));
		}

		/** Join a duel by its 6-letter code. */
		public Game joinDuel(String code, Opts o) {
			return game(http.post("/api/v1/games/join", o.with("code", code), null, false));
		}

		/** The online queue: paired now, or game.waiting() - waitForTurn() holds your place; leave() to go. */
		public Game quickMatch(Opts o) {
			return game(http.post("/api/v1/games", o.with("mode", "quick"), null, false));
		}

		/** Take the seat an invite link opens (opening it again elsewhere moves the seat). */
		public Game claim(String inviteUrl, Opts o) {
			URI u = URI.create(inviteUrl.startsWith("http") ? inviteUrl : DEFAULT_BASE_URL + "/" + inviteUrl.replaceFirst("^/", ""));
			Links.Link l = Links.parseLink(u.toString());
			if (l == null || !"invite".equals(l.kind()))
				throw new SmashAndClashError(400, "bad_request", "That is not an invite link.", "Invite links look like https://www.smashandclash.in/?game=g_...&invite=inv_...");
			// opening the invite again moves the seat to the new token: safe to repeat
			return game(http.post("/api/v1/games/" + Http.enc(l.game()) + "/claim", o.with("invite", l.invite()), null, true));
		}

		/** Resume a game you hold the token for. */
		public Game resume(String id, String playerToken) {
			GameState s = new GameState();
			s.id = id;
			Game g = new Game(http, s, playerToken, null);
			g.refresh();
			return g;
		}

		/** Any game as a spectator (the public board, never a hand). */
		public GameState watch(String id) {
			return Http.GSON.fromJson(http.get("/api/v1/games/" + Http.enc(id), null, null), GameState.class);
		}

		/** The game a room code belongs to, as a spectator sees it. */
		public GameState byCode(String code) {
			return Http.GSON.fromJson(http.get("/api/v1/games/code/" + Http.enc(code.trim().toUpperCase()), null, null), GameState.class);
		}

		/** Open whatever a player shares from any client: a code or join link (a seat; a full room opens to watch), an invite, a watch link. */
		public Opened open(String input, Opts o) {
			Links.Link link = Links.parseLink(input);
			if (link == null) throw new SmashAndClashError(400, "bad_request", "That is not a Smash&Clash code or link.", "Paste a 6-letter code, or a join, invite or watch link.");
			switch (link.kind()) {
				case "code":
					try {
						return new Opened("game", link, joinDuel(link.code(), o), null);
					} catch (SmashAndClashError e) {
						// the room is full (or under way): watch it instead
						if (e.status == 409) return new Opened("watch", link, null, byCode(link.code()));
						throw e;
					}
				case "invite":
					return new Opened("game", link, claim(link.url(), o), null);
				case "watch":
					return new Opened("watch", link, null, watch(link.game()));
				default:
					return new Opened(link.kind(), link, null, null);
			}
		}

		/** A finished game's Game Review. */
		public Model.Review review(String id) {
			return Http.GSON.fromJson(http.get("/api/v1/games/" + Http.enc(id) + "/review", null, null), Model.Review.class);
		}
	}

	/** The deck: every card, with each character's art, colours and attack animation (raw JSON). */
	public JsonElement cards() {
		return http.get("/api/v1/games/cards", Map.of(), null).getAsJsonObject().get("cards");
	}
}
