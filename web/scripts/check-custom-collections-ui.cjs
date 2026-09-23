const path = require('node:path');
const fs = require('node:fs');
const http = require('node:http');
const assert = require('node:assert/strict');
const esbuild = require('esbuild');
const { chromium } = require('@playwright/test');

(async () => {
  const root = path.resolve(__dirname, '..');
  const out = path.join(root, '.custom-collections-ui-test');
  fs.mkdirSync(out, { recursive: true });
  await esbuild.build({
    stdin: { contents: `import React from 'react'; import {createRoot} from 'react-dom/client';
      import {CustomCollectionRail} from './components/media/CustomCollectionRail';
      import {parseCustomCollections} from './lib/customCollections';
      parseCustomCollections(JSON.stringify({title:'My collections',folders:[{title:'Pixar',coverImageUrl:'/cover.jpg',sources:[{provider:'tmdb',tmdbSourceType:'COMPANY',tmdbId:3}]},{title:'Independent films and international favourites',tileShape:'POSTER',sources:[{provider:'tmdb',tmdbSourceType:'LIST',tmdbId:99}]}]})).then(c =>
        createRoot(document.getElementById('root')).render(<CustomCollectionRail catalog={c[0]} folders={c.slice(1)} onOpen={i=>document.getElementById('opened').textContent=i.title}/>));`,
      resolveDir: root, loader: 'tsx' },
    bundle: true, outfile: path.join(out, 'app.js'), jsx: 'automatic', define: { 'process.env.NODE_ENV': '"test"' },
    plugins: [{ name: 'offline-adapters', setup(build) {
      build.onResolve({ filter: /^@\/lib\/(store|i18n|tmdb|imdbRatings)$/ }, args => ({ path: args.path, namespace: 'fixture' }));
      build.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: args.path.endsWith('i18n')
        ? `export const useTranslation=()=>s=>s;`
        : args.path.endsWith('tmdb') ? `export const getLogoUrl=async()=>null;export const getCardProviders=async()=>[];export const getCardMeta=async()=>({});export const resolveTmdbId=async()=>1;export const prefetchDetails=()=>{};`
        : args.path.endsWith('imdbRatings') ? `export const getImdbRating=async()=>null;`
        : `export const useApp=()=>({settings:{language:'en'},isWatched:()=>false,openContextMenu:()=>{},loadCatalogRow:async c=>({id:c.id,title:c.name,items:location.search.includes('empty')?[]:[{id:1,title:'Test movie',mediaType:'movie',image:'/cover.jpg',backdrop:'/cover.jpg'}]})});` }));
      build.onResolve({ filter: /^@\// }, args => ({ path: ['.tsx', '.ts', '/index.tsx', '/index.ts'].map(ext => path.join(root, args.path.slice(2) + ext)).find(fs.existsSync) }));
    } }]
  });
  const server = http.createServer((req, res) => {
    if (req.url === '/app.js') { res.setHeader('content-type', 'application/javascript'); return res.end(fs.readFileSync(path.join(out, 'app.js'))); }
    if (req.url === '/globals.css') { res.setHeader('content-type', 'text/css'); return res.end(fs.readFileSync(path.join(root, 'app/globals.css'))); }
    if (req.url === '/cover.jpg') { res.setHeader('content-type', 'image/jpeg'); return res.end(fs.readFileSync(path.join(root, '../app/src/androidTest/assets/library/120467-backdrop.jpg'))); }
    res.setHeader('content-type', 'text/html');
    res.end('<meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/globals.css"><div id="root"></div><output id="opened"></output><script src="/app.js"></script>');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    for (const [width, height] of [[1920,1080],[1024,768],[390,844]]) {
      const page = await browser.newPage({ viewport: { width,height } });
      const errors = []; page.on('pageerror', e => errors.push(e.message));
      await page.goto(`http://127.0.0.1:${server.address().port}`);
      await page.getByRole('button', { name: 'Pixar', exact: true }).focus();
      await page.keyboard.press('ArrowRight');
      assert.match(await page.locator(':focus').getAttribute('aria-label'), /Independent films/);
      await page.keyboard.press('ArrowLeft');
      await page.screenshot({ path: path.join(out, `rail-${width}.png`) });
      await page.getByRole('button', { name: 'Pixar', exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Test movie' }).waitFor();
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      await page.screenshot({ path: path.join(out, `collection-${width}.png`) });
      await page.keyboard.press('Escape');
      assert.equal(await page.getByRole('dialog').count(), 0);
      await page.getByRole('button', { name: 'Pixar', exact: true }).click();
      await page.getByRole('button', { name: 'Test movie' }).click();
      assert.equal(await page.locator('#opened').textContent(), 'Test movie');
      assert.equal(await page.getByRole('dialog').count(), 0);
      await page.evaluate(() => localStorage.clear());
      await page.goto(`http://127.0.0.1:${server.address().port}/?empty=1`);
      await page.getByRole('button', { name: 'Pixar', exact: true }).click();
      await page.getByText('No titles found').waitFor();
      await page.getByRole('button', { name: 'Retry', exact: true }).click();
      await page.getByText('No titles found').waitFor();
      await page.screenshot({ path: path.join(out, `empty-${width}.png`) });
      assert.deepEqual(errors, []);
      await page.close(); console.log(`${width}x${height}: passed`);
    }
  } finally { await browser.close(); server.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
