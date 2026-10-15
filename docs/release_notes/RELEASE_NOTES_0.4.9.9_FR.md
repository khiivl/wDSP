wDSP — 0.4.9.9 (version 26)
Nouveautés depuis la version 0.4.9.8 (version 25)
=================================================

Une mise à jour majeure : un mois de travail avec l'intégration de la version 1.0 de l'auteur original.
Voici les nouveautés et la manière de les utiliser. Le manuel complet pour chaque fonction est inclus
dans l'archive : manual_en.html (également disponible en ukrainien, espagnol et russe). Il s'ouvre dans
n'importe quel navigateur sans connexion internet.

INSTALLATION

Installez l'APK par-dessus la version précédente, sans désinstallation préalable. Vos préréglages et
vos paramètres restent intacts. Après l'installation, ouvrez wDSP une fois afin que son service d'arrière-plan
puisse démarrer.

-----------------------------------------------------------------------------
1. DEUX STYLES D'INTERFACE : CLASSIQUE ET MODERNE
-----------------------------------------------------------------------------

La version 1.0 de l'auteur a été fusionnée avec la mienne. Dans Paramètres wDSP → «Style d'interface»,
vous choisissez :

- Classique (Original) — l'interface Material 3 originale de l'auteur ;
- Moderne — l'interface en verre dépoli bien connue de mes versions.

Les deux styles prennent pleinement en charge les thèmes clair et sombre. Nouveautés issues de la 1.0 :

- La courbe de l'égaliseur reproduit désormais fidèlement la réponse réelle de la puce (Q 2.2), et non une esquisse.
- Ultra Bass — amplification du caisson de basses qui croît dynamiquement avec le volume.
- Loudness via filtre en plateau (shelf) dans les graves, complété par l'atténuation des aigus (Trim Highs).
- Verrouillage par paire pour les délais — les canaux gauche et droit se déplacent de concert et le confirment à l'activation.
- Option «Désactivé» dans la liste des fréquences du subwoofer pour les véhicules sans caisson.
- GALA (volume asservi à la vitesse) peut être désactivé pour un préréglage spécifique — «Désactiver ce profil».
- Onglet «Test Audio» : bruit rose, générateur de sinus à toute fréquence, balayage (sweep) et pistes
  de test individuelles par haut-parleur. Idéal pour vérifier chaque haut-parleur séparément avant d'étalonner.

-----------------------------------------------------------------------------
2. MESURE DE L'HABITACLE (AUTO-EQ) — ENTIÈREMENT REPENSÉE
-----------------------------------------------------------------------------

Comment l'utiliser désormais :

1. Paramètres wDSP → carte de l'habitacle. Précisez s'il s'agit d'un véhicule fermé ou d'un espace ouvert
   (banc d'essai, cabriolet), renseignez les dimensions et TOUCHEZ LES HAUT-PARLEURS sur le schéma du
   véhicule pour indiquer ceux qui sont réellement présents. Seuls les haut-parleurs déclarés seront mesurés.
2. Calibrez le microphone (une seule fois par autoradio).
3. Lancez la mesure. Le volume n'est plus réglé manuellement — l'application le choisit automatiquement
   et mesure en même temps le nombre exact de décibels générés par chaque palier du bouton de volume.
4. Appuyez sur «Apply Auto-EQ». Immédiatement après, l'application émet un court BALAYAGE DE CONTRÔLE
   à travers le nouveau préréglage et confirme si la correction acoustique fonctionne réellement.

Ce que révèle le nouveau rapport :

- En premier lieu, une LISTE DE DÉFAUTS exprimée en termes clairs : un haut-parleur plus faible que
  son symétrique, un tweeter en opposition de phase avec son propre woofer de porte, canaux gauche/droite
  inversés, résonance de porte ou mode de résonance d'habitacle sous 200 Hz, mesure noyée dans le bruit.
- Si le caisson de basses joue en opposition de phase avec les portes à la fréquence de coupure,
  l'application vous invite à inverser la phase sur l'amplificateur du caisson (0/180).
- Les corrections sont exprimées en décibels réels et non en valeurs internes de registre.
- Le bruit ambiant de l'habitacle n'interrompt plus la mesure : il réduit simplement l'indice de
  confiance sur les bandes de fréquences masquées.

-----------------------------------------------------------------------------
3. ANALYSEUR DE SPECTRE (RTA)
-----------------------------------------------------------------------------

- Choisissez votre affichage : courbe RTA en temps réel ou barres classiques.
- Le bouton «Zéro RTA» dans Test Audio enregistre les deux zéros à la fois (canal audio direct et
  microphone) : il diffuse le bruit rose, effectue l'écoute et calibre le niveau zéro.
- En cas de saturation du microphone, l'écran indique : «Microphone saturé - affiché avec AGC, forme
  approximative. Baissez la sensibilité du micro». Diminuez la sensibilité du micro dans les réglages de
  l'autoradio jusqu'à disparition de l'alerte.
- L'analyseur se met en veille en l'absence de lecture et se réactive automatiquement dès qu'un son est joué.
- La musique transmise en Bluetooth s'affiche désormais sur le spectre, y compris sur les appareils
  dépourvus du module BitPerfect.

-----------------------------------------------------------------------------
4. NOUVEAU : REPRISE DU LECTEUR APRÈS REDÉMARRAGE OU VEILLE
-----------------------------------------------------------------------------

Deux commutateurs dans les Paramètres : «Reprendre la lecture après le redémarrage» et «Reprendre la
lecture après la veille». Si de la musique était en cours lors de l'extinction, wDSP relance le même
lecteur dès le réveil — sans superposer son écran sur votre lanceur d'applications ou votre navigation.
La radio se rétablit d'elle-même sans intervention de l'application.

Pour la mise en veille, appuyez sous le bouton sur «Garder wDSP actif en veille» : cela ouvre la liste
blanche des applications en veille de l'autoradio, où il faut cocher wDSP.

Cette fonction est totalement nouvelle. Faites-moi savoir quel lecteur vous utilisez et s'il a bien repris.

-----------------------------------------------------------------------------
5. APPELS, ÉCRAN DE VEILLE, VOLUME
-----------------------------------------------------------------------------

- Lors d'un appel téléphonique, l'application libère immédiatement le micro et active le profil d'appel ;
  dès que l'appel prend fin, les réglages précédents sont restaurés.
- L'écran de veille se ferme à la seconde même où un appel débute.
- L'écran de veille s'affiche désormais correctement sur les appareils où la «vidéo flottante» ou la
  «navigation flottante» est cochée dans les réglages d'usine (il restait masqué auparavant).
- Le navigateur GPS sélectionné dans les réglages de l'autoradio est automatiquement ajouté aux
  exclusions de l'écran de veille pour ne pas cacher la carte. Si vous préférez afficher l'écran de veille
  par-dessus la carte, retirez-le de cette liste.
- La rangée inférieure de l'égaliseur ne tronque plus les boutons de l'amplificateur sur les écrans étroits
  ou verticaux : l'ensemble de la barre se réduit harmonieusement.
- Le volume de chaque source est mémorisé indépendamment et s'adapte aux modifications effectuées en
  dehors de wDSP.
- GALA ne laisse plus de palier de volume résiduel après chaque ralentissement.

-----------------------------------------------------------------------------
LANGUES
-----------------------------------------------------------------------------

L'interface est disponible en 30 langues et déclinaisons régionales.

-----------------------------------------------------------------------------
APPEL AUX TESTEURS
-----------------------------------------------------------------------------

1. PRÉRÉGLAGES AUTOMATIQUES. À votre oreille, le profil est-il correctement équilibré ? La scène sonore
   est-elle bien centrée ? Merci d'envoyer l'archive de mesure («Enregistrer l'archive» après la mesure) et
   le rapport système (Diagnostic → «Rapport système»). Les deux fichiers sont générés dans le dossier
   Download/wDSP.

2. SI L'ÉCRAN DE VEILLE N'APPARAÎT JAMAIS SUR VOTRE APPAREIL : laissez l'autoradio allumé pendant deux
   minutes sur n'importe quelle application hormis wDSP. Allez ensuite dans Paramètres wDSP → Diagnostic
   → «Screen Topology» → enregistrez et transmettez le fichier. Si l'écran de veille apparaît, appuyez
   plusieurs fois dessus (sur les zones de contrôle et sur les bords de l'écran) avant d'enregistrer : le
   fichier indiquera avec précision où les touches ont été reçues et comment l'application les a interprétées.

3. REPRISE DU LECTEUR — précisez le lecteur utilisé, après redémarrage ou veille, et s'il a repris la
   lecture comme prévu.

Merci de transmettre vos retours sur le groupe : https://t.me/wDSPapp/79

Si vous rencontrez le moindre dysfonctionnement, ajoutez un commentaire détaillant la situation. Si tout
fonctionne parfaitement, partagez simplement vos impressions sur le rendu musical dans votre voiture.

Merci beaucoup.
