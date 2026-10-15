wDSP — 0.4.9.9 (build 26)
Cosa è cambiato rispetto alla 0.4.9.8 (build 25)
=================================================

Un aggiornamento importante: un mese di sviluppo che integra ora al suo interno la versione 1.0 dell'autore.
Di seguito sono illustrate le novità e come utilizzarle. Il manuale completo per ciascuna funzione è incluso
nel pacchetto: manual_en.html (disponibile anche in ucraino, spagnolo e russo). Può essere aperto in qualsiasi
browser e non richiede connessione a internet.

INSTALLAZIONE

Installate l'APK direttamente sopra la versione precedente, senza disinstallare nulla. I vostri preset e
le impostazioni rimarranno invariati. Al termine dell'installazione, aprite wDSP una volta per avviare il
suo servizio in background.

-----------------------------------------------------------------------------
1. DUE STILI DI INTERFACCIA: CLASSICO E MODERNO
-----------------------------------------------------------------------------

La versione 1.0 dell'autore è stata unificata con la mia. In Impostazioni wDSP → «Stile interfaccia» potete scegliere:

- Classico (Originale) — l'interfaccia originale Material 3 dell'autore;
- Moderno — il tema in vetro satinato già noto nelle mie versioni.

Entrambi gli stili supportano pienamente i temi chiaro e scuro. Novità introdotte con la 1.0:

- Il grafico dell'equalizzatore traccia ora la curva reale del processore sonoro (Q 2.2), non un semplice abbozzo.
- Ultra Bass — amplificazione del subwoofer che incrementa in modo progressivo con la manopola del volume.
- Loudness tramite filtro shelf per i bassi, accompagnato dal taglio delle frequenze alte (Trim Highs).
- Blocco per coppie nei ritardi temporali: i canali sinistro e destro si muovono assieme e lo confermano all'attivazione.
- Opzione «Off» nell'elenco delle frequenze del subwoofer per le auto prive di subwoofer.
- GALA (volume in funzione della velocità) disattivabile per un singolo profilo — «Disattiva per profilo».
- Scheda «Test Audio»: rumore rosa, generatore sinusoidale per qualsiasi frequenza, sweep e tracce di test
  dedicate per ogni singolo altoparlante. Ideale per ascoltare ciascun diffusore prima della misurazione.

-----------------------------------------------------------------------------
2. CALIBRAZIONE DELL'ABITACOLO (AUTO-EQ) — COMPLETAMENTE RINNOVATA
-----------------------------------------------------------------------------

Come utilizzarla adesso:

1. Impostazioni wDSP → scheda dell'abitacolo. Specificate se l'auto è chiusa o se si tratta di uno spazio
   aperto (banco di prova, cabriolet), impostate le dimensioni e TOCCATE GLI ALTOPARLANTI sul disegno
   dell'auto per selezionare quali sono realmente installati. La misurazione verrà eseguita solo sui canali dichiarati.
2. Calibrate il microfono (una sola volta per autoradio).
3. Avviate la misurazione. Non dovrete più impostare il volume manualmente: l'app lo determina da sé e,
   lungo il percorso, misura quanti dB effettivi eroga ciascun livello della manopola del volume sulla vostra unità.
4. Premete «Apply Auto-EQ». Subito dopo l'app riprodurrà un breve SWEEP DI CONTROLLO attraverso il nuovo preset
   e vi confermerà se la correzione acustica ha funzionato a dovere.

Cosa evidenzia ora il report:

- Per prima cosa viene mostrato un ELENCO DEI DIFETTI in linguaggio chiaro: un altoparlante con livello
  inferiore al suo simmetrico, un tweeter in controfase con il proprio midbass in portiera, canali sinistro/destro
  invertiti, risonanza del diffusore o modo di risonanza dell'abitacolo sotto i 200 Hz, misurazione sommersa dal rumore.
- Se all'incrocio del crossover il subwoofer lavora in controfase con le portiere, l'app suggerisce di
  invertire l'interruttore di fase sull'amplificatore del sub (0/180).
- La correzione è espressa in dB reali e non in registri interni del chip.
- Il rumore di fondo dell'abitacolo non costituisce più motivo di interruzione: riduce unicamente l'indice di
  affidabilità sulle bande di frequenza che copre.

-----------------------------------------------------------------------------
3. SPETTRO (RTA)
-----------------------------------------------------------------------------

- Scegliete la visualizzazione preferita: curva RTA in tempo reale o barre classiche.
- Il pulsante «Zero RTA» nella scheda Test Audio calibra entrambi gli zeri contemporaneamente (canale audio
  diretto e microfono): riproduce autonomamente il rumore rosa, rileva il segnale e imposta lo zero.
- In caso di saturazione del microfono, sullo spettro appare l'avviso: «Microfono sovraccarico: visualizzato
  con AGC, forma approssimativa. Ridurre la sensibilità del microfono». Abbassate la sensibilità del microfono
  nelle impostazioni dell'autoradio fino a quando la scritta non scompare.
- L'analizzatore entra in pausa quando non c'è riproduzione attiva e si riattiva automaticamente alla ripresa dell'audio.
- La musica via Bluetooth viene ora visualizzata sullo spettro anche sui dispositivi privi del modulo BitPerfect.

-----------------------------------------------------------------------------
4. NOVITÀ: RIPRESA DEL LETTORE DOPO IL RIAVVIO O LO STANDBY
-----------------------------------------------------------------------------

Due interruttori nelle Impostazioni: «Riprendi il lettore dopo il riavvio» e «Riprendi il lettore dopo la
sospensione». Se la musica era in riproduzione allo spegnimento, wDSP riavvia lo stesso lettore al risveglio
dell'unità — senza sovrapporre la sua schermata al launcher o al navigatore. La radio si ripristina da sola e
non viene toccata dall'applicazione.

Per la sospensione, premete «Consenti a wDSP di restare attivo» sotto l'interruttore: si aprirà la whitelist di
sistema dell'autoradio per le app in background, dove andrà spuntato wDSP.

Questa funzionalità è del tutto inedita. Fatemi sapere quale lettore utilizzate e se la riproduzione è ripresa regolarmente.

-----------------------------------------------------------------------------
5. CHIAMATE, SCREENSAVER, VOLUME
-----------------------------------------------------------------------------

- Durante una chiamata telefonica l'app rilascia immediatamente il microfono e attiva il preset per le chiamate;
  al termine della conversazione vengono ripristinate le impostazioni precedenti.
- Lo screensaver si chiude nell'istante esatto in cui comincia una telefonata.
- Lo screensaver si attiva ora correttamente anche sulle autoradio in cui è abilitato il «video mobile» o la
  «navigazione mobile» nelle impostazioni di fabbrica (in precedenza su tali sistemi non compariva).
- L'app di navigazione configurata nelle impostazioni dell'autoradio viene aggiunta in automatico alle eccezioni
  dello screensaver, così da non coprire mai la mappa. Chi preferisce lo screensaver sopra il navigatore può rimuoverlo dall'elenco.
- La fila inferiore dell'equalizzatore non taglia più i pulsanti dell'amplificatore sugli schermi stretti o
  verticali: l'intera riga si adatta proporzionalmente.
- Il volume di ciascuna sorgente viene memorizzato in modo indipendente e segue le modifiche effettuate
  all'esterno di wDSP.
- GALA non lascia più uno scatto di volume residuo dopo ogni frenata o rallentamento.

-----------------------------------------------------------------------------
LINGUE
-----------------------------------------------------------------------------

L'interfaccia è disponibile in 30 lingue e varianti regionali.

-----------------------------------------------------------------------------
RICHIESTA PER I TESTER
-----------------------------------------------------------------------------

1. AUTOPRESET. A vostro giudizio, il preset è bilanciato correttamente? Il fronte sonoro è ben strutturato?
   Vi prego di inviare l'archivio di misurazione («Salva l'archivio» dopo la calibrazione) e il rapporto di sistema
   (Diagnostica → «Rapporto di sistema»). Entrambi i file vengono salvati nella cartella Download/wDSP.

2. SE LO SCREENSAVER NON DOVESSE MAI APPARIRE SULLA VOSTRA UNITÀ: lasciate l'autoradio per due minuti su una
   qualsiasi applicazione tranne wDSP. Andate quindi in Impostazioni wDSP → Diagnostica → «Screen Topology» →
   salvate e inviate il file. Se invece lo screensaver appare, effettuate prima qualche tocco su di esso (sulle aree
   di controllo tracce, sui bordi dello schermo) e solo dopo salvate: nel file sarà registrato con precisione dove
   sono caduti i tocchi e come sono stati interpretati dall'app.

3. RIPRESA DEL LETTORE — indicate quale lettore musicale utilizzate, se dopo riavvio o sospensione, e se la
   riproduzione è ripartita correttamente.

Inviate tutto sul gruppo: https://t.me/wDSPapp/79

Se riscontrate qualche anomalia, aggiungete un commento descrivendo esattamente l'accaduto. Se tutto funziona al
meglio, raccontatemi semplicemente come suonano i preset sulla vostra vettura.

Grazie mille.
