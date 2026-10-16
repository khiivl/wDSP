#!/usr/bin/env python3
"""Regenerates .agents/CLASS_MAP.md - every class and native header, what it is, and what it offers.

Run from anywhere:  python tools/classmap.py

Everything in the map comes from the code itself: the first sentence of each class's doc comment and
of each non-private method's. So the map cannot drift from the code as long as it is regenerated
after a change - a class without a doc comment shows up as "(no class doc)", which is the cue to
write one in the class, not in the map.
"""
import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, 'wdsp_app', 'src', 'main')
OUT = os.path.join(REPO, '.agents', 'CLASS_MAP.md')

DOC_THEN_DECL = re.compile(r'/\*\*(.*?)\*/\s*(?:@\w+(?:\([^)]*\))?\s*)*([^;{]*?)\s*[{;]', re.S)
CLASS_RE = re.compile(r'\b(class|interface|enum)\s+(\w+)')
METHOD_RE = re.compile(r'^\s*(?:public|protected|static|final|synchronized|abstract|\s)*'
                       r'[\w<>\[\],.? ]+\s+(\w+)\s*\(([^)]*)\)')
NOT_METHODS = {'if', 'for', 'while', 'switch', 'return', 'new', 'catch', 'synchronized'}

HEADER = """# Class map — what every class is and what it offers

> **Generated** by `tools/classmap.py` from the code's own doc comments — do not edit by hand;
> fix the comment in the class and run the tool again. The overview (layers, main flows, where to
> start) is in [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md); the design in [ARCHITECTURE.md](ARCHITECTURE.md).

For each file: its size, the first sentence of its class comment, and its non-private methods with
the first sentence of theirs (at most 40 a file).
"""


def first_sentence(doc):
    text = re.sub(r'^\s*\*\s?', '', doc, flags=re.M)
    text = re.sub(r'<[^>]+>', '', text)
    text = re.sub(r'\{@(?:link|code|value)\s+([^}]+)\}', r'\1', text)
    text = ' '.join(text.split())
    m = re.match(r'(.+?[.!?])(\s|$)', text)
    return (m.group(1) if m else text)[:220]


def describe(path):
    src = open(path, encoding='utf-8', errors='replace').read()
    class_doc, methods = '', []
    for m in DOC_THEN_DECL.finditer(src):
        doc, decl = m.group(1), ' '.join(m.group(2).split())
        c = CLASS_RE.search(decl)
        if c and not class_doc and 'private' not in decl.split(c.group(1))[0]:
            class_doc = first_sentence(doc)
            continue
        mm = METHOD_RE.match(decl)
        if mm and 'private' not in decl and mm.group(1) not in NOT_METHODS:
            args = ' '.join(mm.group(2).split())
            methods.append('`%s(%s)` — %s' % (mm.group(1), args[:60], first_sentence(doc)))
    if not class_doc and path.endswith('.h'):
        # A header without a class: its first doc comment says what it holds.
        first = re.search(r'/\*\*(.*?)\*/', src, re.S)
        class_doc = first_sentence(first.group(1)) if first else ''
    return src.count('\n'), class_doc, methods


def main():
    out = [HEADER]
    for base, kind in (('java/com/radiorubka/wdsp', 'Java'), ('cpp', 'Native')):
        root = os.path.join(SRC, base)
        files = sorted(os.path.join(dp, f) for dp, _, fs in os.walk(root)
                       for f in fs if f.endswith(('.java', '.h')))
        out.append('## %s (`%s`)\n' % (kind, base))
        for p in files:
            lines, class_doc, methods = describe(p)
            rel = os.path.relpath(p, root).replace('\\', '/')
            out.append('### `%s` — %d lines' % (rel, lines))
            out.append(class_doc or '(no class doc)')
            out.extend('- ' + x for x in methods[:40])
            if len(methods) > 40:
                out.append('- … %d more' % (len(methods) - 40))
            out.append('')
    with open(OUT, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(out))
    print('wrote', os.path.relpath(OUT, REPO))


if __name__ == '__main__':
    main()
