wDSP — 1.1 "Bulldozer" (Build 26)
Was sich seit 0.4.9.8 (Build 25) geändert hat
=============================================

Ein großes Update: ein Monat Arbeit, und die Version 1.0 des Autors ist nun integriert. Im Folgenden
erfahren Sie, was neu ist und wie Sie es nutzen. Das vollständige Handbuch für jede Funktion liegt dem
Paket bei: manual_en.html (auch auf Ukrainisch, Spanisch und Russisch verfügbar). Es kann in jedem
Browser geöffnet werden und benötigt keine Internetverbindung.

INSTALLATION

Installieren Sie die APK einfach über die vorherige Version; eine vorherige Deinstallation ist nicht
erforderlich. Ihre Presets und Einstellungen bleiben erhalten. Öffnen Sie wDSP nach der Installation
einmalig, damit der Hintergrunddienst gestartet wird.

-----------------------------------------------------------------------------
1. ZWEI DESIGNS: KLASSISCH UND MODERN
-----------------------------------------------------------------------------

Die Version 1.0 des Autors wurde zusammengeführt. Unter wDSP Einstellungen → «UI-Stil» wählen Sie:

- Klassisch (Original) — die originale Material-3-Oberfläche des Autors;
- Modern — das bekannte Milchglas-Design aus meinen Versionen.

Beide Stile unterstützen das helle und dunkle Design. Neuigkeiten aus Version 1.0:

- Der Equalizer-Graph zeichnet nun die reale Kurve des Audio-Chips (Q 2.2), keine grobe Skizze mehr.
- Ultra Bass — Subwoofer-Verstärkung, die mit dem Lautstärkeregler dynamisch ansteigt.
- Loudness über ein Bass-Kuhschwanzfilter (Shelf) plus Höhenabsenkung (Trim Highs).
- Paarweise Sperre für Laufzeitkorrekturen — linker und rechter Kanal bewegen sich gemeinsam und
  bestätigen dies beim Einschalten.
- «Aus» in der Subwoofer-Frequenzliste für Fahrzeuge ohne Subwoofer.
- GALA (geschwindigkeitsabhängige Lautstärke) kann für ein einzelnes Preset deaktiviert werden —
  «Für dieses Preset deaktivieren».
- Der Reiter «Audio-Check»: Rosa Rauschen, Sinusgenerator für beliebige Frequenzen, Sweep-Messung und
  Testaufnahmen für jeden einzelnen Lautsprecher. Ideal, um jeden Lautsprecher vor der Einmessung
  einzeln zu überprüfen.

-----------------------------------------------------------------------------
2. FAHRZEUGEINMESSUNG (AUTO-EQ) — GRUNDLEGEND ÜBERARBEITET
-----------------------------------------------------------------------------

So nutzen Sie die Einmessung jetzt:

1. wDSP Einstellungen → Fahrzeugkarte. Geben Sie an, ob es sich um ein geschlossenes Fahrzeug oder
   einen offenen Raum (Prüfstand, Cabrio) handelt, tragen Sie die Abmessungen ein und TIPPEN SIE DIE
   LAUTSPRECHER auf der Fahrzeugskizze an, um festzulegen, welche tatsächlich verbaut sind. Nur
   aktivierte Lautsprecher werden eingemessen.
2. Kalibrieren Sie das Mikrofon (einmalig pro Gerät).
3. Starten Sie die Messung. Die Lautstärke müssen Sie nicht mehr selbst einstellen — die App wählt sie
   automatisch und misst dabei präzise, wie viel dB jeder Lautstärkeschritt an Ihrem Gerät ausmacht.
4. Tippen Sie auf «Apply Auto-EQ». Direkt im Anschluss spielt die App einen kurzen KONTROLL-SWEEP über
   das neue Preset ab und prüft, ob die Korrektur wie gewünscht greift.

Was der neue Bericht anzeigt:

- An erster Stelle steht eine FEHLERLISTE in verständlichen Worten: ein Lautsprecher leiser als sein
  gespiegeltes Gegenüber, ein Hochtöner gegenphasig zu seinem Tiefmitteltöner, vertauschte Kanäle
  links/rechts, Lautsprecher-Resonanz oder Raummode unter 200 Hz, eine im Störgeräusch untergegangene
  Messung.
- Spielt der Subwoofer an der Trennfrequenz gegenphasig zu den Türen, empfiehlt die App, den
  Phasenschalter am Subwoofer-Verstärker umzulegen (0/180).
- Die Korrektur wird in echten dB angezeigt, nicht mehr in internen Chip-Registern.
- Umgebungsgeräusche im Fahrzeug führen nicht mehr zum Messabbruch; sie reduzieren lediglich die
  Vertrauenswürdigkeit in den betroffenen Frequenzbändern.

-----------------------------------------------------------------------------
3. SPEKTRUM (RTA)
-----------------------------------------------------------------------------

- Wählen Sie die Darstellungsform: Echtzeitkurve (RTA) oder klassische Balken.
- Die Schaltfläche «RTA-Null» im Audio-Check setzt beide Nullen gleichzeitig (Wiedergabekanal und
  Mikrofon): Sie spielt selbstständig Rosa Rauschen ab, misst den Pegel und setzt den Referenzpunkt.
- Bei Mikrofon-Übersteuerung warnt die Anzeige: «Mikrofon übersteuert — Anzeige mit AGC, Form
  näherungsweise. Mikrofonempfindlichkeit verringern». Senken Sie die Mikrofonempfindlichkeit in den
  Fahrzeugeinstellungen, bis die Meldung erlischt.
- Die Spektrum-Anzeige pausiert bei Inaktivität und wacht bei Wiedergabe selbsttätig auf.
- Bluetooth-Musik wird nun auch auf Geräten ohne BitPerfect-Modul im Spektrum visualisiert.

-----------------------------------------------------------------------------
4. NEU: WIEDERGABE-FORTSETZUNG NACH NEUSTART ODER STANDBY
-----------------------------------------------------------------------------

Zwei Schalter in den Einstellungen: «Player nach Neustart fortsetzen» und «Player nach Standby
fortsetzen». Lief beim Ausschalten Musik, startet wDSP denselben Player beim Hochfahren erneut — ohne
dessen Bildschirm über Ihren Launcher oder die Navigation zu legen. Das Radio stellt sich selbstständig
wieder her und bleibt unberührt.

Für den Standby tippen Sie unter dem Schalter auf «wDSP im Standby aktiv lassen»: Dies öffnet die
fahrzeugeigene Whitelist für Hintergrund-Apps, in der wDSP aktiviert werden muss.

Diese Funktion ist neu. Bitte teilen Sie mir mit, welchen Player Sie verwenden und ob er zuverlässig
wieder anlief.

-----------------------------------------------------------------------------
5. ANRUFE, BILDSCHIRMSCHONER, LAUTSTÄRKE
-----------------------------------------------------------------------------

- Bei eingehenden Anrufen gibt die App das Mikrofon sofort frei und schaltet auf das Anruf-Preset um;
  nach dem Auflegen kehren die vorherigen Einstellungen zurück.
- Der Bildschirmschoner schließt sich unmittelbar bei Anrufbeginn.
- Der Bildschirmschoner funktioniert nun auch auf Geräten, bei denen in den Werkseinstellungen
  «schwebendes Video» oder «schwebende Navigation» aktiv ist (zuvor wurde er dort blockiert).
- Die in den Systemeinstellungen gewählte Navigations-App wird automatisch als Ausnahme hinterlegt,
  damit der Bildschirmschoner niemals die Karte verdeckt. Wer den Bildschirmschoner dennoch über der
  Navigation wünscht, kann sie aus der Liste entfernen.
- Die untere Zeile des Equalizers schneidet Verstärker-Buttons auf schmalen und vertikalen Displays
  nicht mehr ab — die gesamte Leiste skaliert gleichmäßig.
- Die Lautstärke jeder Quelle wird separat gespeichert und folgt Änderungen, die außerhalb von wDSP
  vorgenommen werden.
- GALA hinterlässt nach dem Abbremsen keinen überflüssigen Lautstärkeschritt mehr.

-----------------------------------------------------------------------------
SPRACHEN
-----------------------------------------------------------------------------

Die Benutzeroberfläche steht in 30 Sprachen und regionalen Varianten zur Verfügung.

-----------------------------------------------------------------------------
BITTE AN DIE TESTER
-----------------------------------------------------------------------------

1. AUTO-PRESETS. Wurde das Preset nach Ihrem Hörgeschmack passend erstellt? Stimmt die Räumlichkeit
   der Bühne? Bitte senden Sie das Messarchiv («Archiv speichern» nach der Messung) und den
   Systembericht (Diagnose → «Systembericht»). Beide Dateien werden im Ordner Download/wDSP abgelegt.

2. FALLS DER BILDSCHIRMSCHONER NIE ERSCHEINT: Belassen Sie das Gerät zwei Minuten lang in einer
   beliebigen App außer wDSP. Öffnen Sie anschließend wDSP Einstellungen → Diagnose → «Screen Topology»
   → Datei speichern und einsenden. Erscheint der Bildschirmschoner, tippen Sie zunächst mehrmals
   darauf (auf Titel-Zonen, Bildschirmränder) und speichern Sie erst dann: So lässt sich genau
   analysieren, wo Berührungen erkannt und wie sie verarbeitet wurden.

3. WIEDERGABE-FORTSETZUNG — welcher Player, nach Neustart oder Standby, und ob die Musik wie erwartet
   weiterspielte.

Bitte senden Sie alle Rückmeldungen in die Gruppe: https://t.me/wDSPapp/79

Sollte etwas nicht ordnungsgemäß funktionieren, erläutern Sie bitte genau, was passiert ist. Wenn alles
bestens klappt, berichten Sie gern, wie die Presets in Ihrem Fahrzeug klingen.

Vielen Dank.
