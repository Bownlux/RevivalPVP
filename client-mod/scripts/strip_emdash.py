import os, sys

mod_dir = os.path.join(os.path.dirname(__file__), '..', 'src')
mod_dir = os.path.abspath(mod_dir)
EMDASH = '—'  # em-dash codepoint

total = 0
files_touched = 0
for root, _, files in os.walk(mod_dir):
    for f in files:
        if not f.endswith('.java'):
            continue
        p = os.path.join(root, f)
        with open(p, encoding='utf-8') as fh:
            t = fh.read()
        if EMDASH not in t:
            continue
        count = t.count(EMDASH)
        # " — " -> ", "  (spaced em-dash in prose, the dominant pattern)
        new = t.replace(' ' + EMDASH + ' ', ', ')
        # " —" -> ","   (trailing em-dash)
        new = new.replace(' ' + EMDASH, ',')
        # "— " -> ", "  (leading em-dash)
        new = new.replace(EMDASH + ' ', ', ')
        # Anything that survives (naked, doubled, etc.) -> hyphen.
        new = new.replace(EMDASH, '-')
        with open(p, 'w', encoding='utf-8') as fh:
            fh.write(new)
        total += count
        files_touched += 1
        print(f'{os.path.relpath(p, mod_dir)}: {count}')
print(f'\nTOTAL: {total} em-dashes replaced across {files_touched} files')
