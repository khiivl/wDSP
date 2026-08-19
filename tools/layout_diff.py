# -*- coding: utf-8 -*-
"""Compares widget types for every id shared by two layout variants of the same screen.

A missing id costs a null check. The same id declared as a different widget type costs a
ClassCastException in onCreate - the screen simply never opens on whichever geometry picks the
other file.
"""
import io
import re
import sys


def widgets(path):
    s = io.open(path, encoding='utf-8').read()
    found = {}
    # every element start tag, with its attributes
    for m in re.finditer(r'<([A-Za-z][\w.]*)\s([^>]*?)/?>', s, re.S):
        tag, attrs = m.group(1), m.group(2)
        idm = re.search(r'android:id="@\+?id/([A-Za-z0-9_]+)"', attrs)
        if idm:
            found[idm.group(1)] = tag.split('.')[-1]
    return found


a_path, b_path = sys.argv[1], sys.argv[2]
a, b = widgets(a_path), widgets(b_path)

shared = sorted(set(a) & set(b))
mismatch = [(k, a[k], b[k]) for k in shared if a[k] != b[k]]

print('ids: %s=%d  %s=%d  спільних=%d' % (a_path.split('/')[-2], len(a),
                                          b_path.split('/')[-2], len(b), len(shared)))
if mismatch:
    print('\nРОЗБІЖНІСТЬ ТИПІВ (кожна - гарантований ClassCastException):')
    print('%-30s %-24s %s' % ('id', 'landscape', 'port'))
    for k, ta, tb in mismatch:
        print('%-30s %-24s %s' % (k, ta, tb))
else:
    print('\nтипи збігаються')

only_a = sorted(set(a) - set(b))
only_b = sorted(set(b) - set(a))
print('\nлише в landscape (%d): %s' % (len(only_a), ' '.join(only_a)))
print('\nлише в port (%d): %s' % (len(only_b), ' '.join(only_b)))
