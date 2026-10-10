package in.smashandclash.minecraft.client;

import in.smashandclash.minecraft.SmashAndClashMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;

/** How to play: the website's coach script, each caption the line the coach speaks (its voice take plays with it). */
final class HowTo {
	private HowTo() {}

	/** {voice key, the line, the card's title}. */
	static final String[][] PAGES = {
		{"basics-goal", "Cards belong to whoever they face. Own more when the board fills!", "Own the board"},
		{"basics-turn", "Play one card each turn, then draw back up to five.", "One card a turn"},
		{"basics-capture", "Touch their card. Tie or beat their number, and it's yours!", "Match or beat to capture"},
		{"basics-tie", "A tie still wins. The player placing the card takes it.", "Ties go to the attacker"},
		{"basics-sides", "Every touching side fights at once. Diagonals never count.", "Four sides, no diagonals"},
		{"basics-nochain", "Captured cards flip, but they never attack. No chain reactions!", "No chain reactions"},
		{"basics-takeback", "Careful! Anything you capture can be captured right back.", "Nothing is safe"},
		{"mut-intro", "Mutators is the default game: Basics, plus three special tiles.", "Mutators"},
		{"mut-operator", "Land on an operator tile, hop like its chess piece, attack again!", "Operator tiles"},
		{"mut-pieces", "Knights leap over cards. The others slide until a card blocks them.", "Four pieces"},
		{"mut-power", "Match the tile's colour, and every number gets the boost!", "Power tiles"},
		{"mut-overrun", "Boxed in at the centre? Slam a fresh card right on top!", "Overrun"},
		{"fx-rule", "Effect cards bend the rules, but they must do something.", "Effect cards"},
		{"fx-boulder", "Boulder smashes a whole row and column. Frozen cards survive!", "Boulder"},
		{"fx-flip", "Flip spins the board. Every card switches sides, even frozen ones!", "Flip"},
		{"fx-freeze", "Freeze locks your card for good. Nothing can capture it.", "Freeze"},
		{"fx-recruit", "Recruit steals an enemy card and drops it down as yours!", "Recruit"},
		{"fx-swap", "Swap trades your whole hand for your opponent's. Bold move!", "Swap"},
		{"ctl-hop", "Choose a glowing tile to hop, or skip it. Your call!", "Hop or skip"},
	};

	private static SoundInstance playing;

	static void say(String key) {
		stop();
		playing = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(SmashAndClashMod.id("howto." + key)), 1f, 1f);
		Minecraft.getInstance().getSoundManager().play(playing);
	}

	static void stop() {
		if (playing != null) Minecraft.getInstance().getSoundManager().stop(playing);
		playing = null;
	}
}
