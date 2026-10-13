"""Static Inter instances for JavaFX (which can't pick weights from a variable font)."""
import sys, shutil
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

src, jb_dir, out = sys.argv[1], sys.argv[2], sys.argv[3]

def name(font, family, style, weight, bold):
    n = font['name']
    full = family + ('' if style == 'Regular' else ' ' + style)
    ps = (family + '-' + style).replace(' ', '')
    for nid in (16, 17, 21, 22, 25):
        n.removeNames(nameID=nid)
    for rec_id, val in ((1, family), (2, style), (3, ps + ';4.001'), (4, full), (6, ps)):
        n.setName(val, rec_id, 3, 1, 0x409)
        n.removeNames(nameID=rec_id, platformID=1)
    os2 = font['OS/2']
    os2.usWeightClass = weight
    os2.fsSelection = (os2.fsSelection & ~0b1100001) | (0b100000 if bold else 0b1000000)
    font['head'].macStyle = 1 if bold else 0
    if 'STAT' in font:
        del font['STAT']

def freeze_tnum(font):
    gsub = font['GSUB'].table
    idx = {i for fr in gsub.FeatureList.FeatureRecord if fr.FeatureTag == 'tnum' for i in fr.Feature.LookupListIndex}
    mapping = {}
    for i in idx:
        lk = gsub.LookupList.Lookup[i]
        for st in lk.SubTable:
            if lk.LookupType == 7:
                st = st.ExtSubTable
            if hasattr(st, 'mapping'):
                mapping.update(st.mapping)
    for t in font['cmap'].tables:
        for cp, g in list(t.cmap.items()):
            if g in mapping:
                t.cmap[cp] = mapping[g]
    return len(mapping)

# (file, family, style, wght, bold-slot). The 600 instance fills the bold slot: JavaFX only asks for
# regular or bold, and semibold reads better than 700 at interface sizes.
specs = [
    ('Inter-Regular.ttf', 'Inter', 'Regular', 400, False, False),
    ('Inter-SemiBold.ttf', 'Inter', 'Bold', 600, True, False),
    ('InterTabular-Regular.ttf', 'Inter Tabular', 'Regular', 400, False, True),
    ('InterTabular-SemiBold.ttf', 'Inter Tabular', 'Bold', 600, True, True),
]
for fn, fam, style, w, bold, tab in specs:
    f = TTFont(src)
    inst = instancer.instantiateVariableFont(f, {'wght': w, 'opsz': 14})
    if tab:
        print(fn, 'tnum glyphs', freeze_tnum(inst))
    name(inst, fam, style, w, bold)
    inst.save(out + '/' + fn)
for fn in ('JetBrainsMono-Regular.ttf', 'JetBrainsMono-Bold.ttf'):
    shutil.copy(jb_dir + '/' + fn, out + '/' + fn)
