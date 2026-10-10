// Fetches everything the Minecraft mod draws and plays through the Smash&Clash SDK
// (sc.cards() and sc.assets()) and converts it for Minecraft:
//   - card faces, the card back, character art and attack drawings: WebP -> PNG block textures
//     (the block atlas is mipmapped, so cards on the table stay clean at a distance)
//   - music, sound effects, the announcer, the how-to coach and every character's voice: MP3 -> OGG Vorbis
//   - the display and label fonts: TTF
// and writes what Minecraft needs to use them: item models (a card you hold, a card on the
// table, the character art), item model definitions, sounds.json, the font providers, the
// table's board texture, and cards.json / kit.json for the mod.
//
// The art, audio and characters are Smash&Clash's: free to use in clients built for
// Smash&Clash, never to present as your own (see sc.assets().terms).
//
//   node build-assets.mjs            (needs ffmpeg with libvorbis on PATH)
//   node build-assets.mjs --fresh    (convert everything again)

import { SmashAndClash } from '@smashandclash/sdk';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Canvas, noise } from './png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const CACHE = join(HERE, '.cache');
const OUT = join(HERE, '..', 'java', 'src', 'main', 'resources', 'assets', 'smashandclash');
const FRESH = process.argv.includes('--fresh');

const sc = new SmashAndClash({ client: 'minecraft-assets/1.0.0' });

// Texture sizes: multiples of 16 so the block atlas keeps its mipmaps.
const FACE = [256, 352]; // card faces (563x768)
const ART = [256, 256]; // frameless art and attack drawings
const FONTS = {
	// the kit names Luckiest Guy (display) and Lilita One (label); Minecraft reads TTF
	display: 'https://www.smashandclash.in/LuckiestGuy-Regular.ttf',
	label: 'https://github.com/google/fonts/raw/main/ofl/lilitaone/LilitaOne-Regular.ttf',
};

// The board, in texture pixels (BoardGeometry.java uses the same numbers: 165 px a block).
export const BOARD = { w: 512, h: 416, slotW: 83, slotH: 112, pitchX: 92, pitchY: 122, x0: 30, y0: 30 };

/** A Minecraft resource name: lowercase a-z 0-9 . _ - */
const rid = (s) => String(s).replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase().replace(/[^a-z0-9._-]+/g, '_');

async function fetchCached(url) {
	const file = join(CACHE, createHash('sha1').update(url).digest('hex').slice(0, 16) + '_' + url.split('/').pop().replace(/[^\w.-]/g, '_'));
	if (existsSync(file)) return file;
	for (let attempt = 0; ; attempt++) {
		try {
			const res = await fetch(url);
			if (!res.ok) throw new Error(`${res.status} ${url}`);
			await writeFile(file, Buffer.from(await res.arrayBuffer()));
			return file;
		} catch (e) {
			if (attempt >= 3) throw e;
			await new Promise((r) => setTimeout(r, 500 * 3 ** attempt));
		}
	}
}

function ffmpeg(args) {
	return new Promise((resolve, reject) => {
		const p = spawn('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args], { stdio: ['ignore', 'ignore', 'pipe'] });
		let err = '';
		p.stderr.on('data', (d) => (err += d));
		p.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg ${args.join(' ')}\n${err}`))));
	});
}

// a small pool: ffmpeg is the slow part
const jobs = [];
const job = (fn) => jobs.push(fn);
async function runJobs(n = 8) {
	let i = 0;
	let done = 0;
	const worker = async () => {
		while (i < jobs.length) {
			const fn = jobs[i++];
			await fn();
			if (++done % 200 === 0) console.log(`  ${done}/${jobs.length}`);
		}
	};
	await Promise.all(Array.from({ length: n }, worker));
}

async function json(rel, data) {
	const out = join(OUT, rel);
	await mkdir(dirname(out), { recursive: true });
	await writeFile(out, JSON.stringify(data, null, 1));
}

/** A WebP from the site -> a PNG block texture of exactly w x h. */
function texture(url, rel, [w, h]) {
	const out = join(OUT, 'textures', 'block', `${rel}.png`);
	job(async () => {
		if (!FRESH && existsSync(out)) return;
		await mkdir(dirname(out), { recursive: true });
		const src = await fetchCached(url);
		await ffmpeg(['-i', src, '-vf', `scale=${w}:${h}:flags=lanczos`, '-pix_fmt', 'rgba', out]);
	});
	return `smashandclash:block/${rel}`;
}

/** One sound event: every URL a variant. */
const sounds = {};
function sound(event, urls, { music = false, voice = false } = {}) {
	const list = (Array.isArray(urls) ? urls : [urls]).filter(Boolean);
	if (!list.length) return;
	const entries = [];
	list.forEach((url, i) => {
		const rel = `${event.replace(/\./g, '/')}${list.length > 1 ? `_${i + 1}` : ''}`;
		const out = join(OUT, 'sounds', `${rel}.ogg`);
		entries.push(music ? { name: `smashandclash:${rel}`, stream: true } : `smashandclash:${rel}`);
		job(async () => {
			if (!FRESH && existsSync(out)) return;
			await mkdir(dirname(out), { recursive: true });
			const src = await fetchCached(url);
			// music: stereo; everything else mono, so it plays from the table, positioned in the world
			const enc = music ? ['-q:a', '1'] : voice ? ['-ac', '1', '-ar', '32000', '-q:a', '0'] : ['-ac', '1', '-q:a', '3'];
			await ffmpeg(['-i', src, '-map_metadata', '-1', '-vn', '-c:a', 'libvorbis', ...enc, ...(music || !voice ? ['-ar', '44100'] : []), out]);
		});
	});
	sounds[event] = { sounds: entries };
}

/* ---------------------------------- models ---------------------------------- */

// A card: 11 x 15 pixels (the card's 0.733 aspect), half a pixel thick, face to the south.
const CARD_BASE = {
	textures: { back: 'smashandclash:block/card/back', particle: '#front' },
	gui_light: 'front',
	elements: [
		{
			from: [2.5, 0.5, 7.75],
			to: [13.5, 15.5, 8.25],
			faces: {
				south: { uv: [0, 0, 16, 16], texture: '#front' },
				north: { uv: [16, 0, 0, 16], texture: '#back' },
				east: { uv: [0, 0, 1, 16], texture: '#back' },
				west: { uv: [0, 0, 1, 16], texture: '#back' },
				up: { uv: [0, 0, 16, 1], texture: '#back' },
				down: { uv: [0, 0, 16, 1], texture: '#back' },
			},
		},
	],
	display: {
		gui: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1.1, 1.1, 1.1] },
		fixed: { rotation: [0, 0, 0], scale: [1, 1, 1] },
		ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
		// held up in front of you, face toward you, like a real hand of cards
		firstperson_righthand: { rotation: [0, 0, 0], translation: [0.6, -0.4, 0], scale: [0.5, 0.5, 0.5] },
		firstperson_lefthand: { rotation: [0, 0, 0], translation: [-0.6, -0.4, 0], scale: [0.5, 0.5, 0.5] },
		thirdperson_righthand: { rotation: [0, 90, 0], translation: [0, 3, 1], scale: [0.42, 0.42, 0.42] },
		thirdperson_lefthand: { rotation: [0, -90, 0], translation: [0, 3, 1], scale: [0.42, 0.42, 0.42] },
		head: { rotation: [0, 180, 0], translation: [0, 13, 7], scale: [1, 1, 1] },
	},
};

// A flat sprite (the character art, an attack drawing, a frost sheet): a square in the z = 8 plane.
const SPRITE_BASE = {
	textures: { particle: '#sprite' },
	elements: [
		{
			from: [0, 0, 8],
			to: [16, 16, 8],
			shade: false,
			faces: {
				south: { uv: [0, 0, 16, 16], texture: '#sprite' },
				north: { uv: [16, 0, 0, 16], texture: '#sprite' },
			},
		},
	],
};

// The card you hold: the face on both sides, so you see it whichever way the camera looks
// (everyone else is shown card_back by the item definition).
const CARD_HELD = {
	...CARD_BASE,
	elements: [
		{
			...CARD_BASE.elements[0],
			faces: { ...CARD_BASE.elements[0].faces, north: { uv: [0, 0, 16, 16], texture: '#front' } },
		},
	],
};

const model = (id) => ({ type: 'minecraft:model', model: id });

async function main() {
	await mkdir(CACHE, { recursive: true });
	console.log('Reading the deck and the kit through @smashandclash/sdk ...');
	const [cards, kit] = await Promise.all([sc.cards(), sc.assets()]);
	console.log(`  ${cards.length} cards; kit v${kit.version}`);

	// generated files are rebuilt every run; converted ones only with --fresh
	for (const sub of ['models', 'items', 'blockstates', 'font', 'textures/gui', ...(FRESH ? ['textures/block', 'sounds'] : [])]) {
		await rm(join(OUT, sub), { recursive: true, force: true });
	}

	// ---- the cards: textures, a model each, and two item model definitions each:
	//      card/<id> is the card you hold (others see its back), board/<id> the card on the table
	await json('models/item/card_base.json', CARD_BASE);
	await json('models/item/sprite_base.json', SPRITE_BASE);
	await json('models/item/card_held_base.json', CARD_HELD);
	const back = texture(kit.card.back, 'card/back', FACE);
	await json('models/item/card_back.json', { parent: 'smashandclash:item/card_base', textures: { front: back } });
	await json('items/card.json', { model: model('smashandclash:item/card_back') });
	await json('items/card_back.json', { model: model('smashandclash:item/card_back') });
	for (const c of cards) {
		const face = texture(c.image, `card/${c.id}`, FACE);
		await json(`models/item/card/${c.id}.json`, { parent: 'smashandclash:item/card_base', textures: { front: face } });
		await json(`models/item/card_held/${c.id}.json`, { parent: 'smashandclash:item/card_held_base', textures: { front: face } });
		await json(`items/card/${c.id}.json`, {
			model: {
				type: 'minecraft:condition',
				property: 'minecraft:view_entity',
				on_true: model(`smashandclash:item/card_held/${c.id}`),
				on_false: model('smashandclash:item/card_back'),
			},
		});
		await json(`items/board/${c.id}.json`, { model: model(`smashandclash:item/card/${c.id}`) });
		const sprite = async (name, url) => {
			const t = texture(url, name, ART);
			await json(`models/item/${name}.json`, { parent: 'smashandclash:item/sprite_base', textures: { sprite: t } });
			await json(`items/${name}.json`, { model: model(`smashandclash:item/${name}`) });
		};
		if (c.art) await sprite(`art/${c.id}`, c.art);
		if (c.animation) {
			await sprite(`attack/${c.id}_windup`, c.animation.windup);
			await sprite(`attack/${c.id}_strike`, c.animation.strike);
		}
	}

	// ---- the table: the board top (drawn here), the rim under each card (tinted per viewer), the pedestal
	await mkdir(join(OUT, 'textures', 'block'), { recursive: true });
	await writeFile(join(OUT, 'textures', 'block', 'board.png'), drawBoard());
	await writeFile(join(OUT, 'textures', 'block', 'rim.png'), drawRim());
	await writeFile(join(OUT, 'textures', 'block', 'glow.png'), drawGlow());
	// A half of the board as slices along its depth: the outer corners step in like the board's
	// rounded corners (18 px on the texture = 0.06 blocks), the seam side stays square so the halves meet.
	const BOARD_W = BOARD.w / 300, BOARD_D = BOARD.h / 300; // blocks at the table's size (300 px a block)
	const boardModel = (u0, u1, outer) => {
		const r = 18 / 300; // the corner radius, in blocks
		const rx = (r / (BOARD_W * (u1 - u0) / 16)) * 16, rz = (r / BOARD_D) * 16;
		const n = 5;
		const bands = [];
		for (let k = 0; k < n; k++) {
			const zA = (k * rz) / n, zB = ((k + 1) * rz) / n;
			const t = 1 - (k + 0.5) / n;
			const inset = rx * (1 - Math.sqrt(1 - t * t));
			bands.push([zA, zB, inset], [16 - zB, 16 - zA, inset]);
		}
		bands.push([rz, 16 - rz, 0]);
		const uv = (x) => u0 + (x / 16) * (u1 - u0);
		const elements = bands.map(([z0, z1, inset]) => {
			const x0 = outer === 'left' ? inset : 0, x1 = outer === 'right' ? 16 - inset : 16;
			return {
				from: [x0, 0, z0],
				to: [x1, 16, z1],
				faces: {
					up: { uv: [uv(x0), z0, uv(x1), z1], texture: '#top' },
					down: { uv: [0, 0, 16, 16], texture: '#side' },
					north: { uv: [0, 0, 16, 16], texture: '#side' },
					south: { uv: [0, 0, 16, 16], texture: '#side' },
					east: { uv: [0, 0, 16, 16], texture: '#side' },
					west: { uv: [0, 0, 16, 16], texture: '#side' },
				},
			};
		});
		return { textures: { top: 'smashandclash:block/board', side: 'smashandclash:block/board_side', particle: 'smashandclash:block/board_side' }, elements };
	};
	await json('models/item/board.json', boardModel(0, 16, 'both'));
	await json('models/item/board_left.json', boardModel(0, 8, 'left'));
	await json('models/item/board_right.json', boardModel(8, 16, 'right'));
	await json('items/board_left.json', { model: model('smashandclash:item/board_left') });
	await json('items/board_right.json', { model: model('smashandclash:item/board_right') });
	await writeFile(join(OUT, 'textures', 'block', 'board_side.png'), drawBoardSide());
	await json('items/board.json', { model: model('smashandclash:item/board') });
	for (const [name, tex] of [
		['rim', 'smashandclash:block/rim'],
		['glow', 'smashandclash:block/glow'],
	]) {
		await json(`models/item/${name}.json`, {
			textures: { sprite: tex, particle: tex },
			elements: [
				{
					from: [0, 0, 8],
					to: [16, 16, 8],
					shade: false,
					faces: { south: { uv: [0, 0, 16, 16], texture: '#sprite', tintindex: 0 }, north: { uv: [16, 0, 0, 16], texture: '#sprite', tintindex: 0 } },
				},
			],
		});
	}
	// the rim: blue for your cards, orange for theirs, whoever looks (SeatTint on the client)
	await json('items/rim.json', { model: { type: 'minecraft:model', model: 'smashandclash:item/rim', tints: [{ type: 'smashandclash:seat', default: 0xffffff }] } });
	// a tile you can play: tinted in your colour by the server for one player at a time
	await json('items/glow.json', { model: { type: 'minecraft:model', model: 'smashandclash:item/glow', tints: [{ type: 'minecraft:custom_model_data', default: 0x5fa6ff }] } });

	await json('models/block/table.json', {
		parent: 'minecraft:block/block',
		textures: { log: 'minecraft:block/stripped_dark_oak_log', top: 'minecraft:block/dark_oak_planks', particle: 'minecraft:block/stripped_dark_oak_log' },
		elements: [
			{ from: [1, 0, 1], to: [15, 2, 15], faces: Object.fromEntries(['north', 'south', 'east', 'west', 'up', 'down'].map((f) => [f, { texture: ['up', 'down'].includes(f) ? '#top' : '#log', cullface: f === 'down' ? 'down' : undefined }])) },
			{ from: [4, 2, 4], to: [12, 14, 12], faces: Object.fromEntries(['north', 'south', 'east', 'west'].map((f) => [f, { texture: '#log' }])) },
			{ from: [2, 14, 2], to: [14, 16, 14], faces: Object.fromEntries(['north', 'south', 'east', 'west', 'up', 'down'].map((f) => [f, { texture: f === 'up' ? '#top' : '#log' }])) },
		],
		display: {
			gui: { rotation: [30, 225, 0], scale: [0.625, 0.625, 0.625] },
			ground: { translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
			fixed: { scale: [0.5, 0.5, 0.5] },
			thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
			firstperson_righthand: { rotation: [0, 45, 0], scale: [0.4, 0.4, 0.4] },
			firstperson_lefthand: { rotation: [0, 225, 0], scale: [0.4, 0.4, 0.4] },
		},
	});
	await json('blockstates/table.json', { variants: { '': { model: 'smashandclash:block/table' } } });
	await json('items/table.json', { model: model('smashandclash:block/table') });
	await texture(kit.brand.logoPng, '../gui/logo', [512, 512]);

	// ---- audio
	const a = kit.audio;
	for (const [state, urls] of Object.entries(a.music)) sound(`music.${rid(state)}`, urls, { music: true });
	for (const [k, urls] of Object.entries(a.sfx)) sound(`sfx.${rid(k)}`, urls);
	for (const [k, urls] of Object.entries(a.announcer)) sound(`announcer.${rid(k)}`, urls, { voice: true });
	for (const [k, urls] of Object.entries(a.howTo)) sound(`howto.${rid(k.replace(/^howto\./, ''))}`, urls, { voice: true });
	for (const [id, lines] of Object.entries(a.voices)) {
		for (const [kind, v] of Object.entries(lines)) {
			if (Array.isArray(v) || typeof v === 'string') sound(`voice.${id}.${rid(kind)}`, v, { voice: true });
			else if (v && typeof v === 'object') for (const [n, urls] of Object.entries(v)) sound(`voice.${id}.${rid(kind)}.${rid(n)}`, urls, { voice: true });
		}
	}

	console.log(`Converting ${jobs.length} files with ffmpeg ...`);
	await runJobs();
	await json('sounds.json', sounds);

	// ---- fonts (TTF providers; Minecraft's default font stays the body text)
	for (const [name, url] of Object.entries(FONTS)) {
		const src = await fetchCached(url);
		await mkdir(join(OUT, 'font'), { recursive: true });
		await writeFile(join(OUT, 'font', `${name}.ttf`), await readFile(src));
		const provider = { type: 'ttf', file: `smashandclash:${name}.ttf`, size: 11, oversample: 6, shift: [0, name === 'display' ? 0.5 : 0] };
		await json(`font/${name}.json`, { providers: [provider, { type: 'reference', id: 'minecraft:default' }] });
	}

	// ---- data the mod reads: the deck (no URLs) and the parts of the kit it uses
	const deck = cards.map((c) => ({
		id: c.id,
		name: c.name,
		kind: c.kind,
		...(c.kind === 'character' ? { top: c.top, right: c.right, bottom: c.bottom, left: c.left, color: c.color, colorHex: c.colorHex } : { effect: c.effect, does: c.does }),
		art: !!c.art,
		animation: c.animation ? { pivot: c.animation.pivot, artScale: c.animation.artScale } : null,
		voice: !!a.voices[String(c.id)],
	}));
	await json('cards.json', deck);
	await json('kit.json', { terms: kit.terms, colors: kit.colors, sides: kit.card.sides, timing: kit.animation.timing, aspect: kit.card.aspect });
	console.log(`Done: ${Object.keys(sounds).length} sound events, ${cards.length} cards -> ${OUT}`);
}

/* ------------------------------ drawn textures ------------------------------ */

// The board top: a tan wooden tray with 15 slots (the overrun zones B2, C2, D2 hatched).
function drawBoard() {
	const { w, h, slotW, slotH, pitchX, pitchY, x0, y0 } = BOARD;
	const cv = new Canvas(w, h);
	const rnd = noise(7);
	const grain = Array.from({ length: h }, () => rnd());
	const wood = (x, y, base) => {
		const g = Math.sin(x / 23 + grain[y] * 6) * 0.5 + Math.sin(x / 7.3 + y / 40) * 0.25 + (grain[y] - 0.5) * 0.6;
		return [base[0] + g * 9, base[1] + g * 8, base[2] + g * 6, 255];
	};
	cv.roundRect(0, 0, w, h, 18, (x, y) => wood(x, y, [205, 168, 116]));
	cv.roundRect(10, 10, w - 20, h - 20, 12, (x, y) => wood(x, y, [226, 194, 146]));
	// it folds in half like the website's board: a seam down the middle and two brass hinges on the rim
	const mid = w / 2;
	cv.roundRect(mid - 2.5, 0, 5, h, 0, [120, 88, 50, 200]);
	cv.roundRect(mid - 1, 0, 2, h, 0, [245, 222, 180, 120]);
	for (const hy of [2, h - 16]) {
		cv.roundRect(mid - 20, hy, 40, 14, 3, [150, 112, 46, 255]);
		cv.roundRect(mid - 18, hy + 2, 36, 10, 2, [214, 172, 82, 255]);
		cv.roundRect(mid - 1, hy + 1, 2, 12, 0, [120, 84, 30, 255]);
	}
	for (let r = 0; r < 3; r++) {
		for (let c = 0; c < 5; c++) {
			// texture row 0 is the far edge (row 3); the board turns with the table
			const x = x0 + c * pitchX;
			const y = y0 + (2 - r) * pitchY;
			const overrun = r === 1 && c >= 1 && c <= 3;
			cv.roundRect(x - 2, y - 2, slotW + 4, slotH + 4, 10, [176, 140, 92, 255]);
			cv.roundRect(x, y, slotW, slotH, 8, (px, py) => {
				const base = wood(px, py, [212, 178, 128]);
				if (overrun && ((px + py) % 14 < 3)) return [base[0] - 26, base[1] - 30, base[2] - 30, 255];
				return base;
			});
			// a soft inner shadow along the top edge
			for (let i = 0; i < 5; i++) cv.roundRect(x, y + i, slotW, 1, 0, [120, 90, 50, 40 - i * 8]);
		}
	}
	return cv.png();
}

// The board's edge: a darker tan trim with a thin highlight line along the top.
function drawBoardSide() {
	const cv = new Canvas(64, 64);
	cv.roundRect(0, 0, 64, 64, 0, [176, 138, 88, 255]);
	cv.roundRect(0, 0, 64, 8, 0, [232, 204, 160, 255]);
	cv.roundRect(0, 56, 64, 8, 0, [138, 104, 62, 255]);
	return cv.png();
}

// The rim under a card: a white rounded plate the client tints blue (yours) or orange (theirs).
function drawRim() {
	const cv = new Canvas(64, 64);
	cv.roundRect(0, 0, 64, 64, 10, [255, 255, 255, 255]);
	return cv.png();
}

// A tile you can play: a rounded ring, solid at the edge, faint inside.
function drawGlow() {
	const cv = new Canvas(64, 64);
	cv.roundRect(0, 0, 64, 64, 12, [255, 255, 255, 255]);
	for (let y = 6; y < 58; y++) for (let x = 6; x < 58; x++) cv.px[(y * 64 + x) * 4 + 3] = 0.18;
	return cv.png();
}

main().catch((e) => {
	console.error(e);
	process.exit(1);
});
