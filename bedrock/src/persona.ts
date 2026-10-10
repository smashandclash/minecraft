// The opponent at your level wears a player's handle, never a label for what it is: the same
// shapes the lobby has, seeded by the game id so every client shows the same name for a game.
const ADJECTIVES = ['Brave', 'Swift', 'Sneaky', 'Mighty', 'Wobbly', 'Spicy', 'Turbo', 'Cosmic', 'Jolly', 'Rapid', 'Zesty', 'Lucky', 'Cheeky', 'Bouncy', 'Fuzzy', 'Nifty', 'Bold', 'Snappy', 'Plucky', 'Stormy', 'Frosty', 'Toasty'];
const NOUNS = ['Otter', 'Tiger', 'Pengu', 'Yeti', 'Falcon', 'Walrus', 'Comet', 'Ninja', 'Badger', 'Phoenix', 'Koala', 'Bison', 'Hawk', 'Panda', 'Wombat', 'Moose', 'Lynx', 'Heron', 'Gecko', 'Puffin', 'Quokka', 'Ibex'];
const FIRST_NAMES = ['arjun', 'priya', 'rohan', 'aisha', 'kabir', 'meera', 'ishan', 'tara', 'zoya', 'sana', 'kenji', 'yuki', 'mei', 'jisoo', 'hana', 'sora', 'leo', 'maya', 'nina', 'omar', 'theo', 'aria', 'finn', 'luca', 'noor', 'ivy'];

function rngFrom(text: string): () => number {
	let a = 2166136261;
	for (const ch of text) a = Math.imul(a ^ ch.charCodeAt(0), 16777619);
	return () => {
		a = (a + 0x6d2b79f5) | 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

export function personaName(gameId: string): string {
	const rng = rngFrom(gameId);
	const pick = (xs: string[]) => xs[Math.min(xs.length - 1, Math.floor(rng() * xs.length))]!;
	const roll = rng();
	if (roll < 0.45) return `${pick(ADJECTIVES)}${pick(NOUNS)}${Math.floor(rng() * 1000)}`;
	const first = pick(FIRST_NAMES);
	if (roll < 0.7) return first;
	if (roll < 0.85) return `${first}${String(Math.floor(rng() * 90) + 10)}`;
	return `${first}_${String.fromCharCode(97 + Math.floor(rng() * 26))}`;
}
