// Optional local visual QA of the exact Android notification vector at status-bar sizes.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '..');
const xml = fs.readFileSync(path.join(root, 'app/src/main/res/drawable/ic_notification.xml'), 'utf8');
const attribute = (tag, name) => new RegExp(`android:${name}="([^"]+)"`).exec(tag)?.[1];
const paths = [...xml.matchAll(/<path[\s\S]*?\/>/g)].map(([tag]) => {
  const data = attribute(tag, 'pathData');
  const fill = attribute(tag, 'fillColor') === '#00000000' ? 'none' : '#fff';
  const stroke = attribute(tag, 'strokeColor') ? '#fff' : 'none';
  const width = attribute(tag, 'strokeWidth') || '0';
  return `<path d="${data}" fill="${fill}" stroke="${stroke}" stroke-width="${width}" stroke-linejoin="round"/>`;
});
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">${paths.join('')}</svg>`;
const destination = path.join(root, 'build/preview');
fs.mkdirSync(destination, { recursive: true });
Promise.all([24, 48].map(size => sharp(Buffer.from(svg)).resize(size, size).png()
  .toFile(path.join(destination, `ic_notification-${size}.png`))))
  .catch(error => { console.error(error); process.exitCode = 1; });
