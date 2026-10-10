// One Smash&Clash table in a Bedrock world. The script holds the seats of the players at this
// table (their player tokens) and plays them on the games API through @smashandclash/sdk; the other
// seat is a player at this same table, the house, or anyone on any client. Every move is drawn on
// the table with the website's animation beats, for everyone nearby, and each seated player's hand
// is in their inventory.

import { Dimension, Entity, ItemStack, MolangVariableMap, Player, system, Vector3, world } from '@minecraft/server';
import { ActionFormData, ModalFormData } from '@minecraft/server-ui';
import { Game, SmashAndClash, SmashAndClashError, parseLink, joinLink, cellToRC, rcToCell, boardSides } from '@smashandclash/sdk';
import type { GameState, MoveDetail, PlayerView, SeatMove, SeatState, SeatSync } from '@smashandclash/sdk';
import { bedrockFetch, bedrockSleep } from './sdk';
import { IN_CUBIC, IN_OUT_CUBIC, LINEAR, OUT_BACK, OUT_CUBIC, Tween } from './tween';
import { personaName } from './persona';

declare const CARDS_JSON: string;
declare const MARKERS_JSON: string;

export const NS = 'smashandclash';
export const sc = new SmashAndClash({ fetch: bedrockFetch as never, sleep: bedrockSleep, client: 'minecraft-bedrock/1.0.0' });

interface DeckCard {
	id: number;
	name: string;
	kind: 'character' | 'effect';
	top: number;
	right: number;
	bottom: number;
	left: number;
	color?: string;
	effect?: string;
	does?: string;
	art: boolean;
	animation: { pivot: { x: number; y: number }; artScale: number } | null;
	voice: boolean;
}
export const DECK = new Map<number, DeckCard>((JSON.parse(CARDS_JSON) as DeckCard[]).map((c) => [c.id, c]));
const CARD_IDS = [...DECK.keys()];
const MARKERS: string[] = JSON.parse(MARKERS_JSON);
/** Texture index of a card face (0 = the back). */
const faceIndex = (id: number) => CARD_IDS.indexOf(id) + 1;
/** Texture index of a figure: rest, windup, strike for each card with art. */
const SPRITES = ['none', ...CARD_IDS.filter((id) => DECK.get(id)!.art).flatMap((id) => [`a${id}`, `w${id}`, `s${id}`])];
const spriteIndex = (id: number, frame: 'a' | 'w' | 's') => Math.max(0, SPRITES.indexOf(`${frame}${id}`));

/* -------------------------------- geometry -------------------------------- */

const S = 0.55;
export const WIDTH = 512 / (165 / S);
export const DEPTH = 416 / (165 / S);
const COL = 92 / (165 / S);
const ROW = 122 / (165 / S);
const THICK = 0.07;
const TOP_H = 0.08;
/** One website unit (a card's width) in blocks. */
const U = 0.48 * S;
const COMIC = [
	['SMASH!', 'BAM!', 'WHACK!', 'POW!', 'CRUNCH!'],
	['DOUBLE SMASH!', 'TWO DOWN!', 'DOUBLE TROUBLE!', 'ONE-TWO!'],
	['TRIPLE SMASH!', 'THREE IN A ROW!', 'HAT-TRICK!', 'TRIPLE THREAT!'],
	['OVERKILL!!', 'TOTAL WIPEOUT!!', 'CLEAN SWEEP!!', 'ALL FOUR!!'],
];

type V = { x: number; y: number; z: number };
const v = (x: number, y: number, z: number): V => ({ x, y, z });
const lerp = (a: V, b: V, t: number): V => v(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);

/** A tile's centre in the board frame: x = seat A's right, y = up, z = toward seat A. */
export const local = (r: number, c: number): V => v((c - 2) * COL, 0, (1 - r) * ROW);

/* ------------------------------ saved state ------------------------------ */

interface SavedSeat {
	player: string;
	name: string;
	token: string;
	rating: number;
}
export interface Saved {
	key: string;
	dim: string;
	x: number;
	y: number;
	z: number;
	/** from seat A toward seat B */
	fx: number;
	fz: number;
	mode?: string;
	ruleset?: string;
	gameId?: string;
	strength?: number;
	code?: string;
	seats: [SavedSeat | null, SavedSeat | null];
}

export interface Rec {
	rating: number;
	played: number;
	won: number;
	ruleset: 'mutators' | 'classic';
}
export function rec(p: Player): Rec {
	const raw = world.getDynamicProperty(`${NS}:p:${p.id}`);
	const base: Rec = { rating: 1200, played: 0, won: 0, ruleset: 'mutators' };
	try {
		return typeof raw === 'string' ? { ...base, ...JSON.parse(raw) } : base;
	} catch {
		return base;
	}
}
function saveRec(id: string, r: Rec): void {
	world.setDynamicProperty(`${NS}:p:${id}`, JSON.stringify(r));
}
const elo = (rating: number, opp: number, score: number) => Math.round(rating + 32 * (score - 1 / (1 + 10 ** ((opp - rating) / 400))));

/* -------------------------------- the seats -------------------------------- */

class LocalSeat {
	recruitFrom: string | null = null;
	armed: string | null = null;
	armedUntil = 0;
	view: PlayerView | undefined;
	constructor(
		readonly player: string,
		readonly name: string,
		readonly game: Game,
		readonly rating: number,
	) {
		this.view = game.view;
	}
}

/** A card on the table: its entity and its pose in the board frame (the website's Pose). */
class CardView {
	entity: Entity | undefined;
	pos: V = v(0, 0, 0);
	lift = 0;
	scale = 1;
	spin = 0;
	constructor(
		readonly cardId: number,
		public owner: string,
		public frozen = false,
	) {}
}

/* --------------------------------- the table --------------------------------- */

export class Table {
	readonly key: string;
	readonly dim: Dimension;
	readonly center: V;
	readonly f: V;
	readonly r: V;
	readonly yaw: number;
	private tween = new Tween();
	private local: [LocalSeat | null, LocalSeat | null] = [null, null];
	/** idle | starting | waiting | playing | over */
	status = 'idle';
	private message = '';
	private state: GameState | undefined;
	private lastSync: SeatSync | undefined;
	private queue: SeatMove[] = [];
	private appliedSince = 0;
	private animating = false;
	private shown: SeatState | undefined;
	private cells: (CardView | null)[][] = [[null, null, null, null, null], [null, null, null, null, null], [null, null, null, null, null]];
	private markers: Entity[] = [];
	private markerKey = '';
	private boardEntity: Entity | undefined;
	private sign: Entity | undefined;
	private deckPile: Entity[] = [];
	private deckLabel: Entity | undefined;
	private deckShown = -1;
	private opponent: Entity | undefined;
	private opponentSeat: string | null = null;
	private built = false;
	private gen = 0;
	private disposed = false;
	private finished = false;
	private started = false;
	private firstBlood = false;
	private attackerCardId = -1;
	private chainLinks = 0;
	private lastTile: [number, number] | null = null;
	private quietUntil = 0;
	private crowd: Crowd;
	private hinge = 0;

	constructor(public saved: Saved) {
		this.key = saved.key;
		this.dim = world.getDimension(saved.dim);
		this.f = v(saved.fx, 0, saved.fz);
		this.r = v(-saved.fz, 0, saved.fx); // the forward direction turned clockwise (seat A's right)
		this.center = v(saved.x + 0.5, saved.y + 1 + TOP_H + THICK, saved.z + 0.5);
		// the entity yaw that puts the models' +x along r (a Bedrock yaw of 0 faces +z)
		this.yaw = (Math.atan2(-this.f.x, this.f.z) * 180) / Math.PI + 180;
		this.crowd = new Crowd(this);
	}

	/* ---------- frame ---------- */

	world(p: V): V {
		const back = v(-this.f.x, 0, -this.f.z);
		return v(this.center.x + this.r.x * p.x + back.x * p.z, this.center.y + p.y, this.center.z + this.r.z * p.x + back.z * p.z);
	}

	toLocal(w: V): V {
		const d = v(w.x - this.center.x, w.y - this.center.y, w.z - this.center.z);
		return v(d.x * this.r.x + d.z * this.r.z, d.y, -(d.x * this.f.x + d.z * this.f.z));
	}

	private spawn(id: string, at: V, yaw = this.yaw): Entity {
		const e = this.dim.spawnEntity(`${NS}:${id}`, at);
		e.addTag('snc_table');
		e.addTag(`snc@${this.key}`);
		e.setRotation({ x: 0, y: yaw });
		return e;
	}

	private label(text: string, at: V): Entity {
		const e = this.spawn('label', at);
		e.nameTag = text;
		return e;
	}

	clearAll(): number {
		let n = 0;
		for (const e of this.dim.getEntities({ tags: [`snc@${this.key}`] })) {
			try {
				e.remove();
				n++;
			} catch {}
		}
		return n;
	}

	/* ---------- lifecycle ---------- */

	tick(): void {
		if (this.disposed) return;
		if (!this.built) this.build();
		this.tween.tick();
		if (!this.animating && this.queue.length) this.playNext();
		const t = system.currentTick;
		if (t % 4 === 0) this.glows();
		if (t % 20 === 0) this.hints();
		if (t % 40 === 0) this.crowd.tick(this.status === 'playing');
		if (this.status === 'playing') this.crowd.look(this.turnTarget());
		if (t % 10 === 0) this.opponentUpkeep();
		if (this.selftest && t % 10 === 0) this.selftestTurn();
	}

	private build(): void {
		this.built = true;
		const fresh = this.clearAll() === 0 && !this.saved.gameId;
		this.boardEntity = this.spawn('table', v(this.center.x, this.center.y - THICK - TOP_H, this.center.z));
		this.setHinge(fresh ? 180 : 0);
		for (let r = 0; r < 3; r++)
			for (let c = 0; c < 5; c++) {
				const p = this.world(local(r, c));
				const tile = this.spawn('tile', v(p.x, this.center.y - 0.06, p.z));
				tile.addTag(`snc_tile_${r}_${c}`);
			}
		this.sign = this.label('', v(this.center.x, this.center.y + 0.95, this.center.z));
		if (fresh) this.unfold();
		this.renderDeck(0);
		if (this.saved.gameId) this.resume();
		else this.refreshSign();
	}

	private setHinge(deg: number): void {
		this.hinge = deg;
		try {
			this.boardEntity?.setProperty(`${NS}:hinge`, deg);
		} catch {}
	}

	/** A new table: the board lands folded, then opens on its hinge. */
	private unfold(): void {
		this.sound('sfx.whoosh');
		this.tween.run(350, 750, IN_OUT_CUBIC, (e) => this.setHinge(180 * (1 - e)), () => this.sound('sfx.card_place'));
		this.tween.run(1100, 240, LINEAR, (e) => this.setHinge(-6 * Math.exp(-4.5 * e) * Math.sin(12 * e)), () => this.setHinge(0));
	}

	unload(): void {
		this.disposed = true;
		this.gen++;
	}

	/** The table was broken: leave or resign the game, take the cards back, clear the board. */
	destroy(): void {
		this.unload();
		for (const s of this.local) {
			if (!s) continue;
			const g = s.game;
			(g.waiting ? g.leave() : g.over ? Promise.resolve() : g.resign()).catch(() => {});
			const p = this.player(s);
			if (p) clearHand(p);
			if (p) p.stopMusic();
		}
		this.crowd.dismiss();
		this.clearAll();
	}

	/* ---------- sounds and words ---------- */

	sound(name: string, volume = 1.5, at: V = this.center): void {
		try {
			this.dim.playSound(`${NS}.${name}`, at, { volume });
		} catch {}
	}

	private particles(id: string, rc: [number, number], count = 6): void {
		const p = this.world(local(rc[0], rc[1]));
		for (let i = 0; i < count; i++) {
			try {
				this.dim.spawnParticle(id, v(p.x + (Math.random() - 0.5) * 0.25, this.center.y + 0.08 + Math.random() * 0.1, p.z + (Math.random() - 0.5) * 0.25));
			} catch {}
		}
	}

	/** fx.comicPop: a word that pops over the table and drifts up. */
	private comicPop(text: string, at: V, ms = 1000): void {
		const w = this.world(at);
		const e = this.label(text, w);
		this.tween.run(0, ms, LINEAR, (t) => {
			try {
				e.teleport(v(w.x, w.y + 0.25 * t, w.z));
			} catch {}
		}, () => {
			try {
				e.remove();
			} catch {}
		});
	}

	/* ---------- menu ---------- */

	async openMenu(p: Player): Promise<void> {
		const seated = this.seatOf(p) >= 0;
		const r = rec(p);
		const form = new ActionFormData().title('§lSMASH&CLASH');
		const choices: Array<() => void> = [];
		const add = (label: string, fn: () => void) => {
			form.button(label);
			choices.push(fn);
		};
		const head = `Rating ${r.rating}  ·  ${r.played} played  ·  ${r.won} won`;
		if (this.status === 'playing' && seated) {
			form.body(`${head}\n\nHold a card from your hand and use it on a glowing tile on the table.`);
			add('§lBACK TO THE GAME', () => {});
			add('How to play', () => void howTo(p));
			add('§cResign', () => void this.confirmResign(p));
		} else if (this.status === 'waiting' && seated) {
			form.body(this.saved.code ? `Room §e§l${this.saved.code}§r\n\nAnyone at this table can take the other seat, or join from any Smash&Clash client with this code:\n${joinLink(this.saved.code)}` : 'Looking for an opponent: anyone in the online queue, on any client.');
			add('§cCancel', () => this.cancel(p));
		} else if (this.status === 'waiting' && this.saved.mode === 'room' && this.saved.code) {
			form.body(`${this.seatName(0)} opened this table. Take the other seat and play them here.`);
			add('§lTAKE THE OTHER SEAT', () => this.takeSeat(p));
		} else if (this.busy()) {
			form.body(`This table is in use: ${this.seatName(0)} vs ${this.seatName(1)}. Watch the game from here.`);
			add('Close', () => {});
		} else {
			form.body(`${head}${this.message ? '\n\n' + this.message : ''}`);
			add('§lPLAY', () => this.start(p, 'house'));
			add('Quick match', () => this.start(p, 'quick'));
			add('Open a room', () => this.start(p, 'room'));
			add('Join a code', () => void this.joinForm(p));
			add(`Rules: ${r.ruleset === 'classic' ? 'Classic' : 'Mutators'}`, () => {
				r.ruleset = r.ruleset === 'classic' ? 'mutators' : 'classic';
				saveRec(p.id, r);
				void this.openMenu(p);
			});
			add('How to play', () => void howTo(p));
		}
		const res = await form.show(p as never);
		if (res.canceled || res.selection === undefined) return;
		choices[res.selection]?.();
	}

	private async joinForm(p: Player): Promise<void> {
		const res = await new ModalFormData().title('Join a game').textField('A room code from any Smash&Clash client, or a join link', 'ABC234').show(p as never);
		if (res.canceled || !res.formValues) return;
		const code = String(res.formValues[0] ?? '').trim();
		if (code) this.start(p, 'join', code);
	}

	private async confirmResign(p: Player): Promise<void> {
		const res = await new ActionFormData().title('Resign?').body('The other player wins this game.').button('§cResign').button('Keep playing').show(p as never);
		if (!res.canceled && res.selection === 0) this.resign(p);
	}

	private busy(): boolean {
		return this.status === 'starting' || this.status === 'waiting' || this.status === 'playing';
	}

	/* ---------- starting, joining, leaving ---------- */

	/** Testing: what a seated player's seat sees. */
	testView(p: Player): PlayerView | undefined {
		return this.local[this.seatOf(p)]?.view;
	}

	/** A self-test: this table plays a whole house game by itself (no player), every animation running. */
	selfTest(): void {
		this.clearGame();
		this.saved.mode = 'house';
		this.status = 'starting';
		this.selftest = true;
		const gen = ++this.gen;
		sc.games.startHouse({ name: 'Selftest', as: 'person', strength: 1200 }).then((g) => {
			if (gen !== this.gen) return;
			const i = g.state.seat === 'B' ? 1 : 0;
			this.local[i] = new LocalSeat('selftest', 'Selftest', g, 1200);
			this.state = g.state;
			this.saved.gameId = g.id;
			this.saved.strength = 1200;
			this.status = 'playing';
			console.log(`[Smash&Clash] selftest: game ${g.id}, seat ${g.state.seat}`);
			this.pump(false);
		}, (e) => console.warn('[Smash&Clash] selftest start: ' + String(e)));
	}
	selftest = false;
	private selftestBusy = false;

	/** The self-test seat plays its turn: an effect card when it has one (to see them), else the first placement. */
	private selftestTurn(): void {
		const s = this.local.find((x) => x?.player === 'selftest');
		if (!s?.view?.yourTurn || this.animating || this.queue.length || this.selftestBusy) return;
		const moves = s.view.moves ?? [];
		const m = moves.find((x) => x.type === 'effect') ?? moves.find((x) => x.type === 'hop') ?? moves.find((x) => x.type === 'place') ?? moves[0];
		if (!m) return;
		this.selftestBusy = true;
		console.log(`[Smash&Clash] selftest plays ${m.name}`);
		s.view.yourTurn = false;
		s.game.play(m.name).then(() => {
			s.view = s.game.view;
			this.selftestBusy = false;
		}, (e) => {
			console.warn('[Smash&Clash] selftest play: ' + String(e));
			this.selftestBusy = false;
		});
	}

	private start(p: Player, mode: string, arg = ''): void {
		if (this.busy()) return this.say(p, 'This table is in use.');
		this.clearGame();
		const r = rec(p);
		const name = p.name;
		const strength = Math.max(800, Math.min(1600, r.rating));
		this.status = 'starting';
		this.message = mode === 'house' ? 'Finding an opponent at your level...' : mode === 'quick' ? 'Looking for an opponent...' : mode === 'room' ? 'Opening a room...' : 'Joining...';
		this.saved.mode = mode;
		this.saved.ruleset = r.ruleset;
		this.refreshSign();
		const gen = ++this.gen;
		const o = { name, as: 'person' as const, rating: r.rating };
		const work = async (): Promise<Game> => {
			if (mode === 'house') return sc.games.startHouse({ ...o, ruleset: r.ruleset, strength });
			if (mode === 'quick') return sc.games.quickMatch({ ...o, ruleset: r.ruleset, opponent: 'any' });
			if (mode === 'room') return sc.games.createDuel({ ...o, ruleset: r.ruleset });
			const opened = await sc.games.open(arg, o);
			if (opened.kind !== 'game') throw new SmashAndClashError(409, 'full', 'That game is already full: watch it on smashandclash.in.');
			return opened.game;
		};
		work().then(
			(g) => {
				if (gen !== this.gen) return;
				this.saved.strength = strength;
				this.sit(g, p, r.rating);
				if (mode === 'house') this.sound('sfx.match_found');
				this.pump(false);
			},
			(e) => {
				this.status = 'idle';
				this.message = errorText(e);
				this.saved = { ...this.saved, gameId: undefined, code: undefined, mode: undefined, seats: [null, null] };
				persist(this);
				this.say(p, this.message);
				this.refreshSign();
			},
		);
	}

	private sit(g: Game, p: Player, rating: number): void {
		const i = g.state.seat === 'B' ? 1 : 0;
		this.local[i] = new LocalSeat(p.id, p.name, g, rating);
		this.state = g.state;
		this.saved.gameId = g.id;
		this.saved.code = g.code;
		this.saved.seats[i] = { player: p.id, name: p.name, token: g.playerToken, rating };
		this.status = g.waiting ? 'waiting' : 'playing';
		this.message = '';
		persist(this);
		p.playMusic(`${NS}.music.${g.waiting ? 'matchmaking' : 'battle'}`, { loop: true, fade: 1, volume: 0.6 });
		if (g.waiting && g.code) p.sendMessage(`§6Room §e§l${g.code}§r§6: anyone at this table can take the other seat, or join with the code on any Smash&Clash client. ${g.joinLink}`);
		this.refreshSign();
	}

	private takeSeat(p: Player): void {
		const code = this.saved.code;
		if (this.status !== 'waiting' || !code || this.seatOf(p) >= 0) return;
		const r = rec(p);
		sc.games.joinDuel(code, { name: p.name, as: 'person', rating: r.rating }).then(
			(g) => this.sit(g, p, r.rating),
			(e) => this.say(p, errorText(e)),
		);
	}

	private cancel(p: Player): void {
		const i = this.seatOf(p);
		if (i < 0 || this.status !== 'waiting') return;
		this.local[i]!.game.leave().then(
			() => this.endGame('Called off.'),
			(e) => this.say(p, e instanceof SmashAndClashError && e.status === 409 ? 'Someone just joined: the game is on!' : errorText(e)),
		);
	}

	private resign(p: Player): void {
		const i = this.seatOf(p);
		if (i < 0 || this.status !== 'playing') return;
		this.local[i]!.game.resign().catch((e) => this.say(p, errorText(e)));
	}

	private resume(): void {
		this.status = 'starting';
		this.message = 'Picking the game up again...';
		this.refreshSign();
		const sv = this.saved;
		const gen = ++this.gen;
		Promise.all(sv.seats.map((s) => (s ? sc.games.resume(sv.gameId!, s.token) : Promise.resolve(null)))).then(
			(games) => {
				if (gen !== this.gen) return;
				games.forEach((g, i) => {
					const s = sv.seats[i];
					if (!g || !s) return;
					const ls = new LocalSeat(s.player, s.name, g, s.rating);
					this.local[i] = ls;
					this.state = g.state;
				});
				const g = this.primary();
				if (!g || g.over) return this.endGame('');
				this.status = g.waiting ? 'waiting' : 'playing';
				this.message = '';
				this.pump(true);
			},
			() => this.endGame(''),
		);
	}

	private endGame(why: string): void {
		for (const s of this.local) {
			const p = this.player(s);
			if (!p) continue;
			clearHand(p);
			if (this.status !== 'over') p.stopMusic();
		}
		if (this.status !== 'over') this.status = 'idle';
		this.message = why;
		this.gen++;
		this.saved = { ...this.saved, gameId: undefined, code: undefined, mode: undefined, seats: [null, null] };
		persist(this);
		this.refreshSign();
	}

	private clearGame(): void {
		this.tween.clear();
		this.queue = [];
		this.animating = false;
		for (const row of this.cells) for (let c = 0; c < 5; c++) {
			const cv = row[c];
			if (cv?.entity) safeRemove(cv.entity);
			row[c] = null;
		}
		this.clearMarkers();
		this.crowd.dismiss();
		if (this.opponent) safeRemove(this.opponent);
		this.opponent = undefined;
		this.opponentSeat = null;
		this.local = [null, null];
		this.state = undefined;
		this.lastSync = undefined;
		this.shown = undefined;
		this.appliedSince = 0;
		this.finished = this.started = this.firstBlood = false;
		this.setHinge(0);
	}

	/* ---------- following the game ---------- */

	private primary(): Game | undefined {
		return this.local[0]?.game ?? this.local[1]?.game;
	}

	/** Wait for the other player, then long-poll the seat sync for every move (with its events), refreshing each seat's view. */
	private pump(resuming: boolean): void {
		const gen = ++this.gen;
		const loop = async () => {
			let since = 0;
			let first = true;
			while (!this.disposed && gen === this.gen) {
				try {
					const g = this.primary();
					if (!g) return;
					if (g.waiting) {
						const s = await g.waitForTurn(20);
						if (gen !== this.gen) return;
						if (s.status !== 'waiting') {
							this.status = 'playing';
							this.sound('sfx.match_found');
							this.sound('announcer.match_found');
							for (const ls of this.local) this.player(ls)?.playMusic(`${NS}.music.battle`, { loop: true, fade: 1, volume: 0.6 });
							this.refreshSign();
						}
						continue;
					}
					const s = await g.sync({ since, wait: first ? 0 : 20 });
					if (gen !== this.gen) return;
					const news = first || s.moveCount !== since || s.status !== g.state.status;
					const skip = first && resuming;
					since = s.moveCount;
					first = false;
					if (news) {
						for (const ls of this.local) {
							if (!ls) continue;
							this.state = await ls.game.refresh();
							ls.view = ls.game.view;
						}
					}
					this.applySync(s, skip);
					this.afterViews();
					if (s.status === 'finished' || s.status === 'abandoned') return;
				} catch (e) {
					console.warn('[Smash&Clash] sync: ' + String(e));
					await bedrockSleep(3000);
				}
			}
		};
		void loop();
	}

	private applySync(s: SeatSync, skipAnimation: boolean): void {
		this.lastSync = s;
		if (skipAnimation || (!this.shown && s.moves.length === 0)) {
			this.shown = s.state;
			this.appliedSince = s.moveCount;
			this.reconcile();
		}
		if (!this.shown && s.moves.length) {
			this.shown = { ...s.state, board: [0, 1, 2].map(() => [null, null, null, null, null]), powerTiles: [] } as SeatState;
			this.reconcile();
		}
		if (!this.started) {
			this.started = true;
			if (!skipAnimation) {
				this.sound('sfx.match_start');
				this.sound('announcer.match_start');
				this.deal(s.firstPlayer ?? 'A');
			}
		}
		for (const m of s.moves) {
			if (m.index < this.appliedSince) continue;
			this.queue.push(m);
			this.appliedSince = m.index + 1;
		}
	}

	private afterViews(): void {
		if (this.queue.length || this.animating) return;
		for (const s of this.local) {
			const p = this.player(s);
			if (p && s?.view) syncHand(p, s.view, this.key);
		}
		this.refreshSign();
		const st = this.lastSync?.status;
		if ((st === 'finished' || st === 'abandoned') && !this.finished) this.finish();
	}

	/* ---------- playing a card ---------- */

	/** A player used something on tile (r, c). */
	onTile(p: Player, r: number, c: number, held: ItemStack | undefined): void {
		this.lastTile = [r, c];
		const i = this.seatOf(p);
		if (i < 0 || this.status !== 'playing') {
			if (!this.busy() || p.isSneaking) void this.openMenu(p);
			else this.say(p, "You're watching. Use the table to see who's playing.");
			return;
		}
		if (p.isSneaking) return void this.openMenu(p);
		const s = this.local[i]!;
		const view = s.view;
		if (this.animating || this.queue.length) return;
		if (!view?.yourTurn) return this.deny(p, `${this.seatName(1 - i)}'s turn.`);
		const cell = rcToCell({ r, c });
		const moves: MoveDetail[] = view.moves ?? [];
		if (view.pendingHop) {
			for (const m of moves) {
				if (m.type !== 'hop') continue;
				if (cell === m.dest || (m.dest == null && cell === view.pendingHop.from)) return this.play(p, s, m.name);
			}
			return this.deny(p, 'Hop to a glowing tile, or use your card to stay.');
		}
		const cardId = cardOf(held);
		if (cardId < 0) return this.deny(p, 'Hold a card from your hand, then use a glowing tile.');
		const hc = view.hand.find((h) => h.cardId === cardId);
		if (!hc) return;
		if (hc.kind !== 'effect') {
			const m = moves.find((m) => m.cardId === cardId && m.cell === cell && (m.type === 'place' || m.type === 'overrun'));
			return m ? this.play(p, s, m.name) : this.deny(p, `That tile isn't open for ${hc.card}.`);
		}
		const effect = DECK.get(cardId)?.effect ?? '';
		const mine = moves.filter((m) => m.cardId === cardId);
		if (!mine.length) return this.deny(p, `${hc.card} can't do anything right now.`);
		if (effect === 'FLIP' || effect === 'SWAP') {
			if (s.armed === effect && system.currentTick < s.armedUntil) {
				s.armed = null;
				return this.play(p, s, mine[0]!.name);
			}
			s.armed = effect;
			s.armedUntil = system.currentTick + 100;
			p.playSound(`${NS}.sfx.card_pick`);
			p.onScreenDisplay.setActionBar(`${effect === 'FLIP' ? 'Flip turns the board: every card changes sides.' : 'Swap trades your hand for theirs.'} Use it again to play it.`);
			this.quietUntil = system.currentTick + 60;
			return;
		}
		if (effect === 'RECRUIT') {
			if (!s.recruitFrom) {
				if (mine.some((m) => m.target === cell)) {
					s.recruitFrom = cell;
					p.playSound(`${NS}.sfx.card_pick`);
					p.onScreenDisplay.setActionBar('Now use the empty tile to bring it to.');
					this.quietUntil = system.currentTick + 60;
				} else this.deny(p, 'Pick an enemy card to recruit.');
				return;
			}
			const m = mine.find((m) => m.target === s.recruitFrom && m.dest === cell);
			if (m) {
				s.recruitFrom = null;
				return this.play(p, s, m.name);
			}
			if (cell === s.recruitFrom) s.recruitFrom = null;
			else this.deny(p, 'Pick an empty tile for it.');
			return;
		}
		const m = mine.find((m) => m.target === cell);
		return m ? this.play(p, s, m.name) : this.deny(p, effect === 'BOULDER' ? 'Pick a card to smash.' : 'Pick one of your cards to freeze.');
	}

	private play(p: Player, s: LocalSeat, move: string): void {
		if (s.view) s.view.yourTurn = false;
		if (this.lastTile) this.flash(p, this.lastTile, [1, 0.91, 0.45]);
		p.playSound(`${NS}.sfx.card_pick`);
		s.game.play(move).then(
			(st) => {
				this.state = st;
				s.view = s.game.view;
			},
			(e) => {
				this.deny(p, errorText(e));
				s.game.refresh().then(() => (s.view = s.game.view), () => {});
			},
		);
	}

	inspect(p: Player, r: number, c: number): void {
		const cv = this.cells[r]![c];
		const d = cv && DECK.get(cv.cardId);
		if (!cv || !d) return;
		const i = this.seatOf(p);
		const whose = i >= 0 ? ((cv.owner === 'A') === (i === 0) ? 'yours' : 'theirs') : `seat ${cv.owner}`;
		p.onScreenDisplay.setActionBar(`${d.name}${d.kind === 'effect' ? '' : `  ▲${d.top} ▶${d.right} ▼${d.bottom} ◀${d.left}`}  ·  ${whose}${cv.frozen ? '  ·  frozen' : ''}`);
		this.quietUntil = system.currentTick + 60;
	}

	private deny(p: Player, why: string): void {
		this.quietUntil = system.currentTick + 50;
		if (this.lastTile && this.seatOf(p) >= 0) this.flash(p, this.lastTile, [1, 0.23, 0.23]);
		p.playSound(`${NS}.sfx.deny`, { volume: 0.7 });
		p.onScreenDisplay.setActionBar(`§6${why}`);
	}

	private say(p: Player, text: string): void {
		this.quietUntil = system.currentTick + 50;
		p.onScreenDisplay.setActionBar(`§6${text}`);
	}

	/* ---------- the animation (the website's beats) ---------- */

	private playNext(): void {
		const m = this.queue.shift();
		if (!m || !this.shown) return;
		if (this.selftest) console.log(`[Smash&Clash] selftest animates move ${m.index} ${m.seat} ${m.name}: ${(m.events ?? []).map((e) => e.type).join(', ')}`);
		this.animating = true;
		let t = 0;
		this.attackerCardId = -1;
		this.chainLinks = 0;
		const work = this.shown.board.map((row) => row.map((x) => (x ? { ...x } : null)));
		const evs = (m.events ?? []) as Array<Record<string, unknown> & { type: string }>;
		evs.forEach((ev, k) => {
			t += this.event(ev, capturedAfter(evs, k), work, t, m);
		});
		const after = m.state;
		this.tween.at(t + 50, () => {
			this.shown = after;
			this.reconcile();
			this.animating = false;
			if (!this.queue.length) this.afterViews();
		});
	}

	private event(ev: Record<string, unknown> & { type: string }, next: Record<string, unknown> | null, work: Work, t: number, move: SeatMove): number {
		switch (ev.type) {
			case 'cardPlaced': {
				const rc = rcOf(ev.cell);
				const player = String(ev.player);
				const cardId = Number(ev.cardId);
				work[rc[0]]![rc[1]] = { cardId, owner: player as 'A' | 'B', frozen: false };
				const plan = this.clashFor(rc, next, work);
				this.swingIf(t, player);
				return this.placed(t, player, cardId, rc, plan);
			}
			case 'cardsCaptured': {
				const cs = (ev.cells as unknown[]).map(rcOf);
				const owner = String(ev.newOwner);
				for (const rc of cs) if (work[rc[0]]![rc[1]]) work[rc[0]]![rc[1]]!.owner = owner as 'A' | 'B';
				return this.captures(t, cs, owner);
			}
			case 'effectPlayed':
				this.swingIf(t, String(ev.player));
				this.tween.at(t + 300, () => this.crowd.murmur());
				return this.showcase(t, Number(ev.cardId));
			case 'boulderRemoved': {
				const cs = (ev.cells as unknown[]).map(rcOf);
				const target = ev.target ? rcOf(ev.target) : cs[0] ?? ([1, 2] as [number, number]);
				for (const rc of cs) work[rc[0]]![rc[1]] = null;
				return this.boulder(t, cs, target) + this.finishEffect(t + 1100);
			}
			case 'boardFlipped': {
				const f: Work = [0, 1, 2].map(() => [null, null, null, null, null]);
				for (let r = 0; r < 3; r++) for (let c = 0; c < 5; c++) {
					const x = work[r]![c] ?? null;
					if (x) x.owner = x.owner === 'A' ? 'B' : 'A';
					f[2 - r]![4 - c] = x;
				}
				for (let r = 0; r < 3; r++) work[r] = f[r]!;
				return this.flip(t) + this.finishEffect(t + 1350);
			}
			case 'cardFrozen': {
				const rc = rcOf(ev.cell);
				if (work[rc[0]]![rc[1]]) work[rc[0]]![rc[1]]!.frozen = true;
				return this.freeze(t, rc) + this.finishEffect(t + 600);
			}
			case 'cardRecruited': {
				const from = rcOf(ev.from), to = rcOf(ev.to);
				const owner = String(ev.newOwner);
				const x = work[from[0]]![from[1]] ?? null;
				work[from[0]]![from[1]] = null;
				if (x) x.owner = owner as 'A' | 'B';
				work[to[0]]![to[1]] = x;
				const d = this.recruit(t, from, to, owner);
				const plan = this.clashFor(to, next, work);
				const strike = plan ? this.clashStrike(t + d, to, plan) : 0;
				return d + strike + this.finishEffect(t + d + strike);
			}
			case 'handsSwapped':
				this.tween.at(t, () => {
					this.sound('sfx.swap');
					this.sound('announcer.fx_swap');
					this.comicPop('§e§lSWAP!', v(0, 0.45, 0));
				});
				for (let k = 0; k < 5; k++) {
					this.handToHand(t + 100 + k * 90, 'A', 'B');
					this.handToHand(t + 100 + k * 90, 'B', 'A');
				}
				return 1100 + this.finishEffect(t + 1100);
			case 'cardHopped': {
				const from = rcOf(ev.from), to = rcOf(ev.to);
				const x = work[from[0]]![from[1]] ?? null;
				work[from[0]]![from[1]] = null;
				work[to[0]]![to[1]] = x;
				const knight = !!this.shown?.operatorTiles?.some((o) => o.cell.r === from[0] && o.cell.c === from[1] && o.piece === 'KNIGHT');
				const d = this.hop(t, from, to, knight);
				const plan = this.clashFor(to, next, work);
				return d + (plan ? this.clashStrike(t + d, to, plan) : 0);
			}
			case 'powerBoostApplied': {
				const rc = rcOf(ev.cell);
				this.tween.at(t, () => {
					this.sound('sfx.power_boost');
					this.particles('minecraft:villager_happy', rc, 8);
				});
				return 250;
			}
			case 'cardOverrun': {
				const rc = rcOf(ev.cell);
				const id = Number(ev.newCardId);
				work[rc[0]]![rc[1]] = { cardId: id, owner: move.seat as 'A' | 'B', frozen: false };
				const fly = this.overrunOut(t, rc);
				const plan = this.clashFor(rc, next, work);
				return Math.max(fly, 120 + this.placed(t + 120, move.seat, id, rc, plan));
			}
			case 'cardsDrawn': {
				const n = Number(ev.count ?? 1);
				for (let i = 0; i < n; i++) this.flyToSeat(t + i * 150, String(ev.player));
				return n * 150 + 200;
			}
			case 'turnStarted': {
				const seat = String(ev.player);
				this.tween.at(t, () => this.player(this.local[seat === 'A' ? 0 : 1])?.playSound(`${NS}.sfx.turn_chime`, { volume: 0.8 }));
				return 0;
			}
			default:
				return 0;
		}
	}

	/* ---- poses ---- */

	private applyCard(cv: CardView): void {
		const e = cv.entity;
		if (!e) return;
		try {
			const w = this.world(v(cv.pos.x, cv.pos.y, cv.pos.z));
			e.teleport(v(w.x, this.center.y + 0.005, w.z), { rotation: { x: 0, y: this.yaw + (cv.owner === 'B' ? 180 : 0) + (cv.spin * 180) / Math.PI } });
			e.setProperty(`${NS}:lift`, cv.lift);
			e.setProperty(`${NS}:scale`, cv.scale);
		} catch {}
	}

	private spawnCard(cv: CardView): void {
		const w = this.world(cv.pos);
		cv.entity = this.spawn('card', v(w.x, this.center.y + 0.005, w.z), this.yaw + (cv.owner === 'B' ? 180 : 0));
		cv.entity.setProperty(`${NS}:face`, faceIndex(cv.cardId));
		this.setRim(cv);
		cv.entity.setProperty(`${NS}:frozen`, cv.frozen);
		this.applyCard(cv);
	}

	private setRim(cv: CardView, on = true): void {
		try {
			cv.entity?.setProperty(`${NS}:rim`, on ? (cv.owner === 'A' ? 1 : 2) : 0);
		} catch {}
	}

	/** animateCardPlaced: an arc down from the owner's side (330 ms), the slam, then the clash or a springy settle. */
	private placed(t: number, player: string, cardId: number, rc: [number, number], plan: Clash | null): number {
		const cv = new CardView(cardId, player);
		this.tween.at(t, () => {
			const old = this.cells[rc[0]]![rc[1]];
			if (old && old !== cv && old.entity) safeRemove(old.entity);
			this.cells[rc[0]]![rc[1]] = cv;
			const end = local(rc[0], rc[1]);
			const start = this.handLocal(player);
			cv.pos = start;
			cv.lift = 0.5;
			this.spawnCard(cv);
			this.setRim(cv, false);
			this.tween.run(50, 330, IN_OUT_CUBIC, (e) => {
				cv.pos = lerp(start, end, e);
				cv.lift = 0.5 * (1 - e) + 0.95 * U * Math.sin(Math.PI * e);
				this.applyCard(cv);
			}, () => {
				cv.pos = end;
				cv.lift = 0;
				this.applyCard(cv);
				this.setRim(cv);
				this.sound('sfx.card_place');
				this.particles('minecraft:basic_smoke_particle', rc, 3);
				this.absorbCrystal(rc);
				if (!plan && DECK.get(cardId)?.kind === 'character') this.sound(`voice.${cardId}.place`);
			});
		});
		if (plan) return 380 + this.clashStrike(t + 380, rc, plan);
		this.tween.run(t + 380, 300, LINEAR, (e) => {
			const w = Math.exp(-4.2 * e) * Math.sin(11 * e);
			cv.scale = Math.max(0.001, 1 + 0.08 * w);
			cv.lift = Math.max(0, 0.06 * U * w);
			this.applyCard(cv);
		});
		return 680;
	}

	private clashFor(rc: [number, number], next: Record<string, unknown> | null, work: Work): Clash | null {
		const me = work[rc[0]]![rc[1]];
		const mine = me && DECK.get(me.cardId);
		if (!me || !mine || mine.kind === 'effect') return null;
		const captured = new Set<string>();
		if (next) for (const c of (next.cells as unknown[]).map(rcOf)) captured.add(`${c[0]},${c[1]}`);
		const sides = boardSides({ top: mine.top, right: mine.right, bottom: mine.bottom, left: mine.left }, me.owner);
		const boost = this.shown?.powerTiles?.find((p) => p.cell.r === rc[0] && p.cell.c === rc[1] && p.color === mine.color)?.boost ?? 0;
		const dirs: Array<[number, number, 'north' | 'east' | 'south' | 'west', 'north' | 'east' | 'south' | 'west']> = [
			[1, 0, 'north', 'south'],
			[0, 1, 'east', 'west'],
			[-1, 0, 'south', 'north'],
			[0, -1, 'west', 'east'],
		];
		const pairs: Pair[] = [];
		for (const [dr, dc, mySide, theirSide] of dirs) {
			const r = rc[0] + dr, c = rc[1] + dc;
			if (r < 0 || r > 2 || c < 0 || c > 4) continue;
			const o = work[r]![c];
			const theirs = o && DECK.get(o.cardId);
			if (!o || !theirs || o.owner === me.owner || o.frozen || theirs.kind === 'effect') continue;
			const ts = boardSides({ top: theirs.top, right: theirs.right, bottom: theirs.bottom, left: theirs.left }, o.owner);
			pairs.push({ cell: [r, c], atk: sides[mySide], boost, def: ts[theirSide], win: captured.has(`${r},${c}`) });
		}
		return pairs.length ? { cardId: me.cardId, pairs } : null;
	}

	/** playClashStrike: the lunge (one target) or rise-and-stomp (several), the impact and its reasons ("5 ≥ 3"), the recoils, the settle. */
	private clashStrike(t: number, rc: [number, number], plan: Clash): number {
		const ref = () => this.cells[rc[0]]![rc[1]];
		const multi = plan.pairs.length > 1;
		const anyWin = plan.pairs.some((p) => p.win);
		const a = local(rc[0], rc[1]);
		const focus = plan.pairs.reduce((acc, p) => {
			const l = local(p.cell[0], p.cell[1]);
			return v(acc.x + l.x, 0, acc.z + l.z);
		}, a);
		const fx = focus.x / (plan.pairs.length + 1), fz = focus.z / (plan.pairs.length + 1);
		const d = multi ? v(0, 0, 0) : v((fx - a.x) * 0.55, 0, (fz - a.z) * 0.55);
		const rise = (multi ? 0.5 : 0.22) * U;
		const grow = multi ? 0.14 : 0.08;
		const t0 = t + 120;
		this.figure(t, rc, plan.cardId, !anyWin ? 'blocked' : multi ? 'stomp' : 'strike', multi ? 800 : 640);
		const lunge = multi ? 200 : 150;
		this.tween.run(t0, lunge, OUT_CUBIC, (e) => {
			const cv = ref();
			if (!cv) return;
			cv.pos = v(a.x + d.x * e, 0, a.z + d.z * e);
			cv.lift = rise * e;
			cv.scale = 1 + grow * e;
			this.applyCard(cv);
		});
		let impact = t0 + lunge;
		if (multi) {
			this.tween.run(impact, 110, IN_OUT_CUBIC, (e) => {
				const cv = ref();
				if (!cv) return;
				cv.lift = rise * (1 - e);
				cv.scale = 1 + grow * (1 - e * 0.5);
				this.applyCard(cv);
			});
			impact += 110;
		}
		this.tween.at(impact, () => {
			if (anyWin) {
				this.sound('sfx.clash_hit');
				this.sound(this.chainLinks >= 2 ? 'sfx.capture_crit' : 'sfx.capture_hit');
			} else {
				this.crowd.groan();
				this.sound('sfx.block_thud');
				this.sound('sfx.block_shield');
				this.sound(DECK.get(plan.cardId)?.voice ? `voice.${plan.cardId}.blocked` : 'announcer.blocked');
			}
			plan.pairs.forEach((p, i) => {
				const l = local(p.cell[0], p.cell[1]);
				const atk = p.boost ? `${p.atk}+${p.boost}` : `${p.atk}`;
				this.comicPop(`${p.win ? '§e' : '§c'}§l${atk} ${p.win ? '≥' : '<'} ${p.def}`, v((a.x + l.x) / 2, 0.5 + i * 0.12, (a.z + l.z) / 2));
				this.particles(p.win ? 'minecraft:critical_hit_emitter' : 'minecraft:basic_smoke_particle', p.cell, 2);
			});
		});
		for (const p of plan.pairs) {
			const home = local(p.cell[0], p.cell[1]);
			const dv = () => this.cells[p.cell[0]]![p.cell[1]];
			if (p.win) {
				const k = 0.12 * (multi ? 1.4 : 1);
				const rv = v((home.x - a.x) * k, 0, (home.z - a.z) * k);
				this.tween.run(impact, 260, LINEAR, (e) => {
					const c = dv();
					if (!c) return;
					const w = Math.sin(Math.PI * e);
					c.pos = v(home.x + rv.x * w, 0, home.z + rv.z * w);
					this.applyCard(c);
				}, () => {
					const c = dv();
					if (c) {
						c.pos = home;
						this.applyCard(c);
					}
				});
			} else {
				this.tween.run(impact, 300, LINEAR, (e) => {
					const c = dv();
					if (!c) return;
					const w = Math.sin(Math.PI * e);
					c.scale = 1 + 0.09 * w;
					c.lift = 0.06 * U * w;
					this.applyCard(c);
				});
			}
		}
		const settle = impact + 70;
		this.tween.run(settle, 200, IN_OUT_CUBIC, (e) => {
			const cv = ref();
			if (!cv) return;
			cv.pos = v(a.x + d.x * (1 - e), 0, a.z + d.z * (1 - e));
			cv.lift = multi ? 0 : rise * (1 - e);
			cv.scale = 1 + (multi ? grow * 0.5 : grow) * (1 - e);
			this.applyCard(cv);
		});
		let end = settle + 200;
		if (!anyWin) {
			this.tween.run(end, 520, LINEAR, (e) => {
				const cv = ref();
				if (!cv) return;
				const env = e < 0.15 ? e / 0.15 : Math.exp(-2.6 * (e - 0.15));
				cv.pos = v(a.x + Math.sin(e * Math.PI * 22) * 0.035 * U * env, 0, a.z + Math.cos(e * Math.PI * 17) * 0.025 * U * env);
				cv.spin = Math.sin(e * Math.PI * 22) * 0.07 * env;
				this.applyCard(cv);
			}, () => {
				const cv = ref();
				if (cv) {
					cv.pos = a;
					cv.spin = 0;
					this.applyCard(cv);
				}
			});
			end += 520;
		}
		this.attackerCardId = plan.cardId;
		this.chainLinks++;
		return end - t;
	}

	/** The character's own attack: its art, then its windup and strike drawings, standing up out of its card (the kit's timing). */
	private figure(t: number, rc: [number, number], cardId: number, kind: string, ms: number): void {
		const d = DECK.get(cardId);
		if (!d?.art) return;
		const timing = kind === 'stomp' ? [[0, 0.17, 'a'], [0.17, 0.5, 'w'], [0.5, 0.72, 's'], [0.72, 1, 'a']] : kind === 'blocked' ? [[0, 0.15, 'a'], [0.15, 0.46, 'w'], [0.46, 1, 'a']] : [[0, 0.15, 'a'], [0.15, 0.34, 'w'], [0.34, 0.62, 's'], [0.62, 1, 'a']];
		let fig: Entity | undefined;
		const p = this.world(local(rc[0], rc[1]));
		this.tween.at(t, () => {
			fig = this.spawn('sprite', v(p.x, this.center.y + 0.02, p.z));
			fig.setProperty(`${NS}:sprite`, spriteIndex(cardId, 'a'));
			fig.setProperty(`${NS}:size`, 0.05);
		});
		this.tween.run(t + 50, ms, LINEAR, (e) => {
			if (!fig) return;
			const up = Math.min(1, Math.min(e / 0.15, (1 - e) / 0.15));
			const frame = (timing.find(([a, b]) => e >= (a as number) && e < (b as number))?.[2] ?? 'a') as 'a' | 'w' | 's';
			try {
				fig.setProperty(`${NS}:sprite`, spriteIndex(cardId, d.animation ? frame : 'a'));
				fig.setProperty(`${NS}:size`, Math.max(0.05, 0.62 * S * OUT_BACK(up)));
			} catch {}
		}, () => fig && safeRemove(fig));
	}

	/** animateCaptures: sparks, the comic word, a hit-stop, then each card turns over (550 ms; 880 for four) with a hop and a ring-down. */
	private captures(t: number, cs: Array<[number, number]>, owner: string): number {
		const n = cs.length;
		if (!n) return 0;
		const c0 = cs.reduce((acc, rc) => {
			const l = local(rc[0], rc[1]);
			return v(acc.x + l.x / n, 0, acc.z + l.z / n);
		}, v(0, 0, 0));
		const clashId = this.attackerCardId;
		const chain = this.chainLinks;
		this.tween.at(t, () => {
			for (const rc of cs) this.particles('minecraft:critical_hit_emitter', rc, 1);
			const words = COMIC[Math.min(4, n) - 1]!;
			this.comicPop(`§e§l${words[Math.floor(Math.random() * words.length)]}`, v(c0.x, 1.45 * U + 0.25, c0.z), 1100);
		});
		const start = t + (n >= 2 ? 80 : 0);
		const flipDur = n >= 4 ? 880 : 550;
		const stagger = n >= 4 ? 130 : 90;
		cs.forEach((rc, k) => {
			const delay = start + k * stagger;
			const ref = () => this.cells[rc[0]]![rc[1]];
			let base = 0;
			this.tween.at(delay, () => {
				const cv = ref();
				if (cv) base = (cv.owner === 'B' ? Math.PI : 0) + cv.spin;
			});
			this.tween.run(delay, flipDur, IN_OUT_CUBIC, (e) => {
				const cv = ref();
				if (!cv) return;
				cv.spin = base + Math.PI * e - (cv.owner === 'B' ? Math.PI : 0);
				cv.lift = 0.7 * U * Math.sin(Math.PI * e);
				cv.scale = 1 + 0.08 * Math.sin(Math.PI * e);
				this.applyCard(cv);
			}, () => {
				const cv = ref();
				if (!cv) return;
				cv.owner = owner;
				cv.spin = 0;
				cv.lift = 0;
				this.applyCard(cv);
			});
			this.tween.at(delay + Math.round(flipDur * 0.44), () => {
				const cv = ref();
				if (cv) {
					cv.owner = owner;
					this.setRim(cv);
				}
				this.particles('minecraft:critical_hit_emitter', rc, 1);
				if (k !== 0) return;
				this.crowd.cheer(n);
				this.sound('sfx.capture');
				if (n >= 2) this.sound('sfx.combo');
				const voice = clashId > 0 && DECK.get(clashId)?.voice;
				if (!this.firstBlood) {
					this.firstBlood = true;
					this.sound('announcer.first_blood');
				} else if (voice && chain >= 2) {
					this.sound(`voice.${clashId}.chain.${Math.min(4, chain)}`);
					this.sound(`announcer.${chain === 2 ? 'chain2' : chain === 3 ? 'chain3' : 'chain_max'}`);
				} else if (voice) this.sound(`voice.${clashId}.capture.${Math.min(4, n)}`);
				else this.sound(`announcer.smash${Math.min(4, n)}`);
				const last = this.cells[cs[n - 1]![0]]![cs[n - 1]![1]];
				if (last && DECK.get(last.cardId)?.voice) this.tween.at(300, () => this.sound(`voice.${last.cardId}.hurt`));
			});
			this.tween.run(delay + flipDur, 240, LINEAR, (e) => {
				const cv = ref();
				if (!cv) return;
				cv.scale = Math.max(0.001, 1 + 0.07 * Math.exp(-4.5 * e) * Math.sin(12 * e));
				this.applyCard(cv);
			});
		});
		return start - t + (n - 1) * stagger + flipDur + 240;
	}

	private showcaseCard: CardView | null = null;

	/** animateEffectPlayed: the effect card flies up big over the table (300 ms), pulses (160 ms), holds (220 ms). */
	private showcase(t: number, cardId: number): number {
		const d = DECK.get(cardId);
		this.tween.at(t, () => {
			if (this.showcaseCard?.entity) safeRemove(this.showcaseCard.entity);
			const cv = new CardView(cardId, 'A');
			cv.pos = v(0, 0, 0);
			this.spawnCard(cv);
			this.setRim(cv, false);
			this.showcaseCard = cv;
			this.sound('sfx.whoosh');
			if (d?.effect) this.sound(`announcer.fx_${d.effect.toLowerCase()}`);
		});
		this.tween.run(t + 50, 300, OUT_CUBIC, (e) => {
			const cv = this.showcaseCard;
			if (!cv) return;
			cv.lift = 0.3 + 0.5 * e;
			cv.scale = 0.2 + 1.6 * e;
			cv.spin += 0.2;
			this.applyCard(cv);
		});
		this.tween.run(t + 350, 160, OUT_CUBIC, (e) => {
			const cv = this.showcaseCard;
			if (!cv) return;
			cv.scale = 1.8 * (1 + 0.08 * Math.sin(Math.PI * e));
			this.applyCard(cv);
		});
		return 680;
	}

	/** finishEffect: the showcased card dives into the table (300 ms), then the shuffle. */
	private finishEffect(t: number): number {
		this.tween.run(t, 300, IN_CUBIC, (e) => {
			const cv = this.showcaseCard;
			if (!cv) return;
			cv.lift = 0.8 * (1 - e);
			cv.scale = 1.8 * (1 - 0.9 * e);
			this.applyCard(cv);
		}, () => {
			if (this.showcaseCard?.entity) safeRemove(this.showcaseCard.entity);
			this.showcaseCard = null;
			this.sound('sfx.shuffle');
		});
		return 300;
	}

	/** animateBoulder: the rock falls on its target (360 ms); the shockwave sweeps the cards off outward. */
	private boulder(t: number, cs: Array<[number, number]>, target: [number, number]): number {
		const tc = local(target[0], target[1]);
		const tw = this.world(tc);
		let rock: Entity | undefined;
		this.tween.at(t, () => {
			try {
				rock = this.dim.spawnEntity('minecraft:falling_block' as never, v(tw.x, this.center.y + 1.5, tw.z));
			} catch {}
			this.sound('announcer.fx_boulder');
		});
		const impact = t + 410;
		this.tween.at(impact, () => {
			if (rock) safeRemove(rock);
			this.sound('sfx.boulder');
			this.particles('minecraft:huge_explosion_emitter', target, 1);
			this.comicPop('§6§lBOULDER!', v(tc.x, 0.45, tc.z));
		});
		let last = impact + 590;
		for (const rc of cs) {
			const dist = Math.abs(rc[0] - target[0]) + Math.abs(rc[1] - target[1]);
			const delay = impact + 40 + dist * 70;
			const home = local(rc[0], rc[1]);
			let away = v(home.x - tc.x, 0, home.z - tc.z);
			const len = Math.hypot(away.x, away.z) || 1;
			away = v((away.x / len) * 1.4 * S, 0, (away.z / len) * 1.4 * S || 0.01);
			const ref = () => this.cells[rc[0]]![rc[1]];
			this.tween.run(delay, 470, OUT_CUBIC, (e) => {
				const cv = ref();
				if (!cv) return;
				cv.pos = v(home.x + away.x * e, 0, home.z + away.z * e);
				cv.lift = 0.5 * Math.sin(Math.PI * e * 0.8);
				cv.spin = 2.5 * e;
				cv.scale = Math.max(0.01, 1 - e);
				this.applyCard(cv);
			}, () => {
				const cv = ref();
				if (cv?.entity) safeRemove(cv.entity);
				this.cells[rc[0]]![rc[1]] = null;
			});
			last = Math.max(last, delay + 470);
		}
		return last - t;
	}

	/** animateBoardFlip: the whole board turns 180 degrees (1250 ms); every card ends up facing the other player. */
	private flip(t: number): number {
		this.tween.at(t, () => {
			this.sound('sfx.flip');
			this.sound('announcer.fx_flip');
		});
		const all: Array<{ cv: CardView; home: V }> = [];
		this.tween.at(t, () => {
			for (const row of this.cells) for (const cv of row) if (cv) all.push({ cv, home: { ...cv.pos } });
		});
		this.tween.run(t + 50, 1250, IN_OUT_CUBIC, (e) => {
			const a = Math.PI * e;
			for (const { cv, home } of all) {
				cv.pos = v(home.x * Math.cos(a) + home.z * Math.sin(a), 0, -home.x * Math.sin(a) + home.z * Math.cos(a));
				cv.spin = a;
				cv.lift = 0.25 * Math.sin(Math.PI * e);
				this.applyCard(cv);
			}
			try {
				this.boardEntity?.setRotation({ x: 0, y: this.yaw + (a * 180) / Math.PI });
			} catch {}
		}, () => {
			try {
				this.boardEntity?.setRotation({ x: 0, y: this.yaw });
			} catch {}
			const f: (CardView | null)[][] = [0, 1, 2].map(() => [null, null, null, null, null]);
			for (let r = 0; r < 3; r++) for (let c = 0; c < 5; c++) {
				const cv = this.cells[r]![c];
				if (!cv) continue;
				cv.owner = cv.owner === 'A' ? 'B' : 'A';
				cv.spin = 0;
				cv.lift = 0;
				cv.pos = local(2 - r, 4 - c);
				this.applyCard(cv);
				this.setRim(cv);
				f[2 - r]![4 - c] = cv;
			}
			this.cells = f;
			this.markerKey = '';
		});
		return 1300;
	}

	/** animateFreeze: the freeze burst, and the ice forming over the card. */
	private freeze(t: number, rc: [number, number]): number {
		this.tween.at(t, () => {
			this.sound('sfx.freeze');
			this.sound('announcer.fx_freeze');
			this.particles('minecraft:snowflake_particle', rc, 12);
			this.comicPop('§b§lFROZEN!', v(local(rc[0], rc[1]).x, 0.4, local(rc[0], rc[1]).z));
			const cv = this.cells[rc[0]]![rc[1]];
			if (cv) {
				cv.frozen = true;
				try {
					cv.entity?.setProperty(`${NS}:frozen`, true);
				} catch {}
			}
		});
		return 600;
	}

	/** animateRecruit: the lift (220 ms), the arc to its new tile (480 ms), the landing. */
	private recruit(t: number, from: [number, number], to: [number, number], owner: string): number {
		const a = local(from[0], from[1]), b = local(to[0], to[1]);
		this.tween.at(t, () => {
			this.sound('sfx.recruit');
			this.sound('announcer.fx_recruit');
			this.comicPop(`${owner === 'A' ? '§9' : '§6'}§lRECRUIT!`, v(a.x, 0.4, a.z), 900);
		});
		const ref = () => this.cells[from[0]]![from[1]];
		this.tween.run(t + 50, 220, OUT_CUBIC, (e) => {
			const cv = ref();
			if (!cv) return;
			cv.lift = 0.3 * U * e;
			this.applyCard(cv);
		});
		this.tween.run(t + 270, 480, IN_OUT_CUBIC, (e) => {
			const cv = ref();
			if (!cv) return;
			cv.pos = lerp(a, b, e);
			cv.lift = 0.3 * U * (1 - e) + 0.7 * U * Math.sin(Math.PI * e);
			this.applyCard(cv);
		}, () => {
			const cv = ref();
			if (!cv) return;
			this.cells[from[0]]![from[1]] = null;
			this.cells[to[0]]![to[1]] = cv;
			cv.owner = owner;
			cv.pos = b;
			cv.lift = 0;
			this.applyCard(cv);
			this.setRim(cv);
			this.sound('sfx.card_place');
		});
		return 800;
	}

	/** animateCardHopped: the chess hop (460 ms for a knight, 380 otherwise). */
	private hop(t: number, from: [number, number], to: [number, number], knight: boolean): number {
		const dur = knight ? 460 : 380;
		const a = local(from[0], from[1]), b = local(to[0], to[1]);
		this.tween.at(t, () => {
			this.sound('sfx.chess_hop');
			this.reformCrystal(from);
		});
		const ref = () => this.cells[from[0]]![from[1]];
		this.tween.run(t + 50, dur, IN_OUT_CUBIC, (e) => {
			const cv = ref();
			if (!cv) return;
			cv.pos = lerp(a, b, e);
			cv.lift = (knight ? 1.0 : 0.55) * U * Math.sin(Math.PI * e);
			this.applyCard(cv);
		}, () => {
			const cv = ref();
			if (!cv) return;
			this.cells[from[0]]![from[1]] = null;
			this.cells[to[0]]![to[1]] = cv;
			cv.pos = b;
			cv.lift = 0;
			this.applyCard(cv);
			this.absorbCrystal(to);
			this.sound('sfx.card_place');
		});
		return dur + 50;
	}

	private overrunOut(t: number, rc: [number, number]): number {
		const old = this.cells[rc[0]]![rc[1]];
		if (!old) return 0;
		const home = { ...old.pos };
		const out = v(WIDTH / 2 + 0.4, 0, 0);
		this.tween.run(t, 420, IN_CUBIC, (e) => {
			old.pos = lerp(home, out, e);
			old.lift = 1.1 * U * Math.sin(Math.PI * e);
			old.scale = 1 - 0.5 * e;
			this.applyCard(old);
		}, () => old.entity && safeRemove(old.entity));
		return 420;
	}

	private absorbCrystal(rc: [number, number]): void {
		if (!this.shown?.operatorTiles?.some((o) => o.cell.r === rc[0] && o.cell.c === rc[1])) return;
		this.sound('sfx.crystal_shatter');
		this.particles('minecraft:dragon_breath_trail', rc, 4);
	}

	private reformCrystal(rc: [number, number]): void {
		if (!this.shown?.operatorTiles?.some((o) => o.cell.r === rc[0] && o.cell.c === rc[1])) return;
		this.sound('sfx.crystal_form');
	}

	/* ---------- the deck, the deal, the hands ---------- */

	private deckLocal(): V {
		return v(-(WIDTH / 2 + 0.2), -THICK, 0);
	}

	private renderDeck(count: number): void {
		const layers = count <= 0 ? 0 : Math.min(10, 1 + Math.floor(count / 5));
		if (layers !== this.deckShown) {
			this.deckShown = layers;
			for (const e of this.deckPile) safeRemove(e);
			this.deckPile = [];
			const w = this.world(this.deckLocal());
			for (let i = 0; i < layers; i++) {
				const e = this.spawn('card', v(w.x, w.y + i * 0.007, w.z), this.yaw + (i % 3 - 1) * 2);
				e.setProperty(`${NS}:face`, 0);
				this.deckPile.push(e);
			}
		}
		if (!this.deckLabel) {
			const w = this.world(this.deckLocal());
			this.deckLabel = this.label('', v(w.x, w.y + 0.3, w.z));
		}
		try {
			this.deckLabel.nameTag = count > 0 ? `§lDECK ${count}` : '';
		} catch {}
	}

	/** Where a seat's hand is: the player sitting there, the figure across the board, or that edge of the board. */
	private handLocal(seat: string): V {
		const p = this.player(this.local[seat === 'A' ? 0 : 1]);
		if (p && dist2(p.location, this.center) < 36) return this.toLocal(v(p.location.x, p.location.y + 1.1, p.location.z));
		if (this.opponent && seat === this.opponentSeat) {
			try {
				const l = this.opponent.location;
				return this.toLocal(v(l.x, l.y + 1.1, l.z));
			} catch {}
		}
		return v(0, 0.35, (seat === 'A' ? 1 : -1) * (DEPTH / 2 + 0.45));
	}

	/** One card off the top of the deck into a hand: a 380 ms arc. */
	private flyToSeat(t: number, seat: string): void {
		this.tween.at(t, () => {
			const from = v(this.deckLocal().x, 0, 0);
			const to = this.handLocal(seat);
			const cv = new CardView(0, 'A');
			cv.pos = from;
			this.spawnCard(cv);
			cv.entity?.setProperty(`${NS}:face`, 0);
			this.setRim(cv, false);
			this.sound('sfx.draw', 0.9);
			this.tween.run(50, 380, IN_OUT_CUBIC, (e) => {
				cv.pos = lerp(from, v(to.x, 0, to.z), e);
				cv.lift = to.y * e + 0.9 * U * Math.sin(Math.PI * e);
				cv.scale = 1 - 0.25 * e;
				this.applyCard(cv);
			}, () => cv.entity && safeRemove(cv.entity));
		});
	}

	private handToHand(t: number, from: string, to: string): void {
		this.tween.at(t, () => {
			const a = this.handLocal(from), b = this.handLocal(to);
			const cv = new CardView(0, 'A');
			cv.pos = v(a.x, 0, a.z);
			this.spawnCard(cv);
			cv.entity?.setProperty(`${NS}:face`, 0);
			this.setRim(cv, false);
			this.tween.run(50, 520, IN_OUT_CUBIC, (e) => {
				cv.pos = v(a.x + (b.x - a.x) * e + (from === 'A' ? 1 : -1) * 0.25 * Math.sin(Math.PI * e), 0, a.z + (b.z - a.z) * e);
				cv.lift = a.y + (b.y - a.y) * e + 0.5 * Math.sin(Math.PI * e);
				cv.spin = Math.PI * 2 * e;
				this.applyCard(cv);
			}, () => cv.entity && safeRemove(cv.entity));
		});
	}

	/** The deal: five cards each, one at a time, the first player first. */
	private deal(first: string): void {
		this.animating = true;
		const other = first === 'A' ? 'B' : 'A';
		this.sound('sfx.shuffle');
		for (let k = 0; k < 10; k++) this.flyToSeat(300 + k * 130, k % 2 === 0 ? first : other);
		this.tween.at(300 + 10 * 130 + 600, () => {
			this.animating = false;
			if (!this.queue.length) this.afterViews();
		});
	}

	/* ---------- the table, exactly ---------- */

	private reconcile(): void {
		const st = this.shown;
		if (!st?.board) return;
		for (let r = 0; r < 3; r++)
			for (let c = 0; c < 5; c++) {
				const want = st.board[r]![c];
				let cv = this.cells[r]![c];
				if (!want) {
					if (cv?.entity) safeRemove(cv.entity);
					this.cells[r]![c] = null;
					continue;
				}
				if (!cv || cv.cardId !== want.cardId || !cv.entity || !cv.entity.isValid) {
					if (cv?.entity) safeRemove(cv.entity);
					cv = new CardView(want.cardId, want.owner, want.frozen);
					cv.pos = local(r, c);
					this.spawnCard(cv);
					this.cells[r]![c] = cv;
				}
				cv.owner = want.owner;
				cv.frozen = want.frozen;
				cv.pos = local(r, c);
				cv.lift = 0;
				cv.scale = 1;
				cv.spin = 0;
				this.setRim(cv);
				try {
					cv.entity?.setProperty(`${NS}:frozen`, want.frozen);
				} catch {}
				this.applyCard(cv);
			}
		this.renderMarkers();
		this.renderDeck(st.deck ?? 0);
		this.refreshSign();
	}

	private renderMarkers(): void {
		const st = this.shown!;
		const key = JSON.stringify([st.operatorTiles ?? [], st.powerTiles ?? []]);
		if (key === this.markerKey) return;
		this.markerKey = key;
		this.clearMarkers();
		const put = (r: number, c: number, name: string) => {
			const idx = MARKERS.indexOf(name);
			if (idx < 0) return;
			const w = this.world(local(r, c));
			const e = this.spawn('marker', v(w.x, this.center.y + 0.001, w.z));
			e.setProperty(`${NS}:marker`, idx);
			this.markers.push(e);
		};
		for (const o of st.operatorTiles ?? []) put(o.cell.r, o.cell.c, `op_${o.piece.toLowerCase()}`);
		for (const p of st.powerTiles ?? []) put(p.cell.r, p.cell.c, `power_${p.color}_${p.boost}`);
	}

	private clearMarkers(): void {
		for (const e of this.markers) safeRemove(e);
		this.markers = [];
		this.markerKey = '';
	}

	/** The sign over the table: the players, the score, whose turn. */
	private refreshSign(): void {
		if (!this.sign) return;
		const sc_ = this.score();
		let text: string;
		if (this.status === 'idle' || (!this.saved.gameId && this.status !== 'starting')) {
			text = `§e§lSmash&Clash§r\n§fUse the table to play${this.message ? '\n§7' + this.message : ''}`;
		} else {
			let line: string;
			if (this.status === 'starting') line = this.message;
			else if (this.status === 'waiting') line = this.saved.code ? `Room ${this.saved.code}  ·  waiting for a player` : 'Looking for an opponent...';
			else if (this.status === 'over') line = this.winnerLine();
			else {
				const cur = this.lastSync?.state?.current;
				const left = 15 - sc_[0] - sc_[1];
				line = `${cur ? (cur === 'A' ? this.seatName(0) : this.seatName(1)) + "'s turn  ·  " : ''}${left} ${left === 1 ? 'tile' : 'tiles'} left`;
			}
			text = `§l§f${this.seatName(0)}  §e${sc_[0]} – ${sc_[1]}  §f${this.seatName(1)}§r\n§f${line}`;
		}
		try {
			if (this.sign.nameTag !== text) this.sign.nameTag = text;
		} catch {}
	}

	private score(): [number, number] {
		const s: [number, number] = [0, 0];
		for (const row of this.shown?.board ?? []) for (const x of row) if (x) s[x.owner === 'A' ? 0 : 1]++;
		return s;
	}

	private winnerLine(): string {
		const w = this.lastSync?.winner;
		if (!w) return 'Game over';
		if (w === 'draw') return 'A draw!';
		return `${this.seatName(w === 'A' ? 0 : 1)} wins!`;
	}

	/* ---------- the end ---------- */

	private finish(): void {
		if (this.selftest) {
			const sc_ = this.score();
			console.log(`[Smash&Clash] selftest done: ${this.lastSync?.status}, winner ${this.lastSync?.winner}, score ${sc_[0]}-${sc_[1]}, entities ${this.dim.getEntities({ tags: [`snc@${this.key}`] }).length}`);
		}
		this.finished = true;
		this.status = 'over';
		const winner = this.lastSync!.winner;
		const sc_ = this.score();
		this.crowd.applaud();
		this.tween.at(15000, () => this.crowd.dismiss());
		for (let i = 0; i < 2; i++) {
			const s = this.local[i];
			if (!s) continue;
			const seat = i === 0 ? 'A' : 'B';
			const result = !winner || winner === 'draw' ? 0.5 : winner === seat ? 1 : 0;
			const r = (() => {
				const raw = world.getDynamicProperty(`${NS}:p:${s.player}`);
				try {
					return { rating: 1200, played: 0, won: 0, ruleset: 'mutators', ...(typeof raw === 'string' ? JSON.parse(raw) : {}) } as Rec;
				} catch {
					return { rating: 1200, played: 0, won: 0, ruleset: 'mutators' } as Rec;
				}
			})();
			const before = r.rating;
			let opp: number | null | undefined;
			if (this.saved.mode === 'house') opp = this.saved.strength;
			else if (this.local[1 - i]) opp = this.local[1 - i]!.rating;
			else opp = i === 0 ? this.state?.ratings?.B : this.state?.ratings?.A;
			if (typeof opp === 'number') r.rating = elo(r.rating, opp, result);
			r.played++;
			if (result === 1) r.won++;
			saveRec(s.player, r);
			const p = this.player(s);
			if (!p) continue;
			clearHand(p);
			const delta = r.rating - before;
			p.onScreenDisplay.setTitle(result === 1 ? '§6§lVICTORY!' : result === 0.5 ? '§f§lDRAW' : '§c§lDEFEAT', {
				subtitle: `${sc_[i]} – ${sc_[1 - i]}   ·   Rating ${r.rating}${delta ? ` (${delta > 0 ? '+' : ''}${delta})` : ''}`,
				fadeInDuration: 5,
				stayDuration: 60,
				fadeOutDuration: 15,
			});
			p.playSound(`${NS}.sfx.${result === 1 ? 'win' : 'lose'}`);
			p.playSound(`${NS}.announcer.${result === 1 ? 'victory' : 'defeat'}`);
			p.playMusic(`${NS}.music.${result === 1 ? 'victory' : 'defeat'}`, { fade: 1, volume: 0.6 });
			this.tween.at(9000, () => {
				try {
					p.stopMusic();
				} catch {}
			});
		}
		const id = this.saved.gameId!;
		const replay = this.lastSync?.replayUrl;
		sc.games.review(id).then(
			(review) => {
				for (let i = 0; i < 2; i++) {
					const p = this.player(this.local[i]);
					if (!p) continue;
					const me = i === 0 ? 'A' : 'B', them = i === 0 ? 'B' : 'A';
					p.sendMessage(`§6Game Review: §fyour accuracy ${Math.round(review.accuracy[me])}%, theirs ${Math.round(review.accuracy[them])}%${replay ? `  §9${replay}` : ''}`);
				}
			},
			() => {},
		);
		this.saved = { ...this.saved, gameId: undefined, code: undefined, mode: undefined, seats: [null, null] };
		persist(this);
		this.refreshSign();
	}

	/* ---------- feedback ---------- */

	/** Where the player to move can play the card they hold: rings only they see (private particles). */
	private glows(): void {
		for (let i = 0; i < 2; i++) {
			const s = this.local[i];
			const p = this.player(s);
			if (!p || !s?.view || this.status !== 'playing' || this.animating || this.queue.length || !s.view.yourTurn) continue;
			const want = new Map<string, [number, number, number]>();
			const YOU: [number, number, number] = [0.37, 0.65, 1], PICK: [number, number, number] = [1, 0.6, 0.38], ARMED: [number, number, number] = [1, 0.76, 0.1];
			const moves = s.view.moves ?? [];
			if (s.view.pendingHop) {
				for (const m of moves) if (m.type === 'hop') want.set(m.dest ?? s.view.pendingHop.from, m.dest ? YOU : PICK);
			} else {
				const id = cardOf(heldItem(p));
				const armed = !!s.armed && system.currentTick < s.armedUntil;
				for (const m of moves) {
					if (m.cardId !== id) continue;
					if (m.cell) want.set(m.cell, YOU);
					else if (m.effect === 'RECRUIT') {
						if (!s.recruitFrom && m.target) want.set(m.target, YOU);
						else if (s.recruitFrom === m.target && m.dest) want.set(m.dest, YOU);
					} else if (m.target) want.set(m.target, YOU);
					else for (let r = 0; r < 3; r++) for (let c = 0; c < 5; c++) want.set(rcToCell({ r, c }), armed ? ARMED : YOU);
				}
				if (s.recruitFrom) want.set(s.recruitFrom, PICK);
			}
			for (const [cell, color] of want) {
				const rc = cellToRC(cell);
				if (rc) this.ring(p, [rc.r, rc.c], color);
			}
		}
	}

	private ring(p: Player, rc: [number, number], color: [number, number, number]): void {
		const w = this.world(local(rc[0], rc[1]));
		const vars = new MolangVariableMap();
		vars.setColorRGBA('variable.color', { red: color[0], green: color[1], blue: color[2], alpha: 1 });
		try {
			p.spawnParticle(`${NS}:tile_glow`, v(w.x, this.center.y + (this.cells[rc[0]]![rc[1]] ? 0.035 : 0.01), w.z), vars);
		} catch {}
	}

	private flash(p: Player, rc: [number, number], color: [number, number, number]): void {
		for (let k = 0; k < 4; k++) this.tween.at(k * 100, () => this.ring(p, rc, color));
	}

	private hints(): void {
		if (system.currentTick < this.quietUntil) return;
		this.local.forEach((s, i) => {
			const p = this.player(s);
			if (!p || !s) return;
			let line: string | null = null;
			if (this.status === 'waiting') line = this.saved.code ? `Room ${this.saved.code}: waiting for a player` : 'Looking for an opponent...';
			else if (this.status === 'playing' && !this.animating && !this.queue.length && s.view) {
				if (s.view.yourTurn) line = s.view.pendingHop ? 'Hop to a glowing tile, or use your card to stay' : cardOf(heldItem(p)) < 0 ? 'Your turn: hold a card from your hand' : 'Your turn: use a glowing tile';
				else line = `${this.seatName(1 - i)} is thinking...`;
			}
			if (line) p.onScreenDisplay.setActionBar(line);
		});
	}

	/* ---------- the other player ---------- */

	private opponentUpkeep(): void {
		let remote = -1;
		if ((this.status === 'playing' || this.status === 'over') && this.state) {
			if (!this.local[0] && this.local[1]) remote = 0;
			else if (!this.local[1] && this.local[0]) remote = 1;
		}
		if (remote < 0) {
			if (this.opponent) safeRemove(this.opponent);
			this.opponent = undefined;
			return;
		}
		const seat = remote === 0 ? 'A' : 'B';
		if (!this.opponent?.isValid || this.opponentSeat !== seat) {
			if (this.opponent) safeRemove(this.opponent);
			const at = this.world(v(0, 0, (seat === 'A' ? 1 : -1) * (DEPTH / 2 + 0.75)));
			const yaw = (Math.atan2(-(this.center.x - at.x), this.center.z - at.z) * 180) / Math.PI;
			this.opponent = this.spawn('opponent', v(at.x, this.saved.y, at.z), yaw);
			this.opponent.setProperty(`${NS}:skin`, hash(this.saved.gameId ?? '') % 2);
			this.opponentSeat = seat;
		}
		const theirTurn = this.status === 'playing' && this.lastSync?.state?.current === seat;
		const thinking = theirTurn && !this.animating && !this.queue.length;
		try {
			this.opponent.nameTag = `§f${this.seatName(remote)}${thinking ? `\n§7${['•', '• •', '• • •'][Math.floor(system.currentTick / 8) % 3]}` : ''}`;
			this.opponent.setProperty(`${NS}:holding`, theirTurn);
		} catch {}
	}

	private swingIf(t: number, seat: string): void {
		if (seat !== this.opponentSeat) return;
		this.tween.run(Math.max(0, t - 100), 300, LINEAR, (e) => {
			try {
				this.opponent?.setProperty(`${NS}:swing`, Math.sin(Math.PI * e));
			} catch {}
		});
	}

	private turnTarget(): V {
		const cur = this.lastSync?.state?.current;
		if (cur) {
			const p = this.player(this.local[cur === 'A' ? 0 : 1]);
			if (p) return v(p.location.x, p.location.y + 1.6, p.location.z);
			if (this.opponent?.isValid && cur === this.opponentSeat) {
				const l = this.opponent.location;
				return v(l.x, l.y + 1.6, l.z);
			}
		}
		return this.center;
	}

	/* ---------- helpers ---------- */

	player(s: LocalSeat | null | undefined): Player | undefined {
		if (!s) return undefined;
		return world.getAllPlayers().find((p) => p.id === s.player);
	}

	seatOf(p: Player): number {
		return this.local.findIndex((s) => s?.player === p.id);
	}

	seatName(i: number): string {
		const s = this.local[i];
		if (s) return s.name;
		const st = this.state;
		if (!st?.players) return '...';
		const kind = i === 0 ? st.playerKinds?.A : st.playerKinds?.B;
		if (kind === 'house') return personaName(st.id);
		return (i === 0 ? st.players.A : st.players.B) ?? '...';
	}
}

/* ------------------------------ the crowd ------------------------------ */

/**
 * The villagers who come to watch: villagers already close by (16 blocks) walk to spots out on the
 * board's long sides, clear of both seats, and stay put there (their movement held at zero). They
 * turn to whoever's turn it is and react on the spot: hops and cheers for captures, a head shake
 * when an attack is held off. When the game is over they go back to their day.
 */
class Crowd {
	private watching: Array<{ e: Entity; settled: boolean }> = [];
	constructor(private t: Table) {}

	private spot(i: number): V {
		const side = i % 2 === 0 ? -1 : 1;
		const along = [0, -1, 1][Math.floor(i / 2) % 3]!;
		const w = this.t.world(v(side * (WIDTH / 2 + 2.6), 0, along));
		return v(Math.floor(w.x) + 0.5, this.t.saved.y, Math.floor(w.z) + 0.5);
	}

	tick(on: boolean): void {
		this.watching = this.watching.filter((w) => w.e.isValid);
		if (!on) return;
		if (this.watching.length < 6) {
			for (const e of this.t.dim.getEntities({ type: 'minecraft:villager_v2', location: this.t.center, maxDistance: 16 })) {
				if (this.watching.length >= 6) break;
				if (!this.watching.some((w) => w.e.id === e.id)) this.watching.push({ e, settled: false });
			}
		}
	}

	look(target: V): void {
		this.watching.forEach((w, i) => {
			const s = this.spot(i);
			try {
				const l = w.e.location;
				const dx = s.x - l.x, dz = s.z - l.z;
				const d = Math.hypot(dx, dz);
				if (!w.settled) {
					if (d < 0.4) {
						w.settled = true;
						w.e.getComponent('minecraft:movement')?.setCurrentValue(0);
					} else {
						// walk them over, a step a tick, facing where they go
						const step = Math.min(d, 0.12);
						w.e.teleport(v(l.x + (dx / d) * step, l.y, l.z + (dz / d) * step), { facingLocation: s });
						return;
					}
				}
				if (d > 0.6) w.e.teleport(s);
				w.e.teleport(w.e.location, { facingLocation: target });
			} catch {}
		});
	}

	private hop(e: Entity): void {
		try {
			if (e.isOnGround) e.applyImpulse({ x: 0, y: 0.38, z: 0 });
		} catch {}
	}

	cheer(n: number): void {
		for (const { e } of this.watching) {
			if (n < 2 && Math.random() > 0.5) continue;
			try {
				e.dimension.playSound(n >= 3 ? 'mob.villager.yes' : 'mob.villager.yes', e.location);
				e.dimension.spawnParticle('minecraft:villager_happy', v(e.location.x, e.location.y + 2.2, e.location.z));
			} catch {}
			if (n >= 2 || Math.random() < 0.3) this.hop(e);
		}
	}

	groan(): void {
		for (const { e } of this.watching) {
			if (Math.random() > 0.6) continue;
			try {
				e.dimension.playSound('mob.villager.no', e.location);
				e.dimension.spawnParticle('minecraft:villager_angry', v(e.location.x, e.location.y + 2.2, e.location.z));
			} catch {}
		}
	}

	murmur(): void {
		for (const { e } of this.watching) {
			if (Math.random() > 0.6) continue;
			try {
				e.dimension.playSound('mob.villager.idle', e.location);
			} catch {}
			if (Math.random() < 0.5) this.hop(e);
		}
	}

	applaud(): void {
		for (const { e } of this.watching) {
			try {
				e.dimension.playSound('mob.villager.yes', e.location);
				e.dimension.spawnParticle('minecraft:villager_happy', v(e.location.x, e.location.y + 2.2, e.location.z));
			} catch {}
			this.hop(e);
		}
	}

	dismiss(): void {
		for (const { e } of this.watching) {
			try {
				e.getComponent('minecraft:movement')?.resetToDefaultValue();
			} catch {}
		}
		this.watching = [];
	}
}

/* ------------------------------ hands ------------------------------ */

const CARD_ITEM = (id: number) => `${NS}:card_${id}`;

export function cardOf(stack: ItemStack | undefined): number {
	const m = stack ? /^smashandclash:card_(\d+)$/.exec(stack.typeId) : null;
	return m ? Number(m[1]) : -1;
}

export function heldItem(p: Player): ItemStack | undefined {
	const inv = p.getComponent('minecraft:inventory')?.container;
	return inv?.getItem(p.selectedSlotIndex);
}

/** Make the player's card items match the hand the game says they hold. */
export function syncHand(p: Player, view: PlayerView, table: string): void {
	const inv = p.getComponent('minecraft:inventory')?.container;
	if (!inv) return;
	const want = view.hand.map((h) => h.cardId);
	for (let i = 0; i < inv.size; i++) {
		const it = inv.getItem(i);
		const id = cardOf(it);
		if (id < 0) continue;
		const at = want.indexOf(id);
		if (at >= 0 && it?.getDynamicProperty(`${NS}:table`) === table) {
			want.splice(at, 1);
			continue;
		}
		inv.setItem(i, undefined);
	}
	for (const id of want) {
		const d = DECK.get(id);
		const it = new ItemStack(CARD_ITEM(id), 1);
		it.setLore(d ? (d.kind === 'effect' ? [`§7${d.does ?? ''}`] : [`§7▲ ${d.top}   ▶ ${d.right}   ▼ ${d.bottom}   ◀ ${d.left}`, `§8${d.color ?? ''}`]) : []);
		it.setDynamicProperty(`${NS}:table`, table);
		inv.addItem(it);
	}
}

export function clearHand(p: Player): void {
	const inv = p.getComponent('minecraft:inventory')?.container;
	if (!inv) return;
	for (let i = 0; i < inv.size; i++) if (cardOf(inv.getItem(i)) >= 0) inv.setItem(i, undefined);
}

/* ------------------------------ how to play ------------------------------ */

const HOWTO: Array<[string, string, string]> = [
	['basics-goal', 'Own the board', 'Cards belong to whoever they face. Own more when the board fills!'],
	['basics-turn', 'One card a turn', 'Play one card each turn, then draw back up to five.'],
	['basics-capture', 'Match or beat to capture', "Touch their card. Tie or beat their number, and it's yours!"],
	['basics-tie', 'Ties go to the attacker', 'A tie still wins. The player placing the card takes it.'],
	['basics-sides', 'Four sides, no diagonals', 'Every touching side fights at once. Diagonals never count.'],
	['basics-nochain', 'No chain reactions', 'Captured cards flip, but they never attack. No chain reactions!'],
	['mut-intro', 'Mutators', 'Mutators is the default game: Basics, plus three special tiles.'],
	['mut-operator', 'Operator tiles', 'Land on an operator tile, hop like its chess piece, attack again!'],
	['mut-power', 'Power tiles', "Match the tile's colour, and every number gets the boost!"],
	['mut-overrun', 'Overrun', 'Boxed in at the centre? Slam a fresh card right on top!'],
	['fx-rule', 'Effect cards', 'Effect cards bend the rules, but they must do something.'],
	['fx-boulder', 'Boulder', 'Boulder smashes a whole row and column. Frozen cards survive!'],
	['fx-flip', 'Flip', 'Flip spins the board. Every card switches sides, even frozen ones!'],
	['fx-freeze', 'Freeze', 'Freeze locks your card for good. Nothing can capture it.'],
	['fx-recruit', 'Recruit', 'Recruit steals an enemy card and drops it down as yours!'],
	['fx-swap', 'Swap', "Swap trades your whole hand for your opponent's. Bold move!"],
];

export async function howTo(p: Player, i = 0): Promise<void> {
	const [key, title, line] = HOWTO[i]!;
	p.playSound(`${NS}.howto.${key}`);
	const res = await new ActionFormData()
		.title(`How to play  ·  ${i + 1} / ${HOWTO.length}`)
		.body(`§l${title}§r\n\n${line}`)
		.button(i + 1 < HOWTO.length ? '§lNEXT' : '§lDONE')
		.button(i > 0 ? 'Back' : 'Close')
		.show(p as never);
	if (res.canceled) return;
	if (res.selection === 0 && i + 1 < HOWTO.length) return howTo(p, i + 1);
	if (res.selection === 1 && i > 0) return howTo(p, i - 1);
}

/* ------------------------------ misc ------------------------------ */

type Work = Array<Array<{ cardId: number; owner: 'A' | 'B'; frozen: boolean } | null>>;
interface Pair {
	cell: [number, number];
	atk: number;
	boost: number;
	def: number;
	win: boolean;
}
interface Clash {
	cardId: number;
	pairs: Pair[];
}

function capturedAfter(evs: Array<Record<string, unknown> & { type: string }>, k: number): Record<string, unknown> | null {
	for (let i = k + 1; i < evs.length; i++) {
		const t = evs[i]!.type;
		if (t === 'cardsCaptured') return evs[i]!;
		if (/^(cardPlaced|cardHopped|cardRecruited|cardOverrun|turnStarted|effectPlayed)$/.test(t)) return null;
	}
	return null;
}

function rcOf(x: unknown): [number, number] {
	if (x && typeof x === 'object') return [Number((x as { r: number }).r), Number((x as { c: number }).c)];
	const rc = cellToRC(String(x));
	return rc ? [rc.r, rc.c] : [1, 2];
}

function safeRemove(e: Entity): void {
	try {
		if (e.isValid) e.remove();
	} catch {}
}

function dist2(a: Vector3, b: Vector3): number {
	return (a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2;
}

function hash(s: string): number {
	let h = 0;
	for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
	return Math.abs(h);
}

function errorText(e: unknown): string {
	if (e instanceof SmashAndClashError) return e.message;
	return "Couldn't reach Smash&Clash. Check the server's connection.";
}

/* ------------------------------ the registry ------------------------------ */

export const TABLES = new Map<string, Table>();

export function persist(t?: Table): void {
	void t;
	const all = [...TABLES.values()].map((x) => x.saved);
	world.setDynamicProperty(`${NS}:tables`, JSON.stringify(all));
}

export function loadTables(): void {
	const raw = world.getDynamicProperty(`${NS}:tables`);
	if (typeof raw !== 'string') return;
	try {
		for (const s of JSON.parse(raw) as Saved[]) TABLES.set(s.key, new Table(s));
	} catch {}
}

export function tableKeyOf(dim: string, x: number, y: number, z: number): string {
	return `${dim.replace('minecraft:', '')}@${x},${y},${z}`;
}

export { parseLink };
