wDSP — 1.1 "Bulldozer" (Build 26)
Änderungen seit 0.4.9.8, der vorherigen Version vom 13.09.2026
==============================================================

Diese Version ist meine Modifikation von wDSP von Volodymyr Chebanenko (khiivl),
https://github.com/khiivl/wDSP, und enthält all seine Arbeit bis zu Version 1.0.2.

INSTALLATION

- Über eine frühere Version der Modifikation: Installieren Sie die APK darüber; Presets und
  Einstellungen bleiben erhalten.
- Über die Originalversion des Autors: Beide sind mit unterschiedlichen Schlüsseln signiert und
  Android installiert sie nicht übereinander. Sichern Sie Ihre Presets mit dem App-Backup, deinstallieren
  Sie die Version des Autors und installieren Sie anschließend die Modifikation.
- Öffnen Sie wDSP einmalig nach der Installation.

Die Anleitungen (neun Sprachen, einschließlich der Gestenkarte für den Bildschirmschoner) liegen im
Archiv. Sie lassen sich in jedem Browser ohne Internetverbindung öffnen.

-----------------------------------------------------------------------------
NEU
-----------------------------------------------------------------------------

AUS DER APP DES AUTORS (khiivl, bis zu seiner 1.0.2) — alles davon ist auch in der Modifikation
- Ultra Bass, die neue Loudness, eine Live-Analysatorkurve hinter dem Equalizer, "Aus" in der
  Subwoofer-Liste für Fahrzeuge ohne Subwoofer, GALA pro Preset abschaltbar.
- Der Reiter "Audio-Check": Testsignale und Testaufnahmen für jeden einzelnen Lautsprecher.

NUR IN DER MODIFIKATION — die App des Autors kann das nicht
- Sie misst das Auto: Sweeps über jeden Lautsprecher, aufgenommen mit dem Mikrofon des Radios,
  und daraus ein Preset, die Laufzeiten und eine Liste der Probleme.
- Sie arbeitet mit dem Mikrofon: Spektrum und RTA hören in den Innenraum, auch beim analogen
  UKW-Radio, das gar nicht über Android läuft und sonst kein Spektrum hat.
- Der Analysator ist nativer Code (C++), nicht Java.
- Ein Visualisierer in der Statusleiste und ein Vollbild-Bildschirmschoner mit Player-Steuerung.
- Sie kümmert sich um den Klang über den Equalizer hinaus: Anrufe, die Lautstärke jeder
  Quelle, Fortsetzen des Players nach Neustart oder Standby.
- Sie arbeitet mit Ereignissen statt mit einer Uhr: der Dienst des Autors prüft Lautstärke
  und Player alle 200 ms, die Modifikation reagiert nur, wenn sich wirklich etwas ändert.
- Zwei Oberflächen: Klassisch (das Aussehen des Autors) und das eigene Modern der Modifikation.

Neu in der Modifikation in 1.1:

Oberfläche
- Zwei Oberflächenstile in Einstellungen → "UI-Stil": Klassisch (das Aussehen des Autors) und
  Modern. Tag- und Nacht-Themen in beiden Stilen.
- Paarsperren für Laufzeitverzögerungen: "Vorne zusammen" und "Hinten zusammen" behalten den
  Unterschied zwischen links und rechts bei.

Fahrgastraum-Messung
- Einstellungen → Fahrgastraum-Karte: Art des Innenraums und Maße angeben sowie die verbauten
  Lautsprecher antippen.
- Die App wählt die Messlautstärke selbstständig.
- Nach "Apply Auto-EQ" zeigt ein kurzer Kontroll-Sweep, ob die Korrektur gewirkt hat.
- Der Bericht beginnt mit einer Fehlerliste in verständlichen Worten — zum Beispiel ein zu leiser
  Lautsprecher, ein verpolter Hochtöner, vertauschte Kanäle, ein Subwoofer, der gegen die Türen
  spielt (mit dem Hinweis, den Phasenschalter umzulegen). Probleme, die ein Equalizer nicht beheben
  kann, werden gemeldet, statt sie zu "korrigieren".

Spektrum
- Umschalter auf dem Hauptbildschirm: "Aus", "Musik" (was das Preset aus der Musik macht),
  "Microphone" (wie der Innenraum tatsächlich klingt).
- "RTA-Nullpunkt" im Audio-Check kalibriert das Spektrum mit einem einzigen Druck.
- Warnung bei Übersteuerung des Mikrofons: Empfindlichkeit in den Fahrzeugeinstellungen verringern.

Player-Wiederaufnahme
- Einstellungen: "Player nach Neustart fortsetzen" und "Player nach Standby fortsetzen". Der zuletzt
  aktive Player kehrt eigenständig zurück, ohne dass sich sein Fenster über die Navigation drängt.
- Für den Standby-Modus tippen Sie auf "wDSP im Standby aktiv lassen" und aktivieren Sie wDSP in der Liste.

Bildschirmschoner
- Wischen von der Bildschirmmitte aus regelt die Lautstärke.
- Stil-Taste eine Sekunde lang gedrückt halten öffnet den Equalizer.
- Ein Tippen auf das Cover öffnet den aktuell tatsächlich spielenden Player oder das Radio.
- Schalter "Statusleiste überdecken".

-----------------------------------------------------------------------------
BEHOBEN
-----------------------------------------------------------------------------

Autor (khiivl)
- Subwoofer-Pegel beim Einschalten: ein Wettlauf beim Start setzte ihn falsch (seine 1.0.1).

Mod
- Anrufe: Das Preset springt während eines Telefonats nicht mehr hin und her, das Mikrofon wird sofort
  freigegeben und die Lautstärke nach dem Gespräch stimmt.
- Start: Einstellungen erreichen den Audioprozessor nach dem Einschalten und nach Updates zuverlässig.
- Der Bildschirmschoner erscheint nun auch auf Geräten mit aktivem "schwebendem Video" oder "schwebender
  Navigation", verdeckt das Navigationssystem nicht und funktioniert auf vertikalen Bildschirmen.
- Equalizer-Ansicht und Dialogfenster passen auf schmale und vertikale Bildschirme.
- Mikrofonspektrum nach dem Aufwachen und nachdem der Sprachassistent das Mikrofon verwendet hat.
- Mehrere Fehler bei der Fahrgastraum-Messung, insbesondere im Tiefbass und im Zusammenspiel mit dem Subwoofer.
- GALA lässt die Lautstärke nach dem Abbremsen nicht mehr angehoben.

-----------------------------------------------------------------------------
BITTE EINSENDEN
-----------------------------------------------------------------------------

Die Version des Autors besitzt keine Fahrgastraum-Messung, daher gehen Messdaten an die Gruppe der Modifikation.

1. Nach einer Fahrgastraum-Messung: das Messarchiv ("Archiv speichern") und der Systembericht
   (Diagnose → "Systembericht"), beide in Download/wDSP, sowie ein paar Worte dazu, wie sich der
   Klang und die Bühne verändert haben.
2. Falls der Bildschirmschoner nie erscheint: Diagnose → "Screen Topology" → speichern und einsenden.
3. Ob die Player-Wiederaufnahme bei Ihnen funktioniert hat und mit welchem Player.

Gruppe der Modifikation: https://t.me/kostyamat_dev/92
Originalprojekt: https://t.me/wDSPapp, https://github.com/khiivl/wDSP

-----------------------------------------------------------------------------
DANKSAGUNG
-----------------------------------------------------------------------------

An Volodymyr Chebanenko (khiivl), den Autor von wDSP, für die Anwendung, auf der diese Version aufbaut.
An Mirek (Polen) für das Testen des Equalizers des Autors auf echter Hardware. An alle Tester, die Messungen
und Berichte einsenden.
