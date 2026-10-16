"""Microphone spectrum minus calculated spectrum, against the cabin response the sweep measured.

Both spectra are rebuilt from the analyser's own dump lines, exactly as it draws them: the power
averaged over the frames, the noise floor taken off only for the microphone (power - 1.2 x floor,
linear), the curve added in dB, then folded onto the sixteen equaliser bands (centre band + half of
each neighbour, the 20 kHz band x4/3). Each is then referred to its own 200-800 Hz mean - the
reference the cabin response is stored against.
"""
import re, sys
import numpy as np, os
MEDIAN = os.environ.get("MEDIAN") == "1"

HW = ["20", "31.5", "50", "80", "125", "200", "315", "500", "800", "1250", "2000", "3150", "5000",
      "8000", "12500", "20000"]


def read(path):
    rows = {"POWER32": [], "FLOOR32": [], "CURVE32": []}
    for line in open(path, encoding="utf-8", errors="replace"):
        for key in rows:
            m = re.search(key + r" (.*)$", line)
            if m:
                rows[key].append([float(v) for v in m.group(1).split()])
    return {k: np.array(v) for k, v in rows.items()}


def display32(d, acoustic):
    p = (np.median if MEDIAN else np.mean)(10 ** (d["POWER32"] / 10), axis=0)
    if acoustic:
        f = 10 ** (d["FLOOR32"][-1] / 10)
        f[d["FLOOR32"][-1] <= -119] = 0
        p = np.maximum(p - 1.2 * f, 1e-12)
    return 10 * np.log10(p) + d["CURVE32"][-1]


def fold16(db32):
    lin = 10 ** (db32 / 10)
    out = []
    for i in range(16):
        c = 2 * i + 1
        s = lin[c] + 0.5 * lin[c - 1]
        s = s + 0.5 * lin[c + 1] if c + 1 < 32 else s * 4 / 3
        out.append(10 * np.log10(s))
    return np.array(out)


calc, mic = read(sys.argv[1]), read(sys.argv[2])
cabin = np.array([float(v) for v in sys.argv[3].split(",")])
noise = read(sys.argv[4]) if len(sys.argv) > 4 else None

c16 = fold16(display32(calc, False))
m16 = fold16(display32(mic, True))
cRef, mRef = c16[5:9].mean(), m16[5:9].mean()
diff = (m16 - mRef) - (c16 - cRef)
print(f"frames: calc {len(calc['POWER32'])}, mic {len(mic['POWER32'])}; "
      f"reference 200-800 Hz: calc {cRef:.1f} dBFS, mic {mRef:.1f} dBFS (mic ADC)")
print(f"mic curve (compensation) 16: {' '.join(f'{v:+.1f}' for v in mic['CURVE32'][-1][1::2])}")
print(f"mic floor 32 (dB): {' '.join(f'{v:.0f}' for v in mic['FLOOR32'][-1])}")
hdr = f"{'band':>6} {'calc':>7} {'mic':>7} {'mic-calc':>9} {'cabin':>7} {'miss':>6}"
if noise is not None:
    n16 = fold16(10 * np.log10((np.median if MEDIAN else np.mean)(10 ** (noise['POWER32'] / 10), axis=0)))
    raw16 = fold16(10 * np.log10((np.median if MEDIAN else np.mean)(10 ** (mic['POWER32'] / 10), axis=0)))
    hdr += f" {'SNR':>6}"
print(hdr)
for i in range(16):
    row = (f"{HW[i]:>6} {c16[i] - cRef:+7.1f} {m16[i] - mRef:+7.1f} {diff[i]:+9.1f} "
           f"{cabin[i]:+7.1f} {diff[i] - cabin[i]:+6.1f}")
    if noise is not None:
        row += f" {raw16[i] - n16[i]:6.1f}"
    print(row)
ok = np.abs(diff - cabin)
print(f"|mic-calc - cabin|: median {np.median(ok):.1f} dB, max {ok.max():.1f} dB at {HW[int(ok.argmax())]} Hz; "
      f"within 2 dB: {(ok <= 2).sum()} of 16")
