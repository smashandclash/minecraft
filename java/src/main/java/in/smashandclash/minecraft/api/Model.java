package in.smashandclash.minecraft.api;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/** The API's shapes (as the SDK's TypeScript types), read with Gson. Fields the API may leave out are null. */
public final class Model {
	private Model() {}

	public static final class Sides {
		public int north, east, south, west;
	}

	public static final class Score {
		public int A, B;
	}

	public static final class Pair<T> {
		public T A, B;
	}

	public static final class ViewScore {
		public int you, opponent;
	}

	public static final class BoardTile {
		/** "A1".."E3": column A-E, row 1-3 (row 1 nearest seat A). */
		public String cell;
		public String card;
		public Integer cardId;
		/** "you" | "opponent". */
		public String owner;
		public boolean frozen;
		public Sides sides;
	}

	public static final class HandCard {
		public String card;
		public int cardId;
		/** "character" | "effect". */
		public String kind;
		public int top, right, bottom, left;
		public String color;
		public String does;
	}

	/** One legal move as data: name is what play() takes. */
	public static final class MoveDetail {
		public String name;
		/** "place" | "overrun" | "hop" | "effect". */
		public String type;
		public Integer cardId;
		public String cell;
		public String effect;
		public String target;
		public String dest;
	}

	public static final class Special {
		public List<CellPiece> chessTiles;
		public List<CellPower> powerTiles;
		public List<String> overrunZones;
	}

	public static final class CellPiece {
		public String cell;
		public String piece;
	}

	public static final class CellPower {
		public String cell;
		public String color;
		public int boost;
	}

	public static final class PendingHop {
		public String from;
		public List<String> options;
	}

	/** What your seat sees. */
	public static final class PlayerView {
		public String ruleset;
		public boolean yourTurn;
		public boolean gameOver;
		public String winner;
		public ViewScore score;
		public String opponent;
		public List<BoardTile> board;
		public List<HandCard> hand;
		public int drawPile;
		public Special special;
		public PendingHop pendingHop;
		public List<String> legalMoves;
		public List<MoveDetail> moves;
	}

	/** A game as the API returns it. */
	public static final class GameState {
		public String id;
		/** "house" | "duel". */
		public String kind;
		/** "waiting" | "active" | "finished" | "abandoned". */
		public String status;
		public String ruleset;
		public String seat;
		public String code;
		public Boolean matchmaking;
		public Pair<String> players;
		public Pair<String> playerKinds;
		public Pair<Integer> ratings;
		public Pair<String> clients;
		public List<String> openSeats;
		public String turn;
		public int moveCount;
		public String lastMove;
		public Score score;
		public String winner;
		public PlayerView view;
		public List<BoardTile> board;
		public String replayUrl;
		public String watchUrl;
		public String watchPage;
	}

	/** A board cell in seat states: row r (0 = row 1) and column c (0 = A). */
	public static final class Cell {
		public int r, c;
	}

	public static final class SeatCard {
		public int cardId;
		public String owner;
		public boolean frozen;
	}

	public static final class OperatorTile {
		public Cell cell;
		public String piece;
	}

	public static final class PowerTile {
		public Cell cell;
		public String color;
		public int boost;
	}

	public static final class SeatHop {
		public Cell cell;
		public List<Cell> options;
		public List<Cell> used;
	}

	/** Your seat's state: the board by card id, your hand, counts for the rest. */
	public static final class SeatState {
		public String ruleset;
		/** board[r][c]: r 0..2 (row 1..3), c 0..4 (column A..E). */
		public SeatCard[][] board;
		public List<Integer> hand;
		public int opponentHand;
		public int deck;
		public String current;
		public boolean gameOver;
		public String winner;
		public int turn;
		public List<OperatorTile> operatorTiles;
		public List<PowerTile> powerTiles;
		public SeatHop pendingHop;
	}

	/** One move as your seat sees it: what was played, its public events, the state after. */
	public static final class SeatMove {
		public int index;
		public String seat;
		public String name;
		public JsonObject move;
		public List<JsonObject> events;
		public SeatState state;
	}

	public static final class SeatSync {
		public String id;
		public String status;
		public String seat;
		public String ruleset;
		public Pair<String> players;
		public Pair<String> playerKinds;
		public Pair<Integer> ratings;
		public Pair<String> clients;
		public int moveCount;
		public Score score;
		public String winner;
		public String firstPlayer;
		public SeatState state;
		public List<SeatMove> moves;
		public String replayUrl;
	}

	/** A finished game's Game Review. */
	public static final class Review {
		public Pair<String> players;
		public String winner;
		public Map<String, Double> accuracy;
		public Integer turningPoint;
		public Integer biggestBlunder;
		public double[] graph;
		public List<ReviewMove> moves;
		public Map<String, String> classes;
	}

	public static final class ReviewMove {
		public int n;
		public String seat;
		public String name;
		/** brilliant | great | best | excellent | good | inaccuracy | mistake | blunder | miss | opening | forced */
		@com.google.gson.annotations.SerializedName("class")
		public String cls;
		public String label;
		public double loss;
		public double evalA;
	}
}
