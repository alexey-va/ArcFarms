#!/usr/bin/env node
// Compile from canonical Kotlin export plus Atelier sources; never modify a generated HTML by hand.
import {mkdir,readFile,writeFile,mkdtemp} from 'node:fs/promises';
import {resolve,join,dirname} from 'node:path';
import {pathToFileURL,fileURLToPath} from 'node:url';
import {tmpdir} from 'node:os';
import {spawnSync} from 'node:child_process';
const [atelier,recipe,out,...options]=process.argv.slice(2);
if(!atelier||!recipe||!out)throw Error('Usage: node scripts/mine-preview/build.mjs <location-atelier-dir> <recipe> <output>');
const root=resolve(atelier),own=dirname(fileURLToPath(import.meta.url));
let clientJar=null;
const cliOptions=[];
for(let i=0;i<options.length;i++) {
 const option=options[i];
 if(option==='--client-jar') {
  if(clientJar||!options[i+1]||options[i+1].startsWith('--'))throw Error('--client-jar requires one JAR path');
  clientJar=resolve(options[++i]);
  cliOptions.push('--client-jar',clientJar);
 } else if(option.startsWith('--client-jar=')) {
  if(clientJar||!option.slice('--client-jar='.length))throw Error('--client-jar requires one JAR path');
  clientJar=resolve(option.slice('--client-jar='.length));
  cliOptions.push('--client-jar',clientJar);
 } else cliOptions.push(option);
}
const outDir=resolve(out);
const previewRecipe=JSON.parse(await readFile(resolve(recipe),'utf8'));
const hasDieselEffects=Boolean(previewRecipe.dieselEffects && previewRecipe.displayAssemblies?.some(({model})=>model==='factory_diesel_generator'));
let dieselEffectAssetsAvailable=false;
if(hasDieselEffects&&clientJar) {
 const assetDir=join(outDir,'diesel-effects');
 await mkdir(assetDir,{recursive:true});
 const assets=[
  ['assets/minecraft/textures/particle/flame.png','flame.png'],
  ['assets/minecraft/textures/particle/big_smoke_0.png','big_smoke_0.png'],
 ];
 for(const [jarPath,fileName] of assets) {
  const extracted=spawnSync('unzip',['-p',clientJar,jarPath],{encoding:null,maxBuffer:4*1024*1024});
  if(extracted.error)throw extracted.error;
  const pngSignature=Buffer.from([0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a]);
  if(extracted.status!==0||!extracted.stdout?.subarray(0,8).equals(pngSignature))
   throw Error(`Could not extract ${jarPath} from ${clientJar}`);
  await writeFile(join(assetDir,fileName),extracted.stdout);
 }
 dieselEffectAssetsAvailable=true;
}
let source=await readFile(join(root,'cli.mjs'),'utf8');
source=source.replaceAll(/from '(\.\/[^']+)'/g,(_,p)=>`from ${JSON.stringify(pathToFileURL(resolve(root,p)).href)}`)
 .replace('createRequire(import.meta.url)',`createRequire(${JSON.stringify(pathToFileURL(join(root,'cli.mjs')).href)})`)
 .replace('const ROOT = dirname(fileURLToPath(import.meta.url));',`const ROOT = ${JSON.stringify(root)};`)
 .replace("const app = await source('app.js');",`const app = await source('app.js') + '\\n' + ${JSON.stringify(`const DIESEL_EFFECT_ASSETS_AVAILABLE = ${dieselEffectAssetsAvailable};`)} + '\\n' + await readFile(${JSON.stringify(join(own,'diesel-effects.js'))},'utf8') + '\\n' + await readFile(${JSON.stringify(join(own,'displays.js'))},'utf8');`);
const scratch=await mkdtemp(join(tmpdir(),'mine-preview-'));
const cli=join(scratch,'compile.mjs');await writeFile(cli,source);
const result=spawnSync(process.execPath,[cli,resolve(recipe),'--out',outDir,...cliOptions],{stdio:'inherit'});
process.exit(result.status??1);
