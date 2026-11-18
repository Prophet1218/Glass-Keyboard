#!/usr/bin/env python3
"""
Builds the keyboard's binary word lists (app/src/main/assets/hu.dict, en.dict).

Sources (not stored in this repo, clone them next to each other and pass the paths):
  * wordfreq data (https://github.com/rspeer/wordfreq, CC BY-SA 4.0):
      wordfreq/data/small_hu.msgpack.gz, wordfreq/data/large_en.msgpack.gz
  * FrequencyWords (https://github.com/hermitdave/FrequencyWords, CC BY-SA 4.0):
      content/2018/hu/hu_full.txt  (OpenSubtitles word counts)

Usage: build_dict.py WORDFREQ_DATA_DIR HU_FULL_TXT OUT_DIR

Steps: merge frequencies, keep only real-looking words, drop unaccented spellings that are
mostly typos of an accented word (jo → jó), drop apostrophe-less English forms (dont → don't)
and known misspellings, then write a frequency trie (format documented in Dict.java).
"""
import math, os, re, struct, sys, unicodedata
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wordfreq_msgpack as mp

HU_RE = re.compile(r"^[a-zäáéíóöőúüű]+(-[a-záéíóöőúüű]+)?$")
EN_RE = re.compile(r"^[a-z]+('[a-z]+)?$")

EN_MISSPELLINGS = set("""definately alot recieve recieved wierd thier untill seperate seperately occured
accomodate beleive goverment tommorow tommorrow tomorow wich becuase becasue happend teh hte adn
truely arguement acheive begining beggining calender cemetary concious enviroment existance
finaly foriegn freind gaurd grammer harrass independant knowlege liason libary lisence
neccessary noticable occurence persue posession prefered publically realy reccomend refered
relevent religous rember remeber sieze similiar sincerly suprise tatoo threshhold tounge
truley wether wheather wierdly writting youself thru tho accomodation accomodations accomodating""".split())

HU_MISSPELLINGS = set("""nemtudom nemtom nemtudok nemtudod nemkell nemvagyok hogyvagy hogyvagytok
hogyvan mindjártjövök mostmár egyátalán ennyiaz szivesen szívessen elöször
eggyütt egyenlőre kosznom kösznöm koszonom köszönom kőszőnőm""".split())

def deacc(w):
    return ''.join(c for c in unicodedata.normalize('NFD', w) if not unicodedata.combining(c)).replace("'", "")

def filt_accent(freq):
    groups = {}
    for w, f in freq.items():
        groups.setdefault(deacc(w), []).append((f, w))
    out = {}
    for lst in groups.values():
        lst.sort(reverse=True)
        top = lst[0][0]
        for f, w in lst:
            if w != lst[0][1] and deacc(w) == w and top >= 40 * f:
                continue
            out[w] = f
    return out

def hungarian(wf_dir, subs):
    wf = mp.load(os.path.join(wf_dir, 'small_hu.msgpack.gz'))
    sub, tot = {}, 0
    for line in open(subs, encoding='utf-8'):
        p = line.split()
        if len(p) != 2:
            continue
        # old subtitles use Latin-1 look-alikes for ő/ű
        w = p[0].lower().replace('õ', 'ő').replace('û', 'ű').replace('ô', 'ő').replace('ũ', 'ű')
        c = int(p[1]); tot += c
        if c >= 6:
            sub[w] = sub.get(w, 0) + c
    res = {}
    for w, c in sub.items():
        if not HU_RE.match(w):
            continue
        f = c / tot
        if w in wf:
            f = math.sqrt(f * wf[w])
        else:
            if c < 12:
                continue
            f *= 0.5
        res[w] = f
    for w, f in wf.items():
        if w not in res and HU_RE.match(w):
            res[w] = f * 0.7
    for w in HU_MISSPELLINGS:
        res.pop(w, None)
    return filt_accent(res)

def english(wf_dir):
    wf = mp.load(os.path.join(wf_dir, 'large_en.msgpack.gz'))
    res = {w: f for w, f in wf.items() if EN_RE.match(w) and f >= 2e-8 and w not in EN_MISSPELLINGS}
    res = filt_accent(res)
    apos = {}
    for w, f in res.items():
        if "'" in w:
            k = w.replace("'", "")
            apos[k] = max(apos.get(k, 0), f)
    res = {w: f for w, f in res.items() if "'" in w or w not in apos or apos[w] < 3 * f}
    best = sorted(res.items(), key=lambda x: -x[1])[:160000]
    return dict(best)

def fbyte(f):
    return max(1, min(255, 1 + round((math.log10(f) + 9.0) * 31)))

def write_trie(words, out):
    root = {'c': {}, 'f': 0}
    for w, f in words.items():
        n = root
        for ch in w:
            n = n['c'].setdefault(ch, {'c': {}, 'f': 0})
        n['f'] = max(n['f'], fbyte(f))
    # best frequency in each subtree (iterative post-order)
    stack = [(root, False)]
    while stack:
        n, done = stack.pop()
        if done:
            n['m'] = max([n['f']] + [c['m'] for c in n['c'].values()])
        else:
            stack.append((n, True))
            stack.extend((c, False) for c in n['c'].values())
    order = [root]; root['ch'] = '\0'
    i = 0
    while i < len(order):
        n = order[i]; i += 1
        kids = sorted(n['c'].items(), key=lambda kv: -kv[1]['m'])
        n['first'] = len(order); n['cnt'] = len(kids)
        for k, c in kids:
            c['ch'] = k
            order.append(c)
    buf = bytearray(b'GKD1' + struct.pack('>I', len(order)))
    for n in order:
        buf += struct.pack('>HBB', ord(n['ch']), n['f'], n['m']) + n['first'].to_bytes(3, 'big') + bytes([n['cnt']])
    open(out, 'wb').write(buf)
    print(out, len(words), 'words', len(order), 'nodes', len(buf), 'bytes')

if __name__ == '__main__':
    wf_dir, subs, out = sys.argv[1], sys.argv[2], sys.argv[3]
    write_trie(hungarian(wf_dir, subs), os.path.join(out, 'hu.dict'))
    write_trie(english(wf_dir), os.path.join(out, 'en.dict'))
