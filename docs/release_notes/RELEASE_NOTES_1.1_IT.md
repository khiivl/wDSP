wDSP — 1.1 "Bulldozer" (build 26)
Modifiche dalla versione 0.4.9.8, la precedente distribuzione del 13.09.2026
============================================================================

Questo rilascio è la mia modifica di wDSP di Volodymyr Chebanenko (khiivl),
https://github.com/khiivl/wDSP, e include tutto il suo lavoro fino alla versione 1.0.2.

INSTALLAZIONE

- Sopra una versione precedente del mod: installa l'APK sopra; predefiniti e impostazioni rimangono.
- Sopra la versione dell'autore: sono firmate con chiavi diverse e Android non installerà l'una
  sopra l'altra. Salva i predefiniti con il backup dell'applicazione, disinstalla la versione
  dell'autore e quindi installa il mod.
- Apri wDSP una volta dopo l'installazione.

I manuali (nove lingue, inclusa la mappa dei gesti del salvaschermo) sono nell'archivio. Si aprono
in qualsiasi browser senza bisogno di Internet.

-----------------------------------------------------------------------------
NOVITÀ
-----------------------------------------------------------------------------

DALL'APP DELL'AUTORE (khiivl, fino alla sua 1.0.2) — tutto questo è anche nel mod
- Ultra Bass, il nuovo Loudness, una curva dell'analizzatore in tempo reale dietro l'equalizzatore,
  "Spento" nell'elenco subwoofer per le auto sprovviste, GALA disattivabile per singolo predefinito.
- La scheda "Test Audio": segnali di prova e registrazioni di test per ciascun altoparlante.

SOLO NEL MOD — l'app dell'autore non lo fa
- Misura l'auto: sweep su ogni altoparlante, registrati dal microfono dell'autoradio, e da ciò che
  sente costruisce un predefinito, i ritardi e un elenco di problemi.
- Il suo spettro può ascoltare l'abitacolo con il microfono, non solo mostrare la musica.
- Un visualizzatore nella barra di stato e un salvaschermo a schermo intero con i comandi del
  lettore.
- Si occupa del suono oltre l'equalizzatore: chiamate, il volume di ogni sorgente, ripresa del
  lettore dopo un riavvio o la sospensione.
- Due interfacce: Classico (l'aspetto dell'autore) e il Moderno proprio del mod.

Novità del mod nella 1.1:

Interfaccia
- Due stili di interfaccia in Impostazioni → "Stile interfaccia": Classico (l'aspetto dell'autore)
  e Moderno. Temi diurno e notturno per entrambi.
- Blocco coppie per i ritardi: "Anteriori insieme" e "Posteriori insieme" mantengono la differenza
  tra sinistra e destra.

Misurazione dell'abitacolo
- Impostazioni → scheda abitacolo: indica il tipo di abitacolo, le dimensioni e tocca gli altoparlanti
  installati.
- L'applicazione sceglie autonomamente il volume di misurazione.
- Dopo "Apply Auto-EQ" un breve sweep di controllo verifica se la correzione ha avuto effetto.
- Il rapporto inizia con un elenco di problemi descritti in parole semplici — ad esempio un altoparlante
  debole, un tweeter con polarità invertita, canali scambiati, un subwoofer che suona contro le portiere
  (con il suggerimento di commutare la fase). I problemi che un equalizzatore non può risolvere vengono
  segnalati anziché essere "corretti".

Spettro
- Un selettore nella schermata principale: "Spento", "Musica" (ciò che il predefinito ricava dalla
  musica), "Microphone" (come suona realmente l'abitacolo).
- "Zero RTA" in Test Audio calibra lo spettro con una sola pressione.
- Avviso quando il microfono è sovraccarico: riduci la sensibilità nelle impostazioni dell'auto.

Ripresa del lettore
- Impostazioni: "Riprendi il lettore dopo il riavvio" e "Riprendi il lettore dopo la sospensione". Il
  lettore in riproduzione torna attivo da solo, senza che la sua finestra si sovrapponga al navigatore.
- Per la sospensione, tocca "Consenti a wDSP di restare attivo" e abilita wDSP nell'elenco che si apre.

Salvaschermo
- Scorri dal centro dello schermo per regolare il volume.
- Tieni premuto il pulsante dello stile per un secondo per aprire l'equalizzatore.
- Un tocco sulla copertina apre il lettore o la radio effettivamente in riproduzione.
- Interruttore "Copri la barra di stato".

-----------------------------------------------------------------------------
RISOLTO
-----------------------------------------------------------------------------

Autore (khiivl)
- Il livello del subwoofer all'accensione: una race condition all'avvio lo impostava male (la sua 1.0.1).

Mod
- Chiamate: il predefinito non salta più durante una chiamata, il microfono viene rilasciato subito
  e il volume dopo la chiamata è corretto.
- Avvio: le impostazioni raggiungono il processore audio in modo affidabile dopo l'accensione e dopo
  un aggiornamento.
- Il salvaschermo ora appare anche su dispositivi con "video mobile" o "navigazione mobile" abilitati,
  non copre il navigatore e funziona su schermi verticali.
- La schermata dell'equalizzatore e le finestre di dialogo si adattano a schermi stretti e verticali.
- Spettro del microfono ripristinato dopo la sospensione e dopo l'uso del microfono da parte dell'assistente vocale.
- Corretti diversi errori nella misurazione dell'abitacolo, in particolare sui bassi profondi e con il subwoofer.
- GALA non lascia più il volume elevato dopo aver rallentato.

-----------------------------------------------------------------------------
INVIA PER FAVORE
-----------------------------------------------------------------------------

La versione dell'autore non dispone della misurazione dell'abitacolo, pertanto le misurazioni vanno
al gruppo del mod.

1. Dopo una misurazione dell'abitacolo: l'archivio di misurazione ("Salva l'archivio") e il rapporto
   di sistema (Diagnostica → "Rapporto di sistema"), entrambi in Download/wDSP, con due parole su come
   è cambiato il suono e la scena sonora.
2. Se il salvaschermo non compare mai: Diagnostica → "Screen Topology" → salva e invia.
3. Se la ripresa del lettore ha funzionato e con quale lettore.

Gruppo del mod: https://t.me/kostyamat_dev/92
Progetto originale: https://t.me/wDSPapp, https://github.com/khiivl/wDSP

-----------------------------------------------------------------------------
RINGRAZIAMENTI
-----------------------------------------------------------------------------

A Volodymyr Chebanenko (khiivl), autore di wDSP, per l'applicazione su cui è costruito questo rilascio.
A Mirek (Polonia) per aver collaudato l'equalizzatore dell'autore su hardware reale. A tutti i tester che
inviano misurazioni e rapporti.
