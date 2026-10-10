package in.smashandclash.minecraft.api;

import com.google.gson.JsonObject;
import in.smashandclash.minecraft.api.Model.GameState;
import in.smashandclash.minecraft.api.Model.PlayerView;
import in.smashandclash.minecraft.api.Model.Review;
import in.smashandclash.minecraft.api.Model.SeatSync;

import java.util.List;

/** A game you play (the SDK's Game): holds your player token; every method refreshes its state. Calls block: run them off the render thread. */
public final class Game {
	private final Http http;
	public volatile GameState state;
	/** Your seat's secret (keep it to resume the game later). */
	public final String playerToken;
	/** A duel opened for a person: the link to send them. */
	public final String inviteUrl;

	Game(Http http, GameState state, String playerToken, String inviteUrl) {
		this.http = http;
		this.state = state;
		this.playerToken = playerToken;
		this.inviteUrl = inviteUrl;
	}

	public String id() {
		return state.id;
	}

	/** A duel waiting for its second player by code: the code to share. */
	public String code() {
		return state.code;
	}

	/** Still waiting for the other player (a code, an invite, or the quick-match queue). */
	public boolean waiting() {
		return "waiting".equals(state.status);
	}

	public String seat() {
		return state.seat == null ? "A" : state.seat;
	}

	/** The other player's name (null until someone joins). */
	public String opponent() {
		if (state.players == null) return null;
		return "A".equals(seat()) ? state.players.B : state.players.A;
	}

	public PlayerView view() {
		return state.view;
	}

	public List<String> legalMoves() {
		return state.view == null || state.view.legalMoves == null ? List.of() : state.view.legalMoves;
	}

	public boolean yourTurn() {
		return state.view != null && state.view.yourTurn && "active".equals(state.status);
	}

	public boolean over() {
		return "finished".equals(state.status) || "abandoned".equals(state.status);
	}

	/** "you" / "opponent" / "draw" once finished, else null. */
	public String winner() {
		String w = state.winner;
		if (w == null) return null;
		if ("draw".equals(w)) return "draw";
		return w.equals(seat()) ? "you" : "opponent";
	}

	public String replayUrl() {
		return state.replayUrl;
	}

	/** A game waiting for its second player by code: the link that opens it on any client. */
	public String joinLink() {
		return state.code == null ? null : Links.joinLink(state.code, http.baseUrl);
	}

	/** The game's live page, for anyone to watch. */
	public String watchLink() {
		return state.watchPage != null ? state.watchPage : Links.watchLink(state.id, http.baseUrl);
	}

	private String path(String tail) {
		return "/api/v1/games/" + Http.enc(state.id) + tail;
	}

	private GameState read(com.google.gson.JsonElement e) {
		return Http.GSON.fromJson(e, GameState.class);
	}

	/** Play a move by its name from legalMoves (e.g. "Pengu@C2"). Against the house it has answered when this returns. */
	public GameState play(String move) {
		int before = state.moveCount;
		JsonObject body = new JsonObject();
		body.addProperty("move", move);
		try {
			state = read(http.post(path("/moves"), body, playerToken, false));
		} catch (Http.NetworkError err) {
			// the connection dropped: the move may have landed anyway. Only your move can add
			// to the count on your turn, so look before sending again.
			refresh();
			if (state.moveCount > before) return state;
			if (!yourTurn()) throw err;
			state = read(http.post(path("/moves"), body, playerToken, false));
		}
		return state;
	}

	/** Fetch the game again. */
	public GameState refresh() {
		state = read(http.get(path(""), null, playerToken));
		return state;
	}

	/** Long-poll (up to seconds, max 20) until it is your turn or the game ends. */
	public GameState waitForTurn(int seconds) {
		state = read(http.get(path("/wait"), Http.query("timeout", Math.max(1, Math.min(20, seconds))), playerToken));
		return state;
	}

	/** Leave a game nobody has joined (a waiting duel or a quick-match ticket); never resigns one that started (409). */
	public GameState leave() {
		JsonObject body = new JsonObject();
		body.addProperty("ifWaiting", true);
		try {
			state = read(http.post(path("/resign"), body, playerToken, false));
		} catch (Http.NetworkError err) {
			refresh();
			if (over()) return state;
			if (!waiting()) throw new SmashAndClashError(409, "conflict", "the game has started", "Play it: game.refresh() has the board.");
			state = read(http.post(path("/resign"), body, playerToken, false));
		}
		return state;
	}

	/** Resign (the other side wins), or call off a game nobody joined. */
	public GameState resign() {
		try {
			state = read(http.post(path("/resign"), null, playerToken, false));
		} catch (Http.NetworkError err) {
			refresh();
			if (over()) return state;
			state = read(http.post(path("/resign"), null, playerToken, false));
		}
		return state;
	}

	/** Your seat's state and every move since `since`, each with its public events. With wait > 0 it long-polls for news. */
	public SeatSync sync(int since, int wait) {
		var q = wait > 0
			? Http.query("since", since, "timeout", Math.max(1, Math.min(20, wait)), "status", state.status)
			: Http.query("since", since);
		return Http.GSON.fromJson(http.get(path("/sync"), q, playerToken), SeatSync.class);
	}

	/** The finished game's Game Review. */
	public Review review() {
		return Http.GSON.fromJson(http.get(path("/review"), null, null), Review.class);
	}
}
