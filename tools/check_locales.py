#!/usr/bin/env python3
"""Checks every locale against the English master before a build reaches anyone.

Run from anywhere:  python tools/check_locales.py    (exit code 1 if anything is wrong)

Three faults this catches, all of which have actually happened in this project:

1. A bare "%1" where Java needs "%1$d". aapt2 accepts it and the app crashes at the call
   site with UnknownFormatConversionException - seven strings in two Russian locales, found
   only after the build had gone to testers.
2. A placeholder that the caller passes and the translation does not carry. Nothing crashes;
   the value simply never appears, so a status line reads "Done" forever and the preset name
   it was supposed to show is silently dropped.
3. An apostrophe that is not backslash-escaped. This one aapt2 does refuse, so it cannot
   reach a device - it is checked here only so the failure is named in a readable line
   instead of a resource-compiler stack.

Written as a file on purpose. The inline version of this check, passed through PowerShell
into `python -c`, lost its backslashes and reported thirty healthy files as broken - see
memory my-recurring-traps, "інструмент бреше тихо". A checker that needs checking is worse
than none, so this one always prints what it compared, and the master itself goes through the
same rules as every translation.
"""
import os
import re
import sys
import glob
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', 'wdsp_app', 'src', 'main', 'res')

# A full Java/Android format specifier: %[argument$][flags][width][.precision]conversion
SPEC = re.compile(r'%(?:(\d+)\$)?([-+ 0,(#]*)(\d*)(?:\.(\d+))?([a-zA-Z%])')


def strings_of(path):
    """name -> raw inner text, skipping anything marked untranslatable."""
    out = {}
    raw = open(path, encoding='utf-8').read()
    for m in re.finditer(r'<string ([^>]*?)>(.*?)</string>', raw, re.S):
        attrs, body = m.group(1), m.group(2)
        name = re.search(r'name="([^"]+)"', attrs)
        if not name or 'translatable="false"' in attrs:
            continue
        out[name.group(1)] = body
    return out


def specs_in(body):
    """The specifiers of a string, as comparable tokens, plus anything malformed.

    Scanned left to right rather than per "%" character. The first version of this looked at
    every per cent sign independently and so read the *second* half of an escaped "%%" as a
    broken specifier, which made it condemn the English master itself - 204 complaints, six of
    them against the file that defines what correct means.
    """
    tokens, broken = [], []
    i = 0
    while True:
        at = body.find('%', i)
        if at < 0:
            break
        if body.startswith('%%', at):       # a literal per cent: both characters belong to it
            i = at + 2
            continue
        got = SPEC.match(body, at)
        if got:
            tokens.append('%s:%s%s' % (got.group(1) or '-', got.group(4) or '', got.group(5)))
            i = got.end()
            continue
        # A per cent followed by digits pretends to be a positional argument and is not one:
        # "%1" instead of "%1$d" is what crashed the Russian build. A per cent followed by
        # anything else - ")", a space, the end of the line - is the per cent sign itself, which
        # aapt2 and String.format both accept, so it is left alone. "40%" and "(~50%)" are text.
        if re.match(r'%\d', body[at:]):
            broken.append(body[at:at + 6])
        i = at + 1
    return sorted(tokens), broken


def unescaped_apostrophes(body):
    return [body[max(0, m.start() - 16):m.start() + 10]
            for m in re.finditer(r"'", body)
            if m.start() == 0 or body[m.start() - 1] != '\\']


def main():
    master_path = os.path.join(RES, 'values', 'strings.xml')
    master = strings_of(master_path)
    problems = 0
    checked = 0

    for path in sorted(glob.glob(os.path.join(RES, 'values*', 'strings.xml'))):
        loc = os.path.basename(os.path.dirname(path))
        try:
            ET.parse(path)
        except Exception as exc:
            print('XML BROKEN   %-16s %s' % (loc, exc))
            problems += 1
            continue

        here = strings_of(path)
        checked += 1
        for key, body in sorted(here.items()):
            for quote in unescaped_apostrophes(body):
                print("APOSTROPHE   %-16s %-30s ...%s..." % (loc, key, quote))
                problems += 1

            mine, broken = specs_in(body)
            for bad in broken:
                print('NOT A FORMAT %-16s %-30s %r is not a Java conversion' % (loc, key, bad))
                problems += 1

            if loc == 'values' or key not in master:
                continue
            theirs, _ = specs_in(master[key])
            if mine != theirs:
                print('PLACEHOLDER  %-16s %-30s master %s, this locale %s'
                      % (loc, key, theirs or '[]', mine or '[]'))
                problems += 1

    print('compared %d locale files against %s (%d translatable keys in the master)'
          % (checked, os.path.relpath(master_path, HERE), len(master)))
    print('problems: %d' % problems)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
