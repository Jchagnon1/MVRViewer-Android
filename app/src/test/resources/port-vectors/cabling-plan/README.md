# Vecteurs canoniques — câblage sur le plan

`vectors.json` fixe, cas par cas, ce que fait **iOS** pour le parcours
« affecter sur le plan » (`SPEC-cablage-plan.md`, dépôt iOS). Android et le web
rejouent ces vecteurs dans leurs tests et doivent obtenir **exactement** les
mêmes sorties. Guide de portage : `PORTAGE-cablage-calibration.md` à la racine
du dépôt web.

Les sorties viennent du **vrai code iOS compilé**, pas d'une réécriture :
`CablingPlanAssist.swift`, `PowerCabling.swift`, `DMXCabling.swift`,
`CablingColorMode.swift`, `PanelPlacementMath.swift`, `ProjectNavigation.swift`
et le catalogue `Localizable.xcstrings`. Le commit iOS utilisé est écrit dans
`source.commit`.

## Régénérer

Depuis la racine du dépôt web, sur un Mac avec Xcode (swiftc ≥ 6.2) et le
dépôt iOS en place :

```sh
tests/fixtures/_ios-gen/gen.sh                                  # dépôt iOS : ~/Desktop/MVRViewer/MVRViewer
tests/fixtures/_ios-gen/gen.sh /chemin/vers/MVRViewer           # autre emplacement
git diff --stat tests/fixtures/cabling-plan tests/fixtures/calibration-3pts
```

La même commande régénère aussi `../calibration-3pts/vectors.json`. La sortie
est déterministe : sur le même commit iOS, deux générations donnent des
fichiers identiques à l'octet, donc un `git diff` vide. Le binaire est
construit dans `~/.mvrviewer-tools/scratch/port-vectors/build/`, ou dans le
dossier donné par `MVR_PORT_VECTORS_BUILD`. Le dépôt iOS n'est jamais modifié.
Si un fichier lu y a des modifications non commitées, `source.uncommittedChanges`
le signale.

`gen.sh` écrit aussi `MANIFEST.sha256` (empreinte de `vectors.json`) dans
chacun des deux dossiers. Après une régénération, recopier les deux fichiers
dans les ressources de test Android (voir le guide, §1.2), puis relancer les
deux suites de tests.

## Contenu

Toutes les entrées de câblage sont au **format de synchronisation** (sections
`powerCabling` et `dmxCabling`, mêmes noms de champs qu'iOS). Elles sont
décodées par le décodeur iOS **tolérant**.

| Groupe | Ce qu'il fixe | Comment le rejouer |
|---|---|---|
| `voieOrder` | ordre des voies pour ◀ ▶, voisine précédente ou suivante, bouts sans bouclage, famille, voie disparue, décodage tolérant | décoder `input`, construire la liste, puis comparer `voies`, `neighbors[].expect` et `exists[].exists` |
| `labels` | libellés FIGÉS « Soca A · C3 », « DMX A · D2 » ; « DMX B » pour une ligne simple ; nom vide ou fait d'espaces | `power(name, circuit)` et `dmx(name, core, coreCount)` |
| `sequences` | toucher (affecter, retirer, déplacer), cadre (ajout sans doublon, jamais de retrait), voie supprimée (rien n'est écrit), couleur et libellé d'étiquette de chaque projecteur après chaque geste | appliquer `steps[].do` dans l'ordre au DTO décodé, puis comparer `steps[].expect` |
| `status` | état de la barre : libellé, couleur, phase, compte, charge, limite, surcharge, valeurs inconnues, ◀ ▶ actifs. `display` donne les textes fr/en (« 3 800 / 3 680 W », « 550 / 512 canaux », « 1 projecteur », « Touchez pour affecter », « Voie supprimée ») | `metricsByFixture` = conso (W) ou empreinte (canaux) par projecteur. Un projecteur absent compte 0 et est « inconnu ». `null` signifie pas encore résolu |
| `memory` | mémoire de l'écran Câblage : onglet, position par onglet, voie à remettre sous les yeux une seule fois | appliquer `rememberWorkedVoie`, `remember` et `takeRestore`, puis comparer `expect` et `expectState` |
| `tabOpening` | onglet à l'ouverture : celui imposé par l'entrée, sinon le dernier retenu, sinon électrique | table |
| `returnAnchor` | ancrage final du retour : centré si la ligne tient (hauteur + 24 ≤ zone visible), sinon en haut | table |
| `listGeometry` | modèle iOS de la « ligne en tête », recommandé et non obligatoire. `positionHeaderAllowed` est la variante permise à Android et au web, qui peuvent défiler jusqu'à un en-tête | rejouer `report`, `scrolled`, `reset`, `remove` et `current` |
| `assignBar` | place de la barre en bas, au-dessus de la légende dépliée ou à sa droite | `assignBarInsets` ; les pt iOS valent des dp Android ou des px CSS |
| `colors` | couleur de chaque projecteur selon la famille, gris `0xFF737373` s'il n'est pas câblé, libellés d'étiquette, légende | table |
| `texts` | textes fr/en du catalogue iOS, avec leurs formes plurielles | contrôle de catalogue |

## Conventions et tolérances

Elles sont rappelées dans `conventions` en tête du fichier :

- une voie s'écrit `{kind: "power"|"dmx", distId, index}`, avec `index` à
  partir de 1. Les onglets portent les valeurs iOS `cabling` (« Électrique »),
  `dmx` et `powers` (« Puissances »). Chaque plateforme fait correspondre ses
  propres identifiants (web : `power`, `dmx`, `types` ; Android : 0, 1, 2) ;
- `colorArgb` est un entier **non signé** sur 32 bits. Une plateforme qui
  stocke un Int signé compare `v & 0xFFFFFFFF` ;
- **tout est exact** : entiers, chaînes, booléens. Les dictionnaires sont des
  objets à clés triées, et l'ordre de leurs clés ne compte pas ;
- `assignments` : on compare au minimum l'**ensemble** fixture → voie. Garder
  aussi le même ordre qu'iOS est recommandé (ajout en fin de liste), pas exigé ;
- en français, le séparateur de milliers est l'espace fine insécable U+202F
  (« 3 800 », écrit `\u202f` dans le fichier). Une plateforme dont ICU rend
  U+00A0 est tolérée ;
- les blocs marqués `"provenance": "transcription"` (textes de la barre,
  couleur « non câblé » du plan) recopient une vue SwiftUI, qui ne se compile
  pas hors de l'app. Leurs nombres et leurs phrases viennent quand même de
  Foundation et du catalogue iOS réels.

## Contrôle indépendant

Avant le commit, une réimplémentation JavaScript indépendante, écrite à
partir de la spec et non du générateur, a rejoué tout le fichier : 509
vérifications sur 509, mise en forme `fr-FR` / `en-US` d'`Intl` comprise.
