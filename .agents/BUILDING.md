# Building this branch

The intent is that a fresh clone builds without anyone configuring anything. Clone, run the
wrapper, get an APK:

```bash
./gradlew :wdsp_app:assembleRelease
```

The first run downloads things and takes a while. Every run after that is fast.

## What is committed on purpose, and why

Three files that are often left out of a repository are deliberately in this one, because leaving
them out is what makes a clone need setting up by hand.

| file | what it does |
|---|---|
| `gradle/wrapper/` | pins **Gradle 9.4.0** and downloads it. Nothing to install |
| `settings.gradle` → `foojay-resolver-convention` | lets Gradle fetch a JDK by itself instead of demanding one already be installed |
| `gradle/gradle-daemon-jvm.properties` | says which JDK: **JetBrains Runtime 21**, with a download URL per platform |
| `gradle.properties` → `org.gradle.configuration-cache=true` | build speed only; no effect on output |

So the toolchain is provisioned rather than assumed. On a machine with no JDK at all this still
builds.

⚠️ **The first build downloads a JDK even if you already have one**, because the vendor is pinned
and the vendor is JetBrains. That is the price of every machine producing the same bytes. If you
would rather use your own, delete `toolchainVendor=JETBRAINS` from
`gradle/gradle-daemon-jvm.properties` and any JDK 21 will be accepted — but then a build that
works here is not proof that a build works there, which is the whole reason it is pinned.

## What is *not* committed, and must not be

`local.properties` — it holds the absolute path to the Android SDK on one particular machine.
It is in `.gitignore` and should stay there. Android Studio writes it on first open; from the
command line, set `ANDROID_HOME` instead.

## The native part

`wdsp_app/src/main/cpp` is built by CMake as part of the normal Gradle build — there is no separate
step and no prebuilt binary in the tree. It needs the NDK, which Gradle downloads on demand;
the version is pinned in `wdsp_app/build.gradle` (`ndkVersion`).

Two host test harnesses live in the same folder and are **not** part of the library, on purpose:

```bash
cd wdsp_app/src/main/cpp
g++ -O2 -std=c++17 -o /tmp/t_analyzer test_analyzer.cpp analyzer.cpp fft.cpp stitcher.cpp && /tmp/t_analyzer
g++ -O2 -std=c++17 -o /tmp/t_sweep    test_sweep.cpp    sweep.cpp analyzer.cpp fft.cpp stitcher.cpp && /tmp/t_sweep
```

They exist because a wrong answer from an audio measurement looks exactly as plausible as a right
one, and in a car there is nothing to check it against. On a synthetic signal there is. Between
them they have caught third-octave band centres a semitone out, an arrival detector biased by a
constant 202 samples, an inverse filter with its envelope upside down, and a band-power rule that
added 6 dB per octave of pure bookkeeping to every response.

## 🔴 Do not turn R8 on

`minifyEnabled false` and `shrinkResources false` in the release build are not an oversight.

This app reaches the head unit's hardware through reflection and `compileOnly` stubs — the MCU
service, the volume manager, system properties. To the optimiser those calls look side-effect free,
so it removes them. The debug build works, the signed release does nothing at all, and there is no
error anywhere to explain it. On the sibling project this cost days.

If it is ever turned on, it needs a complete `-keep` set for every reflected class *and* a test on
real hardware — not merely a build that compiles.

Related: `targetSdk 29` is also deliberate. Above it the hidden-API blocklist gets stricter.

More about the platform underneath all this is in [platform/INDEX.md](platform/INDEX.md).
