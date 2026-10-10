package in.smashandclash.minecraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import in.smashandclash.minecraft.SmashAndClashMod;
import in.smashandclash.minecraft.net.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvent;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.List;

/**
 * The table's menu, in the Arena Pop look: a sugar panel on the blue scrim, the title in Luckiest
 * Guy, candy buttons tilted -8 degrees with a hard lip (sun = the one primary action, cherry = danger,
 * sugar = the rest). Keyboard: Tab / arrows move, Enter presses, Esc goes back.
 */
public class TableScreen extends Screen {
	static final FontDescription DISPLAY = new FontDescription.Resource(SmashAndClashMod.id("display"));
	static final FontDescription LABEL = new FontDescription.Resource(SmashAndClashMod.id("label"));
	static final int INK = 0xff0d1a4a, INK2 = 0xff22357a, INK3 = 0xff4a5e9c;

	/** A candy fill: face, highlight, lip, label colour. */
	record Fill(int face, int hi, int lip, int text) {}

	static final Fill SUN = new Fill(0xffffc21a, 0xffffe872, 0xffd98a00, INK);
	static final Fill SUGAR = new Fill(0xffe6f0ff, 0xffffffff, 0xffb4d0f0, INK);
	static final Fill CHERRY = new Fill(0xffe02030, 0xffff5a5a, 0xff9e0f18, 0xffffffff);
	static final Fill MINT = new Fill(0xff22d39a, 0xff8bf7cf, 0xff0e9a6c, INK);

	private final BlockPos pos;
	private final JsonObject s;
	/** main | join | howto */
	private String page = "main";
	private int howto;
	private EditBox code;
	private final List<String[]> body = new ArrayList<>();
	private boolean confirmResign;

	public TableScreen(BlockPos pos, String json) {
		super(Component.literal("Smash&Clash"));
		this.pos = pos;
		this.s = JsonParser.parseString(json).getAsJsonObject();
	}

	private String str(String k) {
		return s.has(k) && !s.get(k).isJsonNull() ? s.get(k).getAsString() : "";
	}

	private boolean bool(String k) {
		return s.has(k) && s.get(k).getAsBoolean();
	}

	private void send(String action, String arg) {
		ClientPlayNetworking.send(new Payloads.TableAction(pos, action, arg == null ? "" : arg));
	}

	static void click(String name, float volume) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(SmashAndClashMod.id(name)), 1f, volume));
	}

	/* --------------------------------- layout --------------------------------- */

	private int panelW() {
		return Math.min(300, width - 32);
	}

	@Override
	protected void init() {
		clearWidgets();
		body.clear();
		int w = panelW();
		int x = (width - w) / 2 + 16;
		int bw = w - 32;
		List<Runnable> rows = new ArrayList<>();
		String status = str("status");
		boolean seated = bool("seated");
		switch (page) {
			case "join" -> {
				body.add(new String[] {"Type a room code from any Smash&Clash client (the website, the apps, the CLI, Telegram), or paste a join link."});
				code = new EditBox(font, x, 0, bw, 20, Component.literal("Room code"));
				code.setMaxLength(200);
				code.setHint(Component.literal("ABC234"));
				rows.add(() -> {});
				addRenderableWidget(code);
				setInitialFocus(code);
				button(Component.literal("JOIN"), SUN, x, bw, () -> {
					String c = code.getValue().trim();
					if (!c.isEmpty()) {
						send("join", c);
						onClose();
					}
				});
				button(Component.literal("Back"), SUGAR, x, bw, () -> setPage("main"));
			}
			case "howto" -> {
				String[] p = HowTo.PAGES[howto];
				body.add(new String[] {p[1]});
				HowTo.say(p[0]);
				button(Component.literal(howto + 1 < HowTo.PAGES.length ? "NEXT" : "DONE"), SUN, x, bw, () -> {
					if (howto + 1 < HowTo.PAGES.length) {
						howto++;
						init();
					} else setPage("main");
				});
				if (howto > 0) button(Component.literal("Back"), SUGAR, x, bw, () -> {
					howto--;
					init();
				});
				button(Component.literal("Close"), SUGAR, x, bw, () -> setPage("main"));
			}
			default -> {
				if ("playing".equals(status) && seated) {
					body.add(new String[] {"Hold a card from your hand and right-click a glowing tile on the table. Left-click a card on the table to read it."});
					if (confirmResign) {
						body.add(new String[] {"Resign this game? The other player wins."});
						button(Component.literal("RESIGN"), CHERRY, x, bw, () -> {
							send("resign", "");
							onClose();
						});
						button(Component.literal("Keep playing"), SUGAR, x, bw, () -> {
							confirmResign = false;
							init();
						});
					} else {
						button(Component.literal("BACK TO THE GAME"), SUN, x, bw, this::onClose);
						button(Component.literal("How to play"), SUGAR, x, bw, () -> setPage("howto"));
						button(Component.literal("Resign"), CHERRY, x, bw, () -> {
							confirmResign = true;
							init();
						});
					}
				} else if ("waiting".equals(status) && seated) {
					body.add(new String[] {str("code").isEmpty() ? "Looking for an opponent: anyone in the online queue, on any client." : "Anyone at this table can take the other seat, or join from any Smash&Clash client with this code."});
					if (!str("code").isEmpty()) button(Component.literal("Copy the join link"), SUGAR, x, bw, () -> minecraft.keyboardHandler.setClipboard(str("joinLink")));
					button(Component.literal("Cancel"), CHERRY, x, bw, () -> {
						send("cancel", "");
						onClose();
					});
				} else if (bool("canTake")) {
					body.add(new String[] {seatName(0) + " opened this table. Take the other seat and play them here."});
					button(Component.literal("TAKE THE OTHER SEAT"), SUN, x, bw, () -> {
						send("take", "");
						onClose();
					});
					button(Component.literal("Close"), SUGAR, x, bw, this::onClose);
				} else if ("playing".equals(status) || "starting".equals(status) || "waiting".equals(status)) {
					body.add(new String[] {"This table is in use: " + seatName(0) + " vs " + seatName(1) + ". Watch the game from here."});
					button(Component.literal("Close"), SUGAR, x, bw, this::onClose);
				} else {
					String msg = str("message");
					if (!msg.isEmpty()) body.add(new String[] {msg});
					button(Component.literal("PLAY"), SUN, x, bw, () -> {
						send("house", "");
						onClose();
					});
					button(Component.literal("Quick match"), SUGAR, x, bw, () -> {
						send("quick", "");
						onClose();
					});
					int half = (bw - 6) / 2;
					button(Component.literal("Open a room"), SUGAR, x, half, () -> {
						send("room", "");
						onClose();
					});
					sideButton(Component.literal("Join a code"), SUGAR, x + half + 6, half, () -> setPage("join"));
					boolean classic = "classic".equals(str("ruleset"));
					button(Component.literal(classic ? "Rules: Classic" : "Rules: Mutators"), classic ? SUGAR : MINT, x, half, () -> {
						send("ruleset", classic ? "mutators" : "classic");
						s.addProperty("ruleset", classic ? "mutators" : "classic");
						init();
					});
					sideButton(Component.literal("How to play"), SUGAR, x + half + 6, half, () -> setPage("howto"));
				}
			}
		}
		layout();
	}

	private String seatName(int i) {
		var seats = s.getAsJsonArray("seats");
		return seats == null || seats.size() <= i ? "..." : seats.get(i).getAsJsonObject().get("name").getAsString();
	}

	private void setPage(String p) {
		page = p;
		if ("howto".equals(p)) howto = 0;
		init();
	}

	private final List<Candy> column = new ArrayList<>();

	private void button(Component label, Fill fill, int x, int w, Runnable onPress) {
		Candy c = new Candy(x, 0, w, 22, label, fill, onPress);
		column.add(c);
		addRenderableWidget(c);
	}

	/** A button beside the previous one (same row). */
	private void sideButton(Component label, Fill fill, int x, int w, Runnable onPress) {
		Candy c = new Candy(x, 0, w, 22, label, fill, onPress);
		c.sameRow = true;
		column.add(c);
		addRenderableWidget(c);
	}

	private int panelTop, panelH, bodyY;

	/** Stack the buttons under the title and body text, the whole panel centred. */
	private void layout() {
		int w = panelW();
		int textW = w - 32;
		int textH = 0;
		for (String[] b : body) textH += font.split(Component.literal(b[0]), textW).size() * 10 + 4;
		int rows = 0;
		for (Candy c : column) if (!c.sameRow) rows++;
		int buttonsH = rows * 30 + (code != null && "join".equals(page) ? 28 : 0);
		panelH = 16 + 28 + 14 + textH + 8 + buttonsH + 10;
		panelTop = Math.max(8, (height - panelH) / 2);
		bodyY = panelTop + 16 + 28 + 14;
		int y = bodyY + textH + 8;
		if (code != null && "join".equals(page)) {
			code.setY(y);
			y += 28;
		}
		Candy prev = null;
		for (Candy c : column) {
			if (c.sameRow && prev != null) c.setY(prev.getY());
			else {
				c.setY(y);
				y += 30;
			}
			prev = c;
		}
		column.clear();
	}

	/* --------------------------------- drawing --------------------------------- */

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// the scrim and the sugar panel with its lip
		g.fill(0, 0, width, height, 0x99060f2f);
		int w = panelW();
		int x = (width - w) / 2;
		round(g, x, panelTop + 4, w, panelH, 0xffb4d0f0);
		round(g, x, panelTop, w, panelH, 0xffffffff);
		g.fillGradient(x + 2, panelTop + panelH / 2, x + w - 2, panelTop + panelH - 2, 0x00eef6ff, 0xffeef6ff);
		// the title
		Component title = Component.literal("SMASH&CLASH").withStyle(Style.EMPTY.withFont(DISPLAY));
		g.pose().pushMatrix();
		float scale = 2f;
		g.pose().translate(width / 2f, panelTop + 14);
		g.pose().scale(scale, scale);
		g.centeredText(font, title, 0, 0, 0xff2173e8);
		g.pose().popMatrix();
		String sub = switch (page) {
			case "join" -> "Join a game";
			case "howto" -> "How to play  ·  " + (howto + 1) + " / " + HowTo.PAGES.length + "  ·  " + HowTo.PAGES[howto][2];
			default -> "Rating " + s.get("rating").getAsInt() + "  ·  " + s.get("played").getAsInt() + " played  ·  " + s.get("won").getAsInt() + " won";
		};
		g.centeredText(font, Component.literal(sub).withStyle(Style.EMPTY.withFont(LABEL)), width / 2, panelTop + 16 + 28, INK2);
		int y = bodyY;
		for (String[] b : body) {
			for (var line : font.split(Component.literal(b[0]), w - 32)) {
				g.text(font, line, x + 16, y, INK, false);
				y += 10;
			}
			y += 4;
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	/** A rounded rectangle (radius 3) from fills. */
	static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x + 3, y, x + w - 3, y + h, color);
		g.fill(x + 1, y + 1, x + 3, y + h - 1, color);
		g.fill(x + w - 3, y + 1, x + w - 1, y + h - 1, color);
		g.fill(x, y + 3, x + 1, y + h - 3, color);
		g.fill(x + w - 1, y + 3, x + w, y + h - 3, color);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (!"main".equals(page)) {
			setPage("main");
			return;
		}
		HowTo.stop();
		super.onClose();
	}

	/** A candy slab: the face over a hard lip, skewed -8 degrees, the label in Lilita One. */
	class Candy extends AbstractButton {
		final Fill fill;
		final Runnable action;
		boolean sameRow;

		Candy(int x, int y, int w, int h, Component label, Fill fill, Runnable action) {
			super(x, y, w, h, label);
			this.fill = fill;
			this.action = action;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			click("sfx.click", 0.7f);
			action.run();
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			int x = getX(), y = getY(), w = getWidth(), h = getHeight();
			boolean hot = isHoveredOrFocused();
			int press = isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed() ? 2 : 0;
			g.pose().pushMatrix();
			// skewX(-8deg) about the button's centre
			float cx = x + w / 2f, cy = y + h / 2f;
			g.pose().translate(cx, cy);
			g.pose().mul(new Matrix3x2f(1, 0, (float) Math.tan(Math.toRadians(8)), 1, 0, 0));
			g.pose().translate(-cx, -cy);
			round(g, x, y + 4, w, h, fill.lip);
			round(g, x, y + press, w, h, hot ? fill.hi : fill.face);
			g.fillGradient(x + 2, y + press + 1, x + w - 2, y + press + h / 2, hot ? 0x40ffffff : 0x55ffffff, 0x00ffffff);
			if (isFocused()) {
				// the one focus ring (keyboard and pad only)
				g.outline(x - 3, y - 3 + press, w + 6, h + 10 - press, 0xff0d1a4a);
				g.outline(x - 2, y - 2 + press, w + 4, h + 8 - press, 0xffffffff);
			}
			Component label = getMessage().copy().withStyle(Style.EMPTY.withFont(LABEL));
			g.centeredText(font, label, x + w / 2, y + press + (h - 8) / 2, fill.text);
			g.pose().popMatrix();
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
