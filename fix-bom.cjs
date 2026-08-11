// Strip BOM and normalize all JSON files in .understand-anything
const fs = require('fs');
const path = require('path');
const dir = process.argv[2];

function fixFile(fp) {
  const raw = fs.readFileSync(fp);
  // Strip UTF-8 BOM if present
  let content = raw;
  if (raw[0] === 0xEF && raw[1] === 0xBB && raw[2] === 0xBF) {
    content = raw.subarray(3);
  }
  const text = content.toString('utf8');
  // Validate JSON, then rewrite without BOM
  JSON.parse(text); // throws if invalid
  fs.writeFileSync(fp, JSON.stringify(JSON.parse(text), null, 2), 'utf8');
  console.log('Fixed:', path.basename(fp));
}

for (const f of fs.readdirSync(dir)) {
  if (f.endsWith('.json')) {
    fixFile(path.join(dir, f));
  }
}
