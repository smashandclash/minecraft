package in.smashandclash.minecraft;

/**
 * The opponent at your level wears a player's handle, never a label for what it is: the same
 * shapes the lobby has (a minted Adjective+Noun+digits, or a first name), seeded by the game id
 * so every client shows the same name for the same game.
 */
public final class Persona {
	private static final String[] ADJECTIVES = {"Brave", "Swift", "Sneaky", "Mighty", "Wobbly", "Spicy", "Turbo", "Cosmic", "Jolly", "Rapid", "Zesty", "Lucky", "Cheeky", "Bouncy", "Fuzzy", "Nifty", "Bold", "Snappy", "Plucky", "Stormy", "Frosty", "Toasty"};
	private static final String[] NOUNS = {"Otter", "Tiger", "Pengu", "Yeti", "Falcon", "Walrus", "Comet", "Ninja", "Badger", "Phoenix", "Koala", "Bison", "Hawk", "Panda", "Wombat", "Moose", "Lynx", "Heron", "Gecko", "Puffin", "Quokka", "Ibex"};
	private static final String[] FIRST_NAMES = {"arjun", "priya", "rohan", "aisha", "kabir", "meera", "ishan", "tara", "zoya", "sana", "kenji", "yuki", "mei", "jisoo", "hana", "sora", "leo", "maya", "nina", "omar", "theo", "aria", "finn", "luca", "noor", "ivy"};

	private Persona() {}

	private static final class Rng {
		int a;

		Rng(String text) {
			a = 0x811c9dc5; // 2166136261
			for (int i = 0; i < text.length(); i++) a = (a ^ text.charAt(i)) * 16777619;
		}

		double next() {
			a = a + 0x6d2b79f5;
			int t = (a ^ (a >>> 15)) * (1 | a);
			t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
			return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
		}

		String pick(String[] xs) {
			return xs[Math.min(xs.length - 1, (int) Math.floor(next() * xs.length))];
		}
	}

	public static String name(String gameId) {
		Rng rng = new Rng(gameId);
		double roll = rng.next();
		if (roll < 0.45) return rng.pick(ADJECTIVES) + rng.pick(NOUNS) + (int) Math.floor(rng.next() * 1000);
		String first = rng.pick(FIRST_NAMES);
		if (roll < 0.7) return first;
		if (roll < 0.85) return first + ((int) Math.floor(rng.next() * 90) + 10);
		return first + "_" + (char) ('a' + (int) Math.floor(rng.next() * 26));
	}
}
