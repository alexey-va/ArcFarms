#!/usr/bin/env node
// Compile from canonical Kotlin export plus Atelier sources; never modify a generated HTML by hand.
import {readFile,writeFile,mkdtemp} from 'node:fs/promises';
import {resolve,join,dirname} from 'node:path';
import {pathToFileURL,fileURLToPath} from 'node:url';
import {tmpdir} from 'node:os';
import {spawnSync} from 'node:child_process';
const [atelier,recipe,out]=process.argv.slice(2);
if(!atelier||!recipe||!out)throw Error('Usage: node scripts/mine-preview/build.mjs <location-atelier-dir> <recipe> <output>');
const root=resolve(atelier),own=dirname(fileURLToPath(import.meta.url));
let source=await readFile(join(root,'cli.mjs'),'utf8');
source=source.replaceAll(/from '(\.\/[^']+)'/g,(_,p)=>`from ${JSON.stringify(pathToFileURL(resolve(root,p)).href)}`)
 .replace('createRequire(import.meta.url)',`createRequire(${JSON.stringify(pathToFileURL(join(root,'cli.mjs')).href)})`)
 .replace('const ROOT = dirname(fileURLToPath(import.meta.url));',`const ROOT = ${JSON.stringify(root)};`)
 .replace("const app = await source('app.js');",`const app = await source('app.js') + '\\n' + await readFile(${JSON.stringify(join(own,'displays.js'))},'utf8');`);
const scratch=await mkdtemp(join(tmpdir(),'mine-preview-'));
const cli=join(scratch,'compile.mjs');await writeFile(cli,source);
const result=spawnSync(process.execPath,[cli,resolve(recipe),'--out',resolve(out)],{stdio:'inherit'});
process.exit(result.status??1);
