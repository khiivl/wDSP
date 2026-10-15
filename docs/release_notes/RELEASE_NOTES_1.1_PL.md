wDSP — 1.1 "Bulldozer" (kompilacja 26)
Zmiany od 0.4.9.8, poprzedniego wydania z 13.09.2026
=====================================================

To wydanie to moja modyfikacja wDSP autorstwa Volodymyra Chebanenko (khiivl),
https://github.com/khiivl/wDSP, zawierająca wszystkie jego prace aż do wersji 1.0.2.

INSTALACJA

- Na poprzednią wersję moda: zainstaluj plik APK na wierzch; presety i ustawienia zostają zachowane.
- Na wersję autora: obie są podpisane innymi kluczami i Android nie zainstaluje jednej na drugą.
  Zapisz presety za pomocą kopii zapasowej w aplikacji, odinstaluj wersję autora, a następnie zainstaluj mod.
- Otwórz wDSP jeden raz po instalacji.

Instrukcje (dziewięć języków, w tym mapa gestów wygaszacza) znajdują się w archiwum. Otwierają się
w dowolnej przeglądarce bez dostępu do Internetu.

-----------------------------------------------------------------------------
NOWOŚCI
-----------------------------------------------------------------------------

Z APLIKACJI AUTORA (khiivl, do jego 1.0.2) — wszystko to jest też w modzie
- Ultra Bass, nowa funkcja Loudness, krzywa analizatora na żywo za korektorem, "Wył." na liście
  subwoofera dla aut bez niego, GALA wyłączana dla pojedynczego presetu.
- Zakładka "Test Audio": sygnały testowe i nagrania kontrolne dla każdego głośnika.

TYLKO W MODZIE — aplikacja autora tego nie robi
- Mierzy samochód: sweepy przez każdy głośnik, nagrane mikrofonem radia, a z tego, co usłyszy —
  preset, opóźnienia i lista problemów.
- Jego widmo potrafi słuchać kabiny przez mikrofon, a nie tylko pokazywać muzykę.
- Wizualizator na pasku stanu i pełnoekranowy wygaszacz ze sterowaniem odtwarzaczem.
- Dba o dźwięk nie tylko korektorem: rozmowy, głośność każdego źródła, wznawianie
  odtwarzacza po restarcie lub uśpieniu.
- Działa na zdarzeniach, a nie według zegara: usługa autora sprawdza głośność i odtwarzacz
  co 200 ms, mod reaguje tylko wtedy, gdy coś naprawdę się zmieni.
- Dwa interfejsy: Klasyczny (wygląd autora) i własny Nowoczesny moda.

Nowe w modzie w 1.1:

Interfejs
- Dwa style interfejsu w Ustawienia → "Styl interfejsu": Klasyczny (wygląd autora) i Nowoczesny.
  Motyw dzienny i nocny w obu.
- Blokada par dla opóźnień: "Przednie razem" i "Tylne razem" zachowują różnicę między lewym
  a prawym.

Pomiar kabiny
- Ustawienia → karta kabiny: wskaż rodzaj kabiny, jej wymiary i dotknij zainstalowanych głośników.
- Aplikacja sama dobiera głośność pomiaru.
- Po naciśnięciu "Apply Auto-EQ" krótki sweep kontrolny pokazuje, czy korekta przyniosła skutek.
- Raport zaczyna się od listy usterek w prostych słowach — na przykład za cichy głośnik, odwrócona
  polaryzacja tweetera, zamienione kanały, subwoofer grający w przeciwfazie z drzwiami (ze wskazówką
  przełączenia jego fazy). Problemy, których korektor nie może rozwiązać, są zgłaszane zamiast być
  "korygowane".

Widmo
- Przełącznik na ekranie głównym: "Wył.", "Muzyka" (co preset robi z muzyką), "Microphone" (jak
  naprawdę brzmi kabina).
- "Zero RTA" w Test Audio kalibruje widmo jednym naciśnięciem.
- Ostrzeżenie o przesterowaniu mikrofonu: zmniejsz jego czułość w ustawieniach samochodu.

Wznawianie odtwarzacza
- Ustawienia: "Wznów odtwarzacz po restarcie" oraz "Wznów odtwarzacz po uśpieniu". Grający odtwarzacz
  powraca sam, bez wyskakiwania jego okna na wierzch nawigacji.
- Dla uśpienia naciśnij "Pozwól wDSP działać w uśpieniu" i włącz wDSP na liście, która się otworzy.

Wygaszacz
- Przeciągnięcie od środka ekranu zmienia głośność.
- Przytrzymanie przycisku stylu przez sekundę otwiera korektor.
- Dotknięcie okładki otwiera ten odtwarzacz lub radio, które rzeczywiście gra.
- Przełącznik "Zakrywaj pasek stanu".

-----------------------------------------------------------------------------
NAPRAWIONO
-----------------------------------------------------------------------------

Autor (khiivl)
- Poziom subwoofera po włączeniu: wyścig przy starcie ustawiał go błędnie (jego 1.0.1).

Mod
- Rozmowy: preset nie skacze już w trakcie połączenia, mikrofon jest zwalniany natychmiast, a głośność
  po rozmowie jest prawidłowa.
- Start: ustawienia niezawodnie docierają do procesora dźwięku po włączeniu i po aktualizacji.
- Wygaszacz pojawia się teraz na stacjach z włączonym "pływającym wideo" lub "pływającą nawigacją",
  nie zasłania nawigacji i działa na ekranach pionowych.
- Ekran korektora i okna dialogowe mieszczą się na wąskich i pionowych ekranach.
- Widmo mikrofonu po uśpieniu oraz po użyciu mikrofonu przez asystenta głosowego.
- Kilka błędów w pomiarze kabiny, szczególnie na najniższym basie i w kanale subwoofera.
- GALA nie pozostawia już podwyższonej głośności po zwolnieniu prędkości.

-----------------------------------------------------------------------------
PROŚBA O PRZESŁANIE
-----------------------------------------------------------------------------

Wersja autora nie posiada pomiaru kabiny, dlatego pomiary trafiają do grupy moda.

1. Po pomiarze kabiny: archiwum pomiaru ("Zapisz archiwum") i raport systemowy (Diagnostyka →
   "Raport systemowy"), oba w Download/wDSP, oraz kilka słów o tym, jak zmienił się dźwięk i scena.
2. Jeśli wygaszacz nigdy się nie pojawia: Diagnostyka → "Screen Topology" → zapisz i prześlij.
3. Czy wznawianie odtwarzacza u Ciebie zadziałało i z jakim odtwarzaczem.

Grupa moda: https://t.me/kostyamat_dev/92
Oryginalny projekt: https://t.me/wDSPapp, https://github.com/khiivl/wDSP

-----------------------------------------------------------------------------
PODZIĘKOWANIA
-----------------------------------------------------------------------------

Dla Volodymyra Chebanenko (khiivl), autora wDSP, za aplikację, na której opiera się to wydanie.
Dla Mirka (Polska) za przetestowanie korektora autora na prawdziwym sprzęcie. Dla testerów
przesyłających pomiary i raporty.
