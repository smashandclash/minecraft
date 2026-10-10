// Turn on Beta APIs (the "gametest" experiment) in a Bedrock world's level.dat:
// @minecraft/server-net, which carries the game's HTTP, is a beta module.
//   node tools/beta.mjs "<world folder>"
import nbt from 'prismarine-nbt';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const file = join(process.argv[2], 'level.dat');
const buf = readFileSync(file);
const header = buf.subarray(0, 8);
const { parsed } = await nbt.parse(buf.subarray(8), 'little');
const v = parsed.value;
v.experiments ??= { type: 'compound', value: {} };
v.experiments.value.gametest = { type: 'byte', value: 1 };
v.experiments.value.experiments_ever_used = { type: 'byte', value: 1 };
v.experiments.value.saved_with_toggled_experiments = { type: 'byte', value: 1 };
const body = nbt.writeUncompressed(parsed, 'little');
const head = Buffer.from(header);
head.writeInt32LE(body.length, 4);
writeFileSync(file, Buffer.concat([head, body]));
console.log('Beta APIs on:', file);
