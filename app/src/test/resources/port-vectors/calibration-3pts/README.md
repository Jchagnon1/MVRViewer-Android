# Vecteurs canoniques — calibration GPS en 3 points

`vectors.json` fixe ce que fait **iOS** pour la calibration guidée en trois
points (`SPEC-calibration-3-points.md`, dépôt iOS) : ajustement, précision,
point faux, guidage par nombre de pas, moyenne GPS et forme synchronisée.
Android et le web le rejouent et doivent obtenir les mêmes sorties, **aux
tolérances indiquées**. Guide de portage : `PORTAGE-cablage-calibration.md` à
la racine du dépôt web.

Les sorties viennent du **vrai code iOS compilé** :
`GeoThreePointCalibration.swift` (les maths) ; la partie pure de
`GeoLocationService.swift` (modèle à ancres `GeoCalibration`, relevé
`GeoSurvey`, forme synchronisée) ; les DTO de la section `calibration` de
`ProjectSnapshot.swift` ; `GeoPanelModel.swift` (ligne d'état) ; le catalogue
`Localizable.xcstrings`.

## Régénérer

Même commande que pour le câblage, depuis la racine du dépôt web :

```sh
tests/fixtures/_ios-gen/gen.sh [chemin du dépôt iOS]
```

Voir `../cabling-plan/README.md` pour les prérequis, le déterminisme et le
dossier de construction. Le générateur est dans `../_ios-gen/` :

- `gen.sh` compile les fichiers iOS **tels quels** ;
- il en **extrait** deux morceaux au moment de la génération, jamais recopiés
  à la main : la partie pure de `GeoLocationService.swift` (la classe
  CoreLocation qui suit appelle une méthode absente de macOS) et les DTO de
  calibration de `ProjectSnapshot.swift` ;
- `Stubs.swift` ne contient que des bouchons jamais appelés et l'enveloppe de
  synchro réduite au cas `calibration`.

Les points bruités sont **fabriqués** avec le générateur des tests iOS
(SplitMix64 et Box-Muller, graine indiquée dans `generator`), puis **écrits
explicitement** : on rejoue les points du fichier, jamais le tirage, sauf
dans le groupe facultatif `monteCarlo`.

## Contenu

| Groupe | Ce qu'il fixe |
|---|---|
| `constants` | seuils réels de `GeoThreePoint` (SPEC §6) : durées, précisions, pas par défaut 0,75 m, bases, tolérances… |
| `geodesy` | `longitudeDelta` (Δλ ramené dans [−180°, 180°]), `normalizedLongitude` (dans ]−180°, 180°]), ENU, inverse, rotation |
| `average` | moyenne GPS robuste (§3.2) : saut rejeté, poids, fixes invalides, pas de rejet s'il reste moins de 3 fixes dans la tolérance, plancher de 1 m, antiméridien |
| `fit` | ajustement rigide pondéré : points parfaits, bruit ±3 m, point faux **certain**, échelle douteuse, points trop proches ou alignés, confondus (`null`), et les **cas difficiles de la relecture iOS** (point caché à 15 m, une seule distance fausse avec deux suspects puis contrôle, contrôle ambigu → le plus probable, échelle sur triangle non équilatéral ou guidé, petit plan), précision **aux bords** d'un grand plan, contrôle qui améliore les bords, erreurs corrélées, antiméridien, ajustement provisoire à 2 points. `predictedError` aux points `probes` ; `syntheticAnchors`. `display` (transcription) donne l'écran de résultat : lignes de précision, écarts, alertes, action principale et secondaire, ligne d'état une fois appliqué |
| `guidance` | nombre de pas (`steps`), base visée et emprise rétrécie (`bounds`), repères 2, 3 et de contrôle **dans l'emprise**, accrochés aux éléments reconnaissables (grille de 684 repères, élément isolé, échantillonnage au-delà de 20 000), avec la distance depuis le point de départ et le nombre de pas à 0,75 m |
| `liveGuidance` | « Encore ≈ K pas » (2 points cohérents) ou « Parcouru : ≈ M pas » (sinon, sans minimum) — transcription |
| `sync` | les 2 ancres **synthétiques** et le `survey`. `section` = arbre JSON attendu ; `sectionCanonical` et `payloadJSONCanonical` = sortie de JSONEncoder iOS, clés triées (enveloppe Firestore `{"calibration":{"_0":…}}`) |
| `compat` | lecture et réécriture : calibration ordinaire, forme historique, relevé avec ou sans `edgePrecision`, `edgePrecision: null`, **méthode et clés inconnues**, relevé avec 1 ou 3 ancres (ignoré), relevé incomplet (rejeté par iOS), ordre des clés quelconque |
| `numberFormat` | forme des nombres qu'écrit JSONEncoder iOS, utile seulement pour reproduire les formes canoniques à l'octet |
| `otherMethodClearsSurvey` | « je suis ici » après un relevé : le relevé est effacé |
| `anchorModel` | le modèle **historique** à ancres (Android `GeoCalibration.kt`, web `geo.js`) doit replacer les points des ancres synthétiques, **antiméridien compris** |
| `monteCarlo` | facultatif : statistiques de 2 000 tirages (graine 42), à rejouer seulement si la plateforme reproduit le générateur |
| `texts` | textes fr/en du catalogue iOS (espaces insécables écrites `\u00a0`) |

## Conventions et tolérances

Le détail est dans `conventions`. En résumé :

- **unités** : le monde en mm ; l'ENU, les précisions et les distances en m ;
  `rotation` en radians ;
- **indices** : le premier point est l'origine ENU ; `outlier.index` et
  `suspects` sont 0-based dans `input.points` ; les numéros affichés sont
  1-based et désignent l'**emplacement** (`display.context.slots`) ;
- **égalités** : en cas d'égalité, le PREMIER candidat gagne. La grille fait
  41 × 41 points, parcourue x à l'extérieur et y à l'intérieur ; chaque
  candidat vaut `min + size × (i/40, j/40)` ;
- **distances** : √(dx² + dy²), sans `hypot`, et dans l'ordre des opérations
  du code iOS. Ainsi les repères proposés tombent sur **les mêmes points** à
  1e-6 mm près ;
- **tolérances** : angles 1e-9 rad ; mètres 1e-6 ; monde 1e-3 mm ;
  latitude et longitude 1e-10° (1e-9° pour le modèle à ancres) ; positions du
  modèle à ancres 0,01 mm (float32) ; booléens, entiers, suspects, nombres de
  pas et textes exacts ;
- **JSON synchronisé** : iOS écrit les clés dans un ordre **quelconque, qui
  change d'un lancement à l'autre** (dictionnaire haché, constaté). L'ordre
  des clés n'est donc **pas** un contrat :
  - comparer des **arbres** JSON : mêmes clés, mêmes valeurs, `worldX` et
    `worldY` égaux en float32, optionnels nuls **absents** ;
  - pour comparer des octets, comparer les formes **canoniques** (clés triées,
    nombres au format iOS, voir le guide §3.5).

## Contrôle indépendant

Avant le commit, une réimplémentation JavaScript indépendante, écrite à
partir de la spec, a rejoué la géodésie, la moyenne, tous les ajustements
(tous les champs), les repères, les ancres synthétiques et le modèle à
ancres : 1 536 vérifications sur 1 536 aux tolérances ci-dessus. Le
sérialiseur canonique JS du guide redonne aussi les 44 formes canoniques et
nombres de référence.
