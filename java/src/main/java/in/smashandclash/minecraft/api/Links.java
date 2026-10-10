package in.smashandclash.minecraft.api;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Links: what one client shares, any other opens (the SDK's parseLink / joinLink / watchLink and board helpers). */
public final class Links {
	private Links() {}

	/** What parseLink read: kind is code | invite | watch | replay | challenge. */
	public record Link(String kind, String code, String game, String invite, String url, String agent, String token) {
		static Link code(String code) {
			return new Link("code", code, null, null, null, null, null);
		}

		static Link watch(String game) {
			return new Link("watch", null, game, null, null, null, null);
		}
	}

	private static final Pattern CODE_RE = Pattern.compile("^[A-Za-z2-9]{6}$");
	private static final Pattern GAME_ID_RE = Pattern.compile("^g_[\\w-]{8,40}$");
	private static final Pattern SCHEME = Pattern.compile("^[a-z][\\w+.-]*://", Pattern.CASE_INSENSITIVE);
	private static final Pattern PACKED = Pattern.compile("^j([0-9a-z])([A-Za-z0-9_-]+)$");
	private static final Pattern WATCHED = Pattern.compile("/(?:watch|api(?:/v1)?/games)/(g_[\\w-]{8,40})$");

	/** Read what a player pasted: a code, or a link from any client. Null when it is none of them. */
	public static Link parseLink(String input) {
		String text = input == null ? "" : input.trim();
		if (text.isEmpty()) return null;
		if (CODE_RE.matcher(text).matches()) return Link.code(text.toUpperCase());
		if (GAME_ID_RE.matcher(text).matches()) return Link.watch(text);
		URI u;
		try {
			u = URI.create(SCHEME.matcher(text).find() ? text : "https://" + text);
		} catch (IllegalArgumentException e) {
			return null;
		}
		Map<String, String> q = query(u.getRawQuery());
		String join = q.get("join");
		if (join != null && CODE_RE.matcher(join).matches()) return Link.code(join.toUpperCase());
		String game = q.get("game");
		String invite = q.get("invite");
		if (game != null && invite != null && GAME_ID_RE.matcher(game).matches()) return new Link("invite", null, game, invite, u.toString(), null, null);
		String vs = q.get("vs");
		String ch = q.get("ch");
		if (vs != null && ch != null) return new Link("challenge", null, null, null, null, vs.toLowerCase(), ch);
		String start = q.containsKey("startapp") ? q.get("startapp") : q.get("start");
		if (start != null && start.startsWith("w") && GAME_ID_RE.matcher(start.substring(1)).matches()) return Link.watch(start.substring(1));
		if (start != null) {
			Matcher packed = PACKED.matcher(start);
			if (packed.matches()) {
				int n = Integer.parseInt(packed.group(1), 36);
				String rest = packed.group(2);
				if (rest.length() > n) {
					String id = "g_" + rest.substring(0, n);
					String inv = "inv_" + rest.substring(n);
					if (GAME_ID_RE.matcher(id).matches())
						return new Link("invite", null, id, inv, SmashAndClash.DEFAULT_BASE_URL + "/?game=" + id + "&invite=" + inv, null, null);
				}
			}
		}
		String path = u.getPath() == null ? "" : u.getPath().replaceAll("/+$", "");
		Matcher watched = WATCHED.matcher(path);
		if (watched.find()) return Link.watch(watched.group(1));
		if (path.endsWith("/replay") && u.getRawFragment() != null && !u.getRawFragment().isEmpty())
			return new Link("replay", null, null, null, u.toString(), null, null);
		return null;
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> m = new HashMap<>();
		if (raw == null) return m;
		for (String part : raw.split("&")) {
			int eq = part.indexOf('=');
			String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
			String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
			m.putIfAbsent(k, v);
		}
		return m;
	}

	/** The link that opens a room by its code on any client (?join=). */
	public static String joinLink(String code, String base) {
		return base.replaceAll("/+$", "") + "/?join=" + Http.enc(code.trim().toUpperCase());
	}

	/** A game's live page. */
	public static String watchLink(String id, String base) {
		return base.replaceAll("/+$", "") + "/watch/" + Http.enc(id);
	}

	private static final String COLUMNS = "ABCDE";

	/** "C2" -> {r: 1, c: 2}; null when it is not a cell. */
	public static int[] cellToRC(String cell) {
		if (cell == null) return null;
		String s = cell.trim().toUpperCase();
		if (s.length() != 2) return null;
		int c = COLUMNS.indexOf(s.charAt(0));
		int r = s.charAt(1) - '1';
		return c < 0 || r < 0 || r > 2 ? null : new int[] {r, c};
	}

	/** {r, c} -> "C2". */
	public static String rcToCell(int r, int c) {
		return "" + COLUMNS.charAt(c) + (r + 1);
	}

	/** A character's sides on the board by compass {north, east, south, west}: seat A reads them as printed, seat B's card is turned round. */
	public static int[] boardSides(int top, int right, int bottom, int left, String owner) {
		return "A".equals(owner) ? new int[] {top, right, bottom, left} : new int[] {bottom, left, top, right};
	}
}
