wDSP — 1.1 "Bulldozer" (build 26)
Modifications depuis la version 0.4.9.8 du 13.09.2026
=====================================================

Cette version est ma modification de wDSP créé par Volodymyr Chebanenko (khiivl),
https://github.com/khiivl/wDSP, et intègre l'ensemble de ses travaux jusqu'à sa version 1.0.2.

INSTALLATION

- Par-dessus une version précédente du mod : installez l'APK par-dessus ; les préréglages et paramètres
  sont conservés.
- Par-dessus la version de l'auteur : les deux sont signées avec des clés différentes et Android
  n'installera pas l'une sur l'autre. Sauvegardez vos préréglages via la sauvegarde de l'application,
  désinstallez la version de l'auteur, puis installez le mod.
- Ouvrez wDSP une fois après l'installation.

Les manuels (neuf langues, incluant la carte des gestes de l'économiseur d'écran) sont dans l'archive.
Ils s'ouvrent dans n'importe quel navigateur sans connexion Internet.

-----------------------------------------------------------------------------
NOUVEAUTÉS
-----------------------------------------------------------------------------

La version 1.0 de l'auteur
- Deux styles d'interface dans Paramètres → "Style d'interface" : Classique (Original) et Moderne.
  Thèmes jour et nuit disponibles dans les deux.
- Ultra Bass, la nouvelle compensation de Loudness, davantage de visualisations, verrouillage par paire
  des délais, "Désactivé" dans la liste du subwoofer pour les véhicules non équipés, GALA désactivable
  par préréglage.
- L'onglet "Test Audio" : signaux de test et enregistrements de contrôle pour chaque haut-parleur.

Mesure de l'habitacle
- Paramètres → carte de l'habitacle : indiquez le type d'habitacle, ses dimensions et touchez les
  haut-parleurs installés.
- L'application détermine automatiquement le volume de mesure.
- Après "Apply Auto-EQ", un court balayage de contrôle vérifie l'efficacité de la correction.
- Le rapport débute par une liste des anomalies en termes simples — par exemple un haut-parleur faible,
  un tweeter en inversion de polarité, des canaux inversés, un caisson de basses en opposition avec les
  portes (avec indication d'inverser son sélecteur de phase). Les problèmes qu'un égaliseur ne peut
  résoudre sont signalés au lieu d'être « corrigés » artificiellement.

Spectre
- Un commutateur sur l'écran principal : "Désactivé", "Musique" (ce que le préréglage produit à partir
  de la musique), "Microphone" (le rendu réel dans l'habitacle).
- "Zéro RTA" dans Test Audio étalonne le spectre en une seule pression.
- Avertissement en cas de saturation du microphone : réduisez sa sensibilité dans les réglages du véhicule.

Reprise du lecteur
- Paramètres : "Reprendre la lecture après le redémarrage" et "Reprendre la lecture après la veille".
  Le lecteur actif reprend automatiquement, sans que sa fenêtre ne vienne masquer la navigation.
- Pour la veille, appuyez sur "Garder wDSP actif en veille" et activez wDSP dans la liste affichée.

Économiseur d'écran
- Glissez depuis le centre de l'écran pour modifier le volume.
- Maintenez le bouton de style enfoncé une seconde pour ouvrir l'égaliseur.
- Un appui sur la pochette ouvre le lecteur ou la radio en cours d'écoute.
- Interrupteur "Recouvrir la barre d'état".

-----------------------------------------------------------------------------
CORRIGÉ
-----------------------------------------------------------------------------

- Appels : le préréglage ne saute plus pendant un appel, le microphone est libéré immédiatement et
  le volume après l'appel est rétabli correctement.
- Démarrage : les réglages sont transmis de manière fiable au processeur audio après l'allumage et
  après une mise à jour.
- L'économiseur d'écran s'affiche désormais sur les appareils avec "vidéo flottante" ou "navigation
  flottante" activée, ne masque pas la navigation et fonctionne sur écrans verticaux.
- L'écran de l'égaliseur et les boîtes de dialogue s'adaptent aux écrans étroits et verticaux.
- Rétablissement du spectre microphone après la veille et après utilisation du micro par l'assistant vocal.
- Correction de plusieurs erreurs de mesure de l'habitacle, particulièrement dans les graves profonds et
  avec le subwoofer.
- GALA ne maintient plus le volume élevé après un ralentissement.
- Correction d'un plantage à l'ouverture directe de l'application sur l'onglet Loudness.

-----------------------------------------------------------------------------
MERCI D'ENVOYER
-----------------------------------------------------------------------------

La version de l'auteur ne comportant pas la mesure de l'habitacle, les mesures sont recueillies dans le
groupe du mod.

1. Après une mesure de l'habitacle : l'archive de mesure ("Enregistrer l'archive") et le rapport
   système (Diagnostic → "Rapport système"), tous deux dans Download/wDSP, accompagnés de quelques
   mots sur l'évolution du son et de la scène sonore.
2. Si l'économiseur d'écran ne s'affiche jamais : Diagnostic → "Topologie de l'écran" → enregistrer et envoyer.
3. Si la reprise du lecteur a fonctionné chez vous, et avec quel lecteur.

Groupe du mod : https://t.me/kostyamat_dev/92
Projet original : https://t.me/wDSPapp, https://github.com/khiivl/wDSP

-----------------------------------------------------------------------------
REMERCIEMENTS
-----------------------------------------------------------------------------

À Volodymyr Chebanenko (khiivl), auteur de wDSP, pour l'application sur laquelle s'appuie cette version.
À Mirek (Pologne) pour avoir testé l'égaliseur de l'auteur sur du matériel réel. Aux testeurs qui
partagent leurs mesures et rapports.
