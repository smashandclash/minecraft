// Smash&Clash for Minecraft Bedrock: place a table and play anyone on any client. The game is
// played on @smashandclash/sdk (bundled in), over HTTP from the server (@minecraft/server-net).

import { Player, system, world } from '@minecraft/server';
import { NS, TABLES, Table, cardOf, heldItem, loadTables, persist, sc, tableKeyOf } from './table';

const near = (a: { x: number; y: number; z: number }, b: { x: number; y: number; z: number }, d: number) => (a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2 < d * d;

// using the table opens its menu: the block's own custom component (blocks only report a use when they declare one)
system.beforeEvents.startup.subscribe(({ blockComponentRegistry }) => {
	blockComponentRegistry.registerCustomComponent(`${NS}:table`, {
		onPlayerInteract: ({ block, player }) => {
			if (!player) return;
			const t = TABLES.get(tableKeyOf(block.dimension.id, block.location.x, block.location.y, block.location.z));
			if (t) system.run(() => void t.openMenu(player));
		},
	});
});

world.afterEvents.worldLoad.subscribe(() => {
	loadTables();
	console.log(`[Smash&Clash] ready: ${TABLES.size} table(s)`);
});

// place a table: it faces away from you (you sit at seat A)
world.afterEvents.playerPlaceBlock.subscribe(({ block, player }) => {
	if (block.typeId !== `${NS}:table`) return;
	const d = player.getViewDirection();
	const [fx, fz] = Math.abs(d.x) > Math.abs(d.z) ? [Math.sign(d.x), 0] : [0, Math.sign(d.z) || 1];
	const key = tableKeyOf(block.dimension.id, block.location.x, block.location.y, block.location.z);
	const t = new Table({ key, dim: block.dimension.id, x: block.location.x, y: block.location.y, z: block.location.z, fx, fz, seats: [null, null] });
	TABLES.set(key, t);
	persist();
});

world.afterEvents.playerBreakBlock.subscribe(({ block, brokenBlockPermutation }) => {
	if (brokenBlockPermutation.type.id !== `${NS}:table`) return;
	const key = tableKeyOf(block.dimension.id, block.location.x, block.location.y, block.location.z);
	TABLES.get(key)?.destroy();
	TABLES.delete(key);
	persist();
});

const tileOf = (tags: string[]): { key: string; r: number; c: number } | null => {
	const tile = tags.find((x) => x.startsWith('snc_tile_'));
	const at = tags.find((x) => x.startsWith('snc@'));
	if (!tile || !at) return null;
	const [r, c] = tile.slice(9).split('_').map(Number);
	return { key: at.slice(4), r: r!, c: c! };
};

// use a card on a tile: play it there
world.afterEvents.playerInteractWithEntity.subscribe(({ player, target, itemStack }) => {
	const tile = tileOf(target.getTags());
	if (!tile) return;
	TABLES.get(tile.key)?.onTile(player, tile.r, tile.c, itemStack ?? heldItem(player));
});

// hit a tile: what is on it
world.afterEvents.entityHitEntity.subscribe(({ damagingEntity, hitEntity }) => {
	if (!(damagingEntity instanceof Player)) return;
	const tile = tileOf(hitEntity.getTags());
	if (tile) TABLES.get(tile.key)?.inspect(damagingEntity, tile.r, tile.c);
});

// a card can't be thrown away or used on anything else
world.beforeEvents.playerInteractWithBlock.subscribe((ev) => {
	if (cardOf(ev.itemStack) >= 0 && ev.block.typeId !== `${NS}:table`) ev.cancel = true;
});

system.runInterval(() => {
	const players = world.getAllPlayers();
	for (const t of TABLES.values()) {
		// a table runs while someone is near it (its chunk is loaded) or it has a game on
		const loaded = players.some((p) => p.dimension.id === t.dim.id && near(p.location, t.center, 64));
		if (!loaded && t.status === 'idle') continue;
		try {
			t.tick();
		} catch (e) {
			console.warn('[Smash&Clash] table: ' + String(e));
		}
	}
	// card items on the ground are not cards any more
	if (system.currentTick % 20 === 0) {
		for (const dim of ['overworld', 'nether', 'the_end']) {
			for (const item of world.getDimension(dim).getEntities({ type: 'minecraft:item' })) {
				const stack = item.getComponent('minecraft:item')?.itemStack;
				if (cardOf(stack) >= 0) item.remove();
			}
		}
	}
}, 1);

// /scriptevent smashandclash:ping - is the games API reachable from this server? (needs Beta APIs + @minecraft/server-net allowed)
system.afterEvents.scriptEventReceive.subscribe((ev) => {
	if (ev.id === `${NS}:selftest`) {
		// a table at the world's centre plays a house game by itself (run: tickingarea add circle 0 100 0 2 first)
		const dim = world.getDimension('overworld');
		const y = 100;
		for (let x = -4; x <= 4; x++) for (let z = -4; z <= 4; z++) dim.getBlock({ x, y: y - 1, z })?.setType('minecraft:grass_block');
		dim.getBlock({ x: 0, y, z: 0 })?.setType(`${NS}:table`);
		const key = tableKeyOf('minecraft:overworld', 0, y, 0);
		TABLES.get(key)?.destroy();
		const t = new Table({ key, dim: 'minecraft:overworld', x: 0, y, z: 0, fx: 0, fz: -1, seats: [null, null] });
		TABLES.set(key, t);
		system.runTimeout(() => t.selfTest(), 60);
		return;
	}
	// testing, as a player (the bot test runs these through /scriptevent)
	const me = ev.sourceEntity instanceof Player ? ev.sourceEntity : undefined;
	const nearest = () => (me ? [...TABLES.values()].find((t) => t.dim.id === me.dimension.id && near(me.location, t.center, 8)) : undefined);
	if (ev.id === `${NS}:place`) {
		const dim = world.getDimension('overworld');
		for (let x = -5; x <= 5; x++) for (let z = -5; z <= 5; z++) dim.getBlock({ x, y: 99, z })?.setType('minecraft:grass_block');
		dim.getBlock({ x: 0, y: 100, z: 0 })?.setType(`${NS}:table`);
		const key = tableKeyOf('minecraft:overworld', 0, 100, 0);
		TABLES.get(key)?.destroy();
		TABLES.set(key, new Table({ key, dim: 'minecraft:overworld', x: 0, y: 100, z: 0, fx: 0, fz: -1, seats: [null, null] }));
		persist();
		console.log('[Smash&Clash] test: table placed at 0 100 0');
		return;
	}
	if (ev.id === `${NS}:use` && me) {
		const t = nearest();
		console.log(`[Smash&Clash] test: ${me.name} uses the table (${t ? t.status : 'no table near'})`);
		if (t) void t.openMenu(me);
		return;
	}
	if (ev.id === `${NS}:state` && me) {
		const t = nearest();
		const inv = me.getComponent('minecraft:inventory')?.container;
		const cards: string[] = [];
		if (inv) for (let i = 0; i < inv.size; i++) {
			const id = cardOf(inv.getItem(i));
			if (id >= 0) cards.push(`${i}:${id}`);
		}
		const v = t?.testView(me);
		console.log(`[Smash&Clash] test state: table ${t?.status}, yourTurn ${v?.yourTurn}, moves ${v?.moves?.length ?? 0}, cards in inventory [${cards.join(' ')}]`);
		return;
	}
	if (ev.id === `${NS}:tapfirst` && me) {
		const t = nearest();
		const v = t?.testView(me);
		const m = v?.moves?.find((x) => x.type === 'place' || x.type === 'hop');
		if (!t || !v || !m) return console.log('[Smash&Clash] test: nothing to play');
		const inv = me.getComponent('minecraft:inventory')?.container;
		if (inv && m.cardId !== undefined) for (let i = 0; i < 9; i++) if (cardOf(inv.getItem(i)) === m.cardId) me.selectedSlotIndex = i;
		const cell = m.type === 'hop' ? (m.dest ?? v.pendingHop?.from) : m.cell;
		const rc = /^([A-E])([1-3])$/.exec(String(cell));
		if (!rc) return;
		console.log(`[Smash&Clash] test: ${me.name} plays ${m.name} with the card in slot ${me.selectedSlotIndex}`);
		t.onTile(me, Number(rc[2]) - 1, 'ABCDE'.indexOf(rc[1]!), heldItem(me));
		return;
	}
	if (ev.id !== `${NS}:ping`) return;
	sc.rules().then(
		(r) => console.log(`[Smash&Clash] API ok: ${r.slice(0, 80)}`),
		(e) => console.warn(`[Smash&Clash] API unreachable: ${String(e)}`),
	);
	sc.games.startHouse({ name: 'ping', as: 'person' }).then(
		(g) => {
			console.log(`[Smash&Clash] house game ${g.id}: ${g.legalMoves.length} legal moves, your turn ${g.yourTurn}`);
			return g.resign();
		},
		(e) => console.warn(`[Smash&Clash] start failed: ${String(e)}`),
	);
});
