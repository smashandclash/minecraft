package in.smashandclash.minecraft;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The deck and the kit, as the asset pipeline wrote them from sc.cards() / sc.assets() (assets/smashandclash/cards.json, kit.json). */
public final class Deck {
	public static final class Card {
		public int id;
		public String name;
		/** "character" | "effect". */
		public String kind;
		public int top, right, bottom, left;
		public String color;
		public String colorHex;
		public String effect;
		public String does;
		public boolean art;
		public Anim animation;
		public boolean voice;

		public boolean isEffect() {
			return "effect".equals(kind);
		}
	}

	public static final class Anim {
		public Pivot pivot;
		public float artScale;
	}

	public static final class Pivot {
		public float x, y;
	}

	private static final Map<Integer, Card> CARDS = new HashMap<>();
	private static final Map<String, Integer> COLORS = new HashMap<>();
	public static String terms = "";

	private Deck() {}

	public static void load() {
		Gson gson = new Gson();
		List<Card> cards = gson.fromJson(read("cards.json"), new TypeToken<List<Card>>() {}.getType());
		for (Card c : cards) CARDS.put(c.id, c);
		JsonObject kit = gson.fromJson(read("kit.json"), JsonObject.class);
		terms = kit.get("terms").getAsString();
		for (var e : kit.getAsJsonObject("colors").entrySet()) COLORS.put(e.getKey(), Integer.parseInt(e.getValue().getAsString().substring(1), 16));
	}

	private static String read(String name) {
		try (InputStream in = Deck.class.getResourceAsStream("/assets/smashandclash/" + name)) {
			if (in == null) throw new IllegalStateException("missing " + name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Card card(int id) {
		return CARDS.get(id);
	}

	/** A frame colour by name (red, orange, green, blue, purple, pink, gray) as 0xRRGGBB. */
	public static int color(String name) {
		return COLORS.getOrDefault(name, 0x8a8f9c);
	}

	public static Identifier face(int id) {
		return Identifier.fromNamespaceAndPath("smashandclash", "textures/gui/card/" + id + ".png");
	}

	public static final Identifier BACK = Identifier.fromNamespaceAndPath("smashandclash", "textures/gui/card/back.png");
	public static final Identifier LOGO = Identifier.fromNamespaceAndPath("smashandclash", "textures/gui/logo.png");

	public static Identifier art(int id) {
		return Identifier.fromNamespaceAndPath("smashandclash", "textures/gui/art/" + id + ".png");
	}

	public static Identifier attack(int id, String frame) {
		return Identifier.fromNamespaceAndPath("smashandclash", "textures/gui/attack/" + id + "_" + frame + ".png");
	}
}
