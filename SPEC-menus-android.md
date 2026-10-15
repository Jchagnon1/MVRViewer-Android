# Rangement des menus — adaptation Android (plan de portage)

Référence : la spec iOS **`~/Desktop/MVRViewer/MVRViewer/SPEC-menus.md`** (elle
fait foi pour la structure, les libellés et les règles gratuit/Pro) et
`DESIGN_UI.md` du même dépôt. Ce document dit **comment** la porter sur
Android, ce qui existe aujourd'hui (inventaire), ce qui change, ce qui manque,
et dans quel ordre l'implémenter.

État : **tranches A et B réalisées** (2026-10-15, voir *Réalisé* sous chaque
tranche, §10) ; tranches C à E à faire. Décisions de l'utilisateur sur Q1–Q3 : §9.0.
Base : branche `feature/v1.7-tilt-formats`, commit `e110bbc`
(539 tests unitaires verts + 4 tests réseau ignorés). Relu et corrigé après
critique (re-parcours du code, décisions, disparitions, sûreté des
tranches) : la liste des corrections est en §12.

Toute divergence avec iOS s'écrit ici d'abord (§8), jamais dans le code seul.
Specs Android livrées qu'il ne faut PAS casser : câblage sur le plan (barre
d'affectation, mémoire de l'écran Câblage, `CablingPlanAssist.kt`), calibration
3 points (`ThreePointCalibration*.kt`), fond satellite IGN (`SatelliteUi.kt`),
bibliothèque MA, barres ancrables (`ToolbarLayout.kt`, `AnchoredToolbars.kt`).

---

## 0. Décisions de l'utilisateur et leur traduction Android

Les 11 décisions iOS (SPEC-menus §0) sont **figées** ; on ne les re-litige pas.
Leur traduction :

| # | Décision iOS | Sur Android |
|---|---|---|
| 1 | Pastille « Vue » = points de vue de la vue courante + bascule ; « Vue plan » quitte le ⋯ | Même pastille (composable `ViewPill`), dans les deux vues. En 3D : Perspective, Dessus, Face, Arrière, Gauche, Droite, puis « Vue plan 2D ». Dans le plan : « Dessus » seul (le plan Android n'a pas d'autre projection, §8 D6) puis « Vue 3D ». Le menu « Points de vue » (icône caméra) de la barre 3D disparaît. |
| 2 | « Terminé » à gauche dans le plan | La flèche ← en haut à gauche du plan reste (TalkBack « Terminé », aujourd'hui « Vue 3D ») ; pendant une affectation elle ramène au Câblage (déjà en place, `SceneScreen.kt:1226`). Place et rôle conformes ; la forme (icône ← et non le mot « Terminé ») est une divergence visible, §8 D19. |
| 3 | Compte, Pro, GDTF Share dans « Compte & réglages », hors des familles de la scène | Écran plein « Compte & réglages » (remplace `AccountDialog`), ouvert par l'avant-dernière ligne du ⋯, par l'accueil et par l'outil `account`. |
| 4 | Fiche : portée unique, croix de fermeture | Croix « Fermer » : oui. Portée : **sans objet tant qu'Android n'a ni Pan 180°, ni Tilt 180°, ni côté d'étiquette** (rien à quoi l'appliquer) → absente ; Q3 tranchée : hors chantier (§9.0). |
| 5 | Même fiche dans le plan | Une seule `SelectionCard` dans les deux vues (aujourd'hui : une pastille de texte en 3D, une fiche texte dans le plan). |
| 6 | Plan importé lisible en gratuit, modifier = Pro | Le panneau s'ouvre pour tous ; chaque modification est gardée au toucher (aujourd'hui Pro à l'entrée, `PlanScreen.kt:1150`). |
| 7 | « Je suis ici » ET 3 points | Les deux dans le panneau Position GPS, dans les deux vues. |
| 8 | Opacité du satellite dans le panneau GPS seulement | Le curseur flottant du plan (`PlanScreen.kt:2259`) et les curseurs du ⋯ (`SceneOptions.kt:351`) disparaissent. |
| 9 | iPad : barre préremplie | **Tablette Android** (plus petite largeur ≥ 600 dp) : barre gauche préremplie Patch, Univers, Câblage, Calques, dans les deux vues. Téléphone : aucune barre ancrée. La barre flottante historique est **retirée** (Q1 tranchée, §9.0). |
| 10 | Fiche compacte en paysage | Même règle sur téléphone en paysage (hauteur < 480 dp, le seuil déjà utilisé par `PlanScreen.kt:2452`). |
| 11 | Pastille FR à moitié visible acceptée | Sans objet sur Android (la pastille n'a pas de section « Mes vues ») ; si un menu demande un court défilement en paysage, il est accepté de même. |

---

## 1. Règles transverses (Android)

### 1.1 Vocabulaire

Mêmes règles qu'iOS §1.1 : « projecteur », « calque », « N° », « cadre »,
« canaux ». Pluriels par `<plurals>` (`one` / `other`) et
`pluralStringResource`, jamais « (s) ». Nombres formatés selon la langue.
Les chaînes **touchées par ce chantier** passent en pluriels ; la liste des
« (s) » restants est en §11.

### 1.2 Gratuit / Pro

- Gratuit = tout **lire** ; Pro = tout ce qui **écrit** dans le projet.
- Aucune entrée n'est masquée, grisée ni désactivée selon Pro ; la toucher
  ouvre l'écran Pro (`ProAccess.gate(raison) { … }`, `ProAccess.kt:158`).
- Garde aux **entrées** existantes (inchangées) : « modifier le patch »
  (`FixtureDetail.kt:155`, à l'enregistrement — §8 D15), « modifier le
  câblage » (`CablingState.kt:69`), « affecter le câblage », « renseigner une
  consommation » (`PowerLibraryState.kt:70`), « importer un plan », « importer
  un objet 3D », « régler les objets 3D importés », « calibrer la position
  GPS », « annuler une modification ». Filet cloud et réception inchangés.
- Un **curseur, interrupteur ou champ** Pro se garde dans son setter :
  `ProAccess.gatedSetter(raison) { valeur = nouvelle }`. Pour un gratuit, le
  premier changement ouvre l'écran Pro et la valeur ne bouge pas ; les suivants
  du même geste sont sans effet (fenêtre glissante de 0,8 s, comme
  `ProRefusalThrottle` iOS). Jamais `enabled = isPro`.
- Une entrée n'est absente que si la donnée manque (pas de plan importé, pas
  de câblage, pas de modèle importé, aucun élément masqué, pas de classe,
  pas de calibration) ou si elle n'a pas de sens dans la vue (Décor 3D dans le
  plan, Placement sur le plan en 3D) ou si **la fonction n'existe pas sur
  Android** (§8 D5).
- Ouvrir « Partager ce projet » devient **gratuit** (la garde reste à la
  publication, `SyncViewModel.kt:291`) : les gardes à l'entrée de
  `SceneScreen.kt:1109` et `:1221` sont retirées.
- Une **ligne de liste ne déclenche jamais une action destructive** à elle
  seule (se déconnecter, retirer, réinitialiser) : l'action est un bouton
  nommé, rouge s'il détruit.

### 1.3 Présentation : destinations en surimpression

Aujourd'hui, Patch, Univers, Câblage, GDTF Share et la bibliothèque MA sont des
**modes** de `SceneScreen` (`SceneMode`, `SceneScreen.kt:42`) qui **remplacent**
la vue : la 3D est détruite (reconstruction complète au retour) et le plan
aussi (zoom, sélection et outils perdus) ; le retour ramène toujours à la 3D
(`onBack = { mode = SceneMode.THREE_D }`), même si l'on venait du plan.

Règle Android (équivalent de « par-dessus le plan sans le fermer », iOS §1.3) :

- `SceneScreen` garde une **vue de base** (`THREE_D` ou `PLAN`, seule valeur
  persistée par `ViewStateStore`, comme aujourd'hui) et une **destination**
  facultative (`ProjectDestination`, §4.1) dessinée en **plein écran opaque
  par-dessus** la vue de base, qui reste composée dessous.
- Fermer la destination (← ou retour système) rend la vue de base **intacte**
  (caméra 3D, zoom du plan, sélection, outils).
- La bascule 3D ↔ plan, elle, reste un remplacement (une seule vue de base
  composée à la fois : mémoire inchangée).
- À vérifier à l'émulateur (tranche A) : la surface opaque cache entièrement
  la `SceneView` et capte tous les touchers ; le coût du rendu 3D qui continue
  dessous est mesuré (images par seconde, chauffe). Repli si inacceptable :
  masquer la `SceneView` pendant la destination, ou revenir au remplacement
  avec une « vue d'origine » mémorisée (le plan serait alors recréé).

### 1.4 Ce qui ne change pas

- `ToolId.rawId` **figés** (JSON persistant, communs avec iOS) : on change les
  libellés et icônes, on ajoute des cas, on n'en renomme aucun.
- `PanelLayoutStore.PanelID` : on ajoute des cas, on n'en renomme aucun ; un
  panneau qui change de coin d'ancrage reçoit un **nouvel** identifiant (sinon
  son ancien décalage, mesuré depuis l'autre coin, serait réappliqué).
- Clés `ViewStateStore`, `BackgroundColorStore`, `ProjectStore` : inchangées.
- Gestes : appui long sur un projecteur → fiche patch (3D et plan) ; toucher
  une étiquette du plan pour la saisir ; pincer / glisser.
- Barre d'affectation du câblage et assistant 3 points : contenu et place
  inchangés.

### 1.5 Contraintes techniques

- `Scene3DScreen.kt` (3 159 lignes), `PlanScreen.kt` (3 624) et
  `SceneScreen.kt` (1 380) sont déjà énormes (risque de méthode JVM trop
  grosse, lisibilité) : **toute nouvelle surface est un composable dans son
  propre fichier** (menu, pastille, rangée du haut, panneau GPS, panneau Plan
  importé, panneau d'étiquettes, fiche, Compte & réglages), et toute règle est
  une fonction **pure** testée en JVM.
- Tests : JVM seulement (`app/src/test`, pas de Robolectric ni d'`androidTest`).
  Les textes se vérifient sur les vrais `strings.xml` (`TestRes.kt`).
- Chaque nouvelle chaîne entre dans `values/strings.xml` **et**
  `values-en/strings.xml` (anglais britannique, les valeurs `en` d'iOS mot pour
  mot) ; les clés retirées sont supprimées des deux fichiers.
- Les contrôles Compose posés sur la `SceneView` sont visibles et cliquables
  (constaté, `Scene3DScreen.kt:1956`) ; un `pointerInput` posé sur toute la
  scène capte le toucher natif : n'en poser qu'en mode cadre (règle existante,
  `Scene3DScreen.kt:2129`).
- Jamais de `Slider` ni de sélecteur de couleur dans un `DropdownMenu` (le
  curseur d'opacité actuel du ⋯ s'en va avec la décision 8).
- Une lambda Compose ne capture jamais un index de liste mutable : clés stables.
- Build et tests : `./gradlew :app:testDebugUnitTest` et `:app:assembleDebug`
  (JBR d'Android Studio, `JAVA_HOME` et `ANDROID_HOME` de la note de build) ;
  un seul Gradle à la fois ; commandes bornées dans le temps ; travail dans un
  worktree sous `~/.mvrviewer-worktrees` (jamais `/tmp`), commits de travail.

---

## 2. Inventaire de l'existant (base `e110bbc`)

Chemins relatifs à `app/src/main/java/com/minou/mvrviewer/`.

### 2.1 Hôte du projet — `ui/SceneScreen.kt`

| Élément | Où | Comportement |
|---|---|---|
| Modes de la scène | `:42` `enum SceneMode { THREE_D, PLAN, PATCH, GDTF_SHARE, UNIVERSE, CABLING, MA_LIBRARY }` | un seul écran à la fois (`when(mode)`, `:1060-1314`) |
| Vue retrouvée à la réouverture | `:94`, `:125` | 3D ou plan, via `ViewStateStore` |
| Dispositions des barres | `:139-142` | chargées puis **réécrites à chaque composition** (`LaunchedEffect(layout3D)`) : une disposition vide est donc enregistrée dès la 1re ouverture (conséquence §3.13) |
| Fiche patch (appui long) | `:148`, `:1370-1378` | `FixtureDetailSheet` par-dessus la vue courante |
| Cible d'affectation câblage | `:239`, `finishAssign` `:265`, désarmement `:271` | « Terminé » → `mode = CABLING` |
| Profil MA reçu | `:161-163` | `mode = MA_LIBRARY` |
| Retour système | `:1047` (3D → `onClose`), `:1051` (sous-vue → 3D ; plan en affectation → `finishAssign`), `:1055` (dialogue de synchro → fermer) | |
| Classes | `:1319-1332` | `ClassesSheet` hors du `when`, valable en 3D et plan |
| Bannière « nouvelle version » | `:1337-1345` | haut-centre, **carte pleine largeur** (`SyncUI.kt:438`) posée par-dessus la barre du haut, dans tous les modes |
| Pastille de synchro | `:1348-1350` | **bas-droite** (« ☁ … »), seulement si un projet partagé est ouvert ; posée hors du `when` : visible par-dessus **tous** les modes (3D, plan, Patch, Univers, Câblage, GDTF Share, Bibliothèque MA) |
| Plan importé : import, persistance, synchro | `onSetReferencePlan` `:1143-1177` (persiste, téléverse, pousse le manifeste, masque les calques éteints du DXF) ; `commitTransform` `:952` ; mais la **persistance débouncée du placement** est dans `PlanScreen.kt:819-834` | |
| Dialogues Compte / Partager / Historique / Rejoindre | `:1034-1037`, `:1352-1362` | `AlertDialog`s |
| Garde « partager un projet » à l'entrée | `:1109`, `:1221` | |
| Garde des calques DXF | `:1188` | « modifier les calques du plan importé » |

### 2.2 Vue 3D — `ui/Scene3DScreen.kt`

| Élément | Où | Détail |
|---|---|---|
| Barre du haut | `TopAppBar` `:1962-2036` | opaque, au-dessus de la scène |
| ← | `:1978-1982` | `onClose` (retour à l'accueil), TalkBack « Projets » |
| Titre | `:1963-1977` | « 3D · N objets 3D · M proj. GDTF (tronqué) », ou étape + % pendant le chargement |
| Menu « Points de vue » | `:1987-2008` (icône caméra) | Dessus, Face, Côté, Isométrique, Réinitialiser la vue |
| Menu ⋯ | `:2009-2034` (`SceneOptionsMenu`, §2.3) | |
| Barre de progression | `:2039-2044` | pleine largeur sous la barre |
| Calque de cadre | `:2134-2160` | `pointerInput` seulement en mode cadre |
| Recherche | `:2254-2273` | champ **toujours visible** haut-droite, « N° projecteur », rien si aucun résultat (`doSearch3D` `:1596`) |
| Barre flottante historique | `:2278-2282` | bas-gauche, `ToolbarLayout.floating3D` |
| Barres ancrées | `:2287` | |
| Mention IGN | `:2291-2294` | bas-droite |
| Bandeau de mesure | `:2299-2325` | bas-centre |
| « Fiche » de sélection | `:2328-2343` | pastille de texte « N° x · Nom » ou « n projecteurs sélectionnés », **aucune action** |
| Panneau Modèles importés | `:2346-2368` | `MovablePanel(IMPORTED_MODELS_3D)`, **bas-droite** |
| Dialogue des formats 3D | `:2369-2386` | |
| « Chargement… » / message d'import | `:2388-2407` | haut-centre |
| Feuille Calques (LOD) | `:2435-2448` | `LayersSheet` « Calques · Vue 3D » |
| Personnaliser la barre | `:2450-2459` | `ToolbarCustomizeSheet` |
| Couleur du fond (outil) | `:2463-2471` | `BackgroundColorDialog` |
| Outils (description unifiée) | `tools3D` `:1834-1954` | RECT, MEASURE, SOLO, CLEAR_SEL, GPS, GPS_MARKER_SIZE, IMPORT_MODEL (libellé variable), LAYERS (« Calques » en dur), LABELS, LAYER_COLORS, SATELLITE, LABEL_SIZE (cycle), BACKGROUND, CAM_RESET, PLAN_VIEW, PATCH, UNIVERSE, CABLING (« Câblage électrique »), GDTF_SHARE (« GDTF Share » en dur, `:1938` : ouvre l'écran combiné connexion + modèles), ACCOUNT (« Compte » en dur), SHARE_PROJECT, HISTORY (ces trois-là seulement si la synchro est branchée) |
| Toucher | `handleTap` `:1711` | **ajoute / retire** le projecteur visé (sélection multiple d'office) ; vide → tout désélectionner ; en mesure, désigne A puis B |
| Appui long | `handleLongPress` `:1695` | sélection unique puis fiche patch |
| Pas de double-toucher, pas de panneau Plan importé, pas d'import de plan en 3D | | |

### 2.3 Menu ⋯ actuel — `ui/SceneOptions.kt` (`SceneOptionsMenu`, `:173-465`)

Un seul composable, mais un **contenu différent par vue** (paramètres nuls =
entrées absentes) et une **longue liste à plat** qui défile. Icône `MoreVert`
(`:266`), TalkBack « Options ».

Ordre en **3D** (paramètres passés `Scene3DScreen.kt:2009`) :
Vue plan · Liste de patch · Univers DMX · Câblage électrique · GDTF Share
(modèles 3D) · Bibliothèque MA — *Outils* : Calques… · Classes… (si classes) ·
Sélection rectangle · Mesurer une distance · Solo (si sélection ou solo) ·
Effacer la sélection (si sélection) · Ma position (3D) (si calibré) · Taille du
marqueur (si position) · Importer un modèle 3D… / Modèles 3D (n) ·
Personnaliser la barre d'outils… — *Synchro* : Compte · Partager ce projet ·
Historique des modifications · Rejoindre un projet — *Couleur du fond* : 5
préréglages · Personnalisée… — Fond satellite (carte) + message + curseur
d'opacité (si calibré) — Couleurs par calque — Étiquettes, et si allumées :
6 champs à cocher (+ « ↳ bloc séparé » par champ), Taille : …, Écart : …,
Masquer si trop dézoomé.

Ordre dans le **plan** (`PlanScreen.kt:2078`) : Vue 3D · Liste de patch ·
Bibliothèque MA — *Outils* : Classes… · Sélection rectangle · Masquer des
éléments · Solo · Mesurer une distance · Tout réafficher (si masqués) · Vider
le solo (si solo) · Effacer la sélection · Ma position GPS · Calibrer : je
suis ici (si position) · Calibrer en 3 points (guidé)… · Export PDF ·
Importer un plan… / Plan de repère · ligne d'état de la calibration ·
Personnaliser la barre d'outils… — *Synchro* (4) — *Couleur du fond* —
Fond satellite + opacité (si calibré) — Couleurs par calque — Coloration : …
(cycle, si câblage) — Décor / structure — Légende — Étiquettes + champs,
Étiquettes de la sélection, Étiquettes du même type (ce calque), Taille,
Écart, Masquer si trop dézoomé, Replacer les étiquettes.

Les bascules gardent le menu ouvert, les actions le ferment (`:98-107`).

Le ⋯ de la **3D** montre aussi « ↳ bloc séparé » par champ et « Masquer si
trop dézoomé » (même composable) alors que ces deux réglages **n'ont aucun
effet en 3D** (`labelDetached` et `hideLabelsWhenZoomedOut` ne sont lus que
par le plan, `PlanRender.kt:395`) : ils ne sont pas portés dans le panneau
3D (§3.8), ce qui est dit ici pour que leur départ de la 3D ne passe pas
inaperçu.

### 2.4 Vue plan — `ui/PlanScreen.kt`

| Élément | Où | Détail |
|---|---|---|
| ← | `:1994-1998` | haut-gauche ; retour 3D, ou « Terminé » pendant une affectation |
| Titre | `:1980-1991` | pastille « Plan · N projecteur(s) » |
| Légende | `:2008-2076` | **haut-gauche** (`LEGEND_TOP_ANDROID = 100`, `CablingPlanAssist.kt:757`), calques ou distributeurs |
| Menu ⋯ | `:2077-2155` | haut-droite |
| Recherche | `:2164-2220` | champ toujours visible sous le ⋯ |
| Barre flottante historique | `:2226-2230` | bas-gauche, 12 outils (`ToolbarLayout.floatingPlan`) |
| Barres ancrées | `:2234` | |
| Curseur d'opacité flottant + mention IGN | `:2241-2280` | bas-centre / bas-droite, montent au-dessus du panneau 3 points et de la barre d'affectation |
| Panneau Plan importé | `:2283-2441` | `MovablePanel(REF_PLAN_PLACEMENT_PLAN)` **bas-droite** ; Visible, Déplacer (4 flèches), Rotation ±5°, Rotation verticale ±5° (sans effet visible en 2D), Échelle ÷ ×, Homothétie (curseur log, saisie, remise à 1), Calques (n) à cocher, Retirer ; **Pro à l'entrée** (`:1150`) |
| Barre d'affectation | `:2449-2482` | `CablingAssignBar`, inchangée |
| Panneau 3 points | `:2487-2511` | `ThreePointCalibrationPanel`, inchangé |
| Panneau Export PDF | `:2517-2640` | `MovablePanel(EXPORT_PLAN)` bas-droite : nom, Ajouter la vue, liste (↑ ↓ supprimer, toucher = renommer), Générer · Envoyer · Imprimer, « n vue(s) » |
| Renommer une vue | `:2643-2672` | `AlertDialog` |
| Aide de calibrage | `:2675-2689` | bas-centre, jaune |
| Aide de mesure | `:2693-2712` | bas-centre |
| Fiche / bannière | `:2717-2751` | fiche texte (« #N  nom / type / calque · DMX x ») ou bannière jaune « n projecteurs sélectionnés », sans action |
| Personnaliser / fond / étiquettes | `:2756`, `:2768`, `:2783` | panneau `LabelSettingsPanel` (`:2803`) **seulement par l'outil ancré** `LABEL_SETTINGS`, `MovablePanel(LABELS_PLAN)` **déjà en bas-gauche** ; contenu : « Afficher les étiquettes », champs + « Bloc séparé » par champ, Taille (cycle), « Fermer » en texte — **ni Écart ni « Masquer si trop dézoomé »** |
| Import du plan | `importLauncher` `:452-516` | analyse du fichier, puis **centrage sur `data.cx` / `data.cy`** (données du plan) avant `onSetReferencePlan` ; ouvre le panneau |
| Persistance du placement | `:819-834` | `dxfVersion` → `onTransformTouched` (immédiat) ; sauvegarde **débouncée** `ProjectStore.saveTransform` + `onTransformChanged` (journal, push) — vit dans le plan |
| Outils | `toolsPlan` `:1076-1177` | RECT, MASK, SOLO, MEASURE, SHOW_ALL, CLEAR_SOLO, CLEAR_SEL, GPS, CALIBRATE, CALIBRATE_3, SATELLITE, EXPORT_PDF, DXF (libellé variable), LABELS, LABEL_SETTINGS, LAYER_COLORS, STRUCTURE, LEGEND, BACKGROUND — **ni Patch, ni Univers, ni Câblage, ni Calques** |
| Modes de toucher | `toggleTouchTool` `:1026`, `PlanScreenRules.kt:26` | cadre, masquer, solo, mesure, ancre (exclusifs) |
| Gestes | glisser (cadre, masque) `:1229`, pincer/glisser `:1330`, toucher / appui long `:1370-1600`, glisser une étiquette saisie `:1626` | toucher = sélection **unique** (en mode cadre : ajoute / retire) |
| Projection | — | **dessus seulement** (`:107`) |

### 2.5 Barres d'outils — `ui/ToolbarLayout.kt`, `ui/AnchoredToolbars.kt`, `ui/ToolbarCustomizeSheet.kt`

- `ToolId` `ToolbarLayout.kt:30-95` (rawId camelCase communs avec iOS) ;
  défauts vides `:173-176` ; **barres flottantes historiques**
  `floating3D` `:183` (6 outils) et `floatingPlan` `:189` (12 outils), rendues
  par défaut en bas-gauche (`AnchoredToolbars.kt:112`) tant qu'un outil n'est
  pas ancré ailleurs.
- `ToolbarLayoutStore` (`ToolbarLayoutStore.kt:12`) : clés
  `toolbar.layout.scene3D` / `toolbar.layout.plan`.
- `ToolId` **sans aucun `ToolSpec`** (jamais rendus, jamais ancrables) :
  `COLOR_MODE` (`colorMode`), `SEARCH` (`search`), `CAM_PRESETS`
  (`cameraPresets`).
- `ToolbarCustomizeSheet` (`:49`) : texte d'aide `toolbar_customize_help`,
  puces Haut / Bas / Gauche / Droite / Aucun, « Réinitialiser », « Terminé ».
- Boutons : `FilledIconToggleButton` jaune quand actif (`AnchoredToolbars.kt:145`),
  `contentDescription` = libellé de l'outil.

### 2.6 Écrans (destinations actuelles)

| Écran | Où | Barre | Notes |
|---|---|---|---|
| Liste de patch | `ui/PatchListScreen.kt:57` | ← « Retour 3D », titre « Patch » | recherche, facettes Calque/Type/Mode/Univers, doublons de N° ; toucher → `FixtureDetailSheet` ; **pas de multi-sélection** |
| Fiche patch | `ui/FixtureDetail.kt:221` | `ModalBottomSheet` | nom, adresse, type, mode, conso + « ✓ C'est bon » (`:528-536`), Enregistrer (garde `:155`) |
| Univers DMX | `ui/DmxUniverseScreen.kt:91` | ← , titre « Univers n » + menu des univers | inspecteur de canal `:281` sans action |
| Câblage | `ui/CablingScreen.kt` | ← « Retour 3D », ⚙ Réglages (onglet Électrique), icône partage → menu des 4 PDF (`:286-322`) | onglets **Électrique / DMX / Puissances** (`:325-328`), mémoire d'onglet (`:171`, `CablingScreenTab.opening`, `CablingPlanAssist.kt:44-58`) |
| GDTF Share | `ui/GdtfShareScreen.kt:112` | ← , « GDTF Share » | **déconnecté : seulement le formulaire** ; connecté : « Améliorer tous », liste des types, recherche par type, Se déconnecter |
| Bibliothèque MA | `ui/MaLibraryScreen.kt:97` | ← , « Bibliothèque MA » | rapport d'import « OK » (`:568`) |
| Calques | `ui/LayersSheet.kt:55` | `Dialog` | LOD par calque seulement (« Calques · Vue 3D »), « Tout en Auto », « Terminé » |
| Classes | `ui/ClassesSheet.kt:44` | `Dialog` | bascules, Tout masquer/afficher, « Terminé » |
| Couleur personnalisée | `ui/BackgroundColorPicker.kt` | `AlertDialog` | « OK » (`:82`) |
| Panneau Modèles importés | `ui/ModelPlacementPanel.kt:84` | flottant 3D | titre « Modèles 3D (%d) » (clé `tool_models_fmt`, partagée avec l'outil) |

Flèche ← : TalkBack **« Retour 3D »** (`common_back_3d`) sur Patch
(`PatchListScreen.kt:105`), Univers (`DmxUniverseScreen.kt:124`), Câblage
(`CablingScreen.kt:277`), GDTF Share (`GdtfShareScreen.kt:169`) et
Bibliothèque MA (`MaLibraryScreen.kt:457`, faux aussi quand elle est ouverte
depuis l'**accueil**). Les volets internes (recherche GDTF Share, connexion,
pages de la bibliothèque MA) ont leur propre ← « Retour » et leur propre
retour système : ce sont des **sous-volets** de l'écran, pas des écrans de
`SceneScreen`.

### 2.7 Compte et synchro — `ui/SyncUI.kt`

- `AccountDialog` `:101` : `AlertDialog`, titre **variable** (« Compte » /
  « Créer un compte » / « Connexion »), section Pro (`ProAccountSection` `:67`,
  **textes en dur non traduits**, dont un message de refus de rattachement de
  l'achat, `ProAccess.accountLinkMessage`), compte ou formulaire, « Supprimer
  mon compte » en rouge, libellé du serveur (« Firebase » / « Local (démo) »),
  bouton « Déconnexion » (`sync_sign_out`, iOS : « Se déconnecter »).
- `ShareProjectDialog` `:247`, `JoinProjectDialog` `:321`, `HistoryDialog`
  `:365` (annuler : garde Pro, `SceneScreen.kt:985`), `SyncStatusBadge` `:420`,
  `MvrVersionBanner` `:438`.
- Pas de bascule « Forcer le backend local » à l'écran (`BackendSelector`
  n'est forcé que par les tests).

### 2.8 Accueil, lecture, erreur — `ui/HomeScreen.kt`, `MainActivity.kt`

- Accueil (`HomeScreen.kt:53`) : « Ouvrir un fichier .mvr » (principal),
  formats, « Bibliothèque MA », carte « Créez votre compte (gratuit) »
  (`:264-280`, **texte en dur**), « Mon compte » / « Se connecter », « Rejoindre
  un projet », projets partagés, erreur (`:177-184`), projets récents (✕ pour
  en retirer un), rapport de diagnostic. Le compte s'y ouvre en
  `AccountDialog` posé par `HomeScreen` lui-même (`:237`) ; la bibliothèque MA
  de l'accueil est hébergée par `App` (`HomeMaLibraryHost`, `MainActivity.kt:153`).
- Lecture du .mvr : le même écran affiche barre + étape + nom de fichier
  (`:89-111`) — **aucun bouton pour abandonner**.
- Erreur d'ouverture : message sur l'accueil, bouton Ouvrir toujours là
  (**pas d'impasse**).
- Construction 3D : progression dans la barre 3D, ← disponible (ferme le
  projet ; la libération à la sortie est déjà sûre, `Scene3DScreen.kt:2418`).
- `MainActivity.kt:49-58` : crochets DEBUG par extras d'intent (`MVR_PRO`,
  `MVR_GDTFSHARE_MOCK_DIR`), seulement si l'app est débogable.

### 2.9 Écran Pro

`ProPaywallHost` posé une fois par-dessus tout (`MainActivity.kt:72`), ouvert
par `ProAccess.gate`.

---

## 3. Structure cible

### 3.1 Haut de la scène : une rangée commune

Un composable `SceneTopRow` (fichier à part), **identique dans les deux vues**,
posé en surimpression (la `TopAppBar` 3D disparaît, la scène prend toute la
hauteur — DESIGN_UI §6 : l'habillage se fait oublier) :

- **à gauche** : ← (48 dp).
  - 3D : ferme le projet (comme aujourd'hui), TalkBack « Fermer le projet ».
  - Plan : TalkBack « Terminé » ; retour à la 3D, ou au Câblage pendant une
    affectation (inchangé, décision 2).
- **à droite** : `[Sélection par cadre] [Multi-sélection] [⋯]`, ronds de
  48 dp, rendu `ToolbarButton` (jaune plein, glyphe noir quand actif). Un
  bouton permanent est **masqué quand son outil est ancré** dans une barre.
  TalkBack : « Sélection par cadre », « Multi-sélection », « Plus d'options ».
  Le bouton Multi-sélection existe (Q2 tranchée, §9.0 : toucher remplace,
  Multi-sélection ajoute).
- **sous la rangée** :
  - 3D : bloc d'état à gauche (§3.5), pastille « Vue » à droite (§3.3) ;
  - plan : pastille « Vue » **centrée** ; la légende commence sous elle
    (`LEGEND_TOP_ANDROID` passe de 100 à ~112 dp, à mesurer ; la barre
    d'affectation lit déjà le cadre mesuré de la légende).
- Pendant le chargement 3D : la fine barre de progression pleine largeur reste
  tout en haut, au-dessus de la rangée.
- La bannière « nouvelle version » (carte pleine largeur) se pose **sous** la
  rangée, jamais sur ← ni sur ⋯ (aujourd'hui elle couvre la barre du haut),
  dans les deux vues ; elle peut couvrir le bloc d'état et la pastille le
  temps qu'on y réponde (« Rouvrir » / « Plus tard »).
- Dans le **plan**, la pastille de synchro (projet partagé seulement) se pose
  dans la colonne de droite sous la rangée, alignée à droite, bornée par le
  cadre mesuré de la pastille « Vue » centrée (sinon elle passe sous la
  pastille, toujours à droite) — §8 D21.
- Comportements inchangés : le cadre force le multi et coupe la mesure ;
  couper le multi vide la sélection et coupe le cadre ; dans le plan, armer le
  cadre ferme l'assistant 3 points.

### 3.2 Menu ⋯ (un seul, identique en 3D et dans le plan)

Modèle pur `MainMenuModel` (§4.4) + un composable `MainMenu` (fichier à part)
qui le dessine dans un `DropdownMenu` ancré au bouton ⋯. Icône `MoreVert` (⋮,
le débordement Android, §8 D3), TalkBack « Plus d'options ».

**Familles en pages** : toucher « Affichage ▸ » remplace le contenu du même
menu par la famille, avec en tête une ligne « ‹ Affichage » qui revient au
premier niveau ; un choix de valeur (Couleur du fond, Colorer par câblage)
ouvre de même une 3e page (liste à boutons radio). Le retour système dans une
page revient au premier niveau ; au premier niveau, il ferme le menu. (Le
`Popup` du `DropdownMenu` reçoit lui-même le retour et ferme tout : le
`BackHandler` des pages se pose **dans** le contenu du menu — à vérifier à
l'émulateur en tranche C ; repli : le retour ferme le menu entier, la ligne
« ‹ Famille » restant le chemin vers le premier niveau.)
Profondeur maximale : menu → famille → valeur (iOS §2.8).

Les **bascules gardent le menu ouvert** (comportement Android actuel) ; les
actions le ferment. L'état d'une bascule est dessiné par le style du menu
(coche à droite pour une ligne ; fond `secondaryContainer` pour une case de
rangée), jamais par le jaune d'outil.

#### 3.2.1 Premier niveau — 10 lignes dans les deux vues

| # | fr | en | Icône Material | Type | Apparition | Accès |
|---|---|---|---|---|---|---|
| 1a | Mesurer | Measure | `Straighten` | bascule (rangée 1) | toujours | gratuit |
| 1b | Étiquettes | Labels | `Label` | bascule (rangée 1) | toujours | gratuit |
| 1c | Calques | Layers | `Layers` | ouvre la feuille Calques (§3.10) | toujours | gratuit |
| 2a | Patch | Patch | `List` | ouvre la Liste de patch (rangée 2) | toujours | gratuit (enregistrer : Pro) |
| 2b | Univers | Universes | `GridView` | ouvre Univers DMX | toujours | gratuit |
| 2c | Câblage | Cabling | `Bolt` | ouvre le Câblage sur son dernier onglet | toujours | gratuit (modifier : Pro) |
| — | *séparateur* | | | | | |
| 3 | Affichage ▸ | Display | `Visibility` | famille §3.2.2 | toujours | gratuit |
| 4 | Masquer & isoler ▸ | Hide & isolate | `VisibilityOff` | famille §3.2.3 | **toujours** (3D : solo seulement, §8 D4) | gratuit |
| 5 | Patch & câblage ▸ | Patch & cabling | `Cable` | famille §3.2.4 | toujours | — |
| 6 | Plan & décor ▸ | Plan & scenery | `Layers` (contour) | famille §3.2.5 | toujours | — |
| 7 | Position GPS… | GPS position… | `LocationOn` | ouvre le panneau §3.6 | toujours | gratuit (calibrer : Pro) |
| 8 | Partager & exporter ▸ | Share & export | `Share` | famille §3.2.6 | toujours | — |
| — | *séparateur* | | | | | |
| 9 | Compte & réglages… | Account & settings… | `AccountCircle` | ouvre l'écran §3.11 | toujours | gratuit |
| 10 | Fermer le projet | Close the project | `Close` | ferme le projet (§3.2.7) | toujours | gratuit |

- Les rangées 1 et 2 sont un composable `MenuIconRow` : trois cases icône +
  libellé (`labelSmall`, deux lignes au plus), largeur égale, posé comme
  premier contenu du `DropdownMenu`. TalkBack : chaque case est un bouton (ou
  une bascule cochée / non cochée pour Mesurer et Étiquettes).
- Disparaissent du premier niveau : Vue plan / Vue 3D (→ pastille), Liste de
  patch, Univers DMX, Câblage électrique, GDTF Share (modèles 3D),
  Bibliothèque MA, toute la section *Outils*, la section *Synchro* (dont
  « Rejoindre un projet », qui reste sur l'accueil), la section *Couleur du
  fond*, le satellite et son curseur, les options d'étiquettes.
- « Mesurer » reste exclusif du cadre (comme aujourd'hui dans chaque vue).

#### 3.2.2 ⋯ › Affichage

| Section | fr | en | Type | Apparition |
|---|---|---|---|---|
| (sans titre) | Réglage des étiquettes… | Label settings… | ouvre le panneau §3.8 | **toujours** |
| Couleurs / Colours | Couleurs par calque | Colours per layer | bascule | toujours |
| Couleurs | Colorer par câblage ▸ | Colour by cabling | choix de valeur : Calque · Socapex · Ligne DMX (modes ayant des données) | plan, s'il y a plus d'un mode |
| Couleurs | Couleur du fond ▸ | Background colour | choix de valeur : préréglages de la vue + « Personnalisée… » (dialogue) | toujours (réglages 3D et plan distincts, inchangés) |
| (sans titre, **Android**) | Décor / structure | Set / structure (valeur `en` actuelle de `opt_structure`) | bascule | plan seulement |
| (sans titre, **Android**) | Légende | Legend | bascule | plan seulement |
| (sans titre, dernière) | Barre d'outils de cette vue… | Toolbar for this view… | ouvre `ToolbarCustomizeSheet` de la vue courante | toujours |

Absents (fonctions inexistantes sur Android, §8 D5) : section « Rendu 3D »
(Textures & matériaux, Éclairage général, Sol virtuel) et « Qualité ▸ ».

#### 3.2.3 ⋯ › Masquer & isoler

| fr | en | Type | Apparition |
|---|---|---|---|
| Masquer des éléments | Hide items | bascule du mode masquage | **plan** |
| Isoler (solo) | Isolate (solo) | plan : bascule du mode solo ; 3D : isole la sélection / retire l'isolement (action actuelle de l'outil SOLO) | toujours ; en 3D, grisée sans sélection ni solo (absence de donnée, §8 D23) |
| Tout réafficher | Show everything again | action | plan, s'il y a des éléments masqués |
| Vider l'isolement | Clear the solo | action | s'il y a un solo actif |

« Éléments masqués (%d)… » n'existe pas (pas de feuille des éléments masqués
sur Android, §8 D5).

#### 3.2.4 ⋯ › Patch & câblage

| Section | fr | en | Action |
|---|---|---|---|
| (sans titre) | Modèles de projecteurs… | Fixture models… | ouvre Modèles de projecteurs (§3.10) |
| Câblage / Cabling | Socapex & lignes PC16 | Socapex & PC16 lines | Câblage, onglet **Électrique** |
| Câblage | Lignes & multipaires DMX | DMX lines & multicores | Câblage, onglet **DMX** |
| Câblage | Puissances par type | Power per type | Câblage, onglet **Puissances** |

#### 3.2.5 ⋯ › Plan & décor

| Section | fr | en | Action | Apparition | Accès |
|---|---|---|---|---|---|
| Plan importé / Imported plan | Importer un plan (DXF, image, PDF)… | Import a plan (DXF, image, PDF)… | sélecteur de fichier (`OpenDocument`, « */* ») | toujours, **deux vues** (import hissé dans `SceneScreen`) | Pro au toucher |
| Plan importé | Régler le plan importé… | Adjust the imported plan… | ouvre le panneau §3.7 de la vue courante | s'il y a un plan importé | gratuit (lecture) |
| Décor 3D / 3D scenery | Importer un modèle 3D… | Import a 3D model… | dialogue des formats inchangé | 3D seulement | Pro au toucher |
| Décor 3D | Modèles importés (%d)… | Imported models (%d)… | panneau Modèles importés | 3D, s'il y a des modèles | Pro au toucher (inchangé) |

« Placer sur une carte… » n'existe pas sur Android (§8 D5).

#### 3.2.6 ⋯ › Partager & exporter

| Section | fr | en | Action | Apparition |
|---|---|---|---|---|
| Équipe / Team | Partager ce projet… | Share this project… | `ShareProjectDialog` (ouvrir gratuit, publier Pro) | si la synchro est branchée (toujours dans l'app : `App` crée le `SyncViewModel`) |
| Équipe | Historique des modifications | Change history | `HistoryDialog` (annuler : Pro) | idem |
| Exporter / Export | PDF du plan (composer les vues)… | Plan PDF (compose the views)… | plan : ouvre le panneau d'export ; 3D : ouvre le plan en mode export (`PlanStartAction.EXPORT_PDF`) | toujours |
| PDF de câblage / Cabling PDF | Dossier câblage complet | Full cabling pack | génère puis feuille de partage Android | s'il existe un câblage (un distributeur électrique ou une ligne DMX) |
| PDF de câblage | Distribution élec | Power distro | idem | idem |
| PDF de câblage | Câblage DMX | DMX cabling | idem | idem |
| PDF de câblage | Plans repérés | Marked-up plans | idem | idem |

- La génération des PDF de câblage (`runExport`, `CablingScreen.kt:194`) est
  extraite dans une fonction partagée appelable depuis `SceneScreen` ; l'écran
  Câblage garde son propre menu d'export. Attente : indicateur « Génération du
  PDF… » au-dessus de la vue courante, puis la feuille de partage Android.
- « Fichier MVR… » n'existe pas sur Android (pas d'export MVR, §8 D5).

#### 3.2.7 Fermer le projet

3D : `onClose()`. Plan : `onClose()` directement (la vue de base disparaît avec
le projet). Pas de confirmation (tout est enregistré au fil de l'eau).

### 3.3 Pastille « Vue »

Composable `ViewPill` (fichier à part) : capsule `surface`, ombre légère, texte
`bodySmall`, icône de tête, ▾. TalkBack : libellé « Vue », état = le libellé
affiché ; étiquette de test `viewPill`. Toucher → `DropdownMenu`.

**3D** (sous la rangée, à droite) — libellé « 3D · %s » (« 3D · Face »),
icône `ViewInAr` :

| Section | fr | en | Action |
|---|---|---|---|
| Point de vue / Viewpoint | Perspective | Perspective | ex-« Isométrique » (position actuelle `0,7·r` sur les trois axes) |
| Point de vue | Dessus | Top | inchangé |
| Point de vue | Face | Front | inchangé (caméra en +Z Filament = −Y MVR, même côté qu'iOS) |
| Point de vue | Arrière | Back | **nouveau** (caméra en −Z Filament) |
| Point de vue | Gauche | Left | **nouveau** (caméra en −X) |
| Point de vue | Droite | Right | ex-« Côté » (caméra en +X = iOS « Droite ») |
| (sans titre) | Vue plan 2D | 2D plan view | ouvre le plan |

- Coche sur le dernier point de vue appliqué (« Perspective » à l'ouverture) ;
  le retoucher y ramène la caméra après une orbite.
- « Réinitialiser la vue » quitte ce menu → bouton Recadrer (§3.4).
- Section « Mes vues » absente (Vue 1·2·3 n'existent pas, §8 D5).
- À vérifier à l'émulateur : Face et Droite montrent le même côté du show
  qu'iOS sur un même fichier (`mini.mvr`).

**Plan** (centrée sous la rangée) — libellé « Plan · %s » (« Plan · Dessus »),
icône `VerticalAlignBottom` :

| Section | fr | en | Action |
|---|---|---|---|
| Projection 2D / 2D projection | Dessus | Top | coché (seule projection Android, §8 D6) |
| (sans titre) | Vue 3D | 3D view | ferme le plan ; la 3D se reconstruit à son ouverture (comportement actuel) |

Pendant une affectation de câblage, « Vue 3D » désarme puis ferme le plan
**sans** rouvrir le Câblage (iOS §3) ; ← garde son comportement (retour au
Câblage).

**Cadrage sous la pastille** (iOS §3, restes 2026-10-15) : le cadrage
automatique de l'affectation (`CablingAssignBarPlacement.fitAbove`, qui ne
réserve aujourd'hui que le bas) et le futur Recadrer du plan posent le
contenu **entre** le bas mesuré de la pastille « Vue » (+ 24 dp) et le haut
de la barre du bas (barre d'affectation, panneau 3 points) — portage de
`PanelPlacementMath.planFitMargins(height, topReserve, bottomReserve)`, pur
et testé. Sans cela, la rangée de projecteurs du haut passe sous la
pastille centrée (défaut constaté sur iOS). Refait quand la pastille est
mesurée ou change (rotation), tant que le plan n'a pas été touché.

### 3.4 Bas-droite : Rechercher puis Recadrer (les deux vues)

Une pile verticale alignée à droite, même gabarit 48 dp :

- **Rechercher** (`Search`), TalkBack « Rechercher un projecteur par N° » :
  ouvre un champ « N° de projecteur » à gauche de la pile (clavier, action
  Rechercher) ; résultat vide : « Aucun projecteur nº %s » sous le champ. Le
  champ permanent du haut disparaît (3D `:2254`, plan `:2200`).
- **Recadrer** (`ZoomOutMap`, même icône pour l'outil `resetCamera`), TalkBack
  « Recadrer la vue ». 3D : action actuelle de « Réinitialiser la vue » ;
  plan : **nouveau** — remet zoom et décalage, en respectant la place de la
  barre d'affectation et du panneau 3 points (cadrages existants).
- La pile **monte** au-dessus de ce qui occupe le bas de sa colonne, d'une
  hauteur **mesurée** (`PanelPlacementMath.clearanceLift`, cadre de la pile
  mesuré hors de sa montée) : fiche, bannière multi, bandeau de mesure, barre
  d'affectation, panneau 3 points, panneau flottant ouvert dans sa colonne, et
  la **mention IGN** lue à sa place sans sa propre montée (la mention ne dépend
  pas de la pile : pas de boucle ; elle n'est jamais recouverte).
- Recadrer est masqué si l'outil `resetCamera` est ancré. Rechercher n'est
  **jamais** masqué : l'identifiant `search` n'a aucun `ToolSpec` (il n'est
  dans aucun catalogue, il ne peut pas être ancré) et iOS n'a pas d'outil de
  recherche ; il reste inerte (§6 A23).
- Les panneaux flottants qui étaient en **bas-droite** (Modèles importés,
  Plan importé, Export PDF) passent en **bas-gauche** avec un nouvel
  identifiant `PanelID` (§8 D13) : `IMPORTED_MODELS_3D_LEADING`
  (`importedModels3DLeading`), `REF_PLAN_PLAN_LEADING`
  (`refPlanPlacementPlanLeading`), `EXPORT_PLAN_LEADING`
  (`exportPlanLeading`) — clés propres à Android (iOS ne les a jamais eus à
  droite). Les anciennes clés restent dans l'énumération (jamais renommées),
  sans usage.

### 3.5 Bloc d'état (3D, haut-gauche sous ←)

Une colonne de pastilles (`MvrBadge` : capsule `surface`, `bodySmall`),
bornée en largeur par le cadre mesuré de la pastille Vue, chacune seulement si
elle a quelque chose à dire :

| fr | en | Condition |
|---|---|---|
| « %d objets » (pluriel « 1 objet »), suivi de « (tronqué) » si le plafond d'objets est atteint | « %d objects » / « 1 object » | toujours (pendant le chargement : étape + « %d % ») |
| « %d classes masquées » (pluriel « 1 classe masquée ») | « %d hidden classes » / « 1 hidden class » | classes masquées |
| « Modèles GDTF en cours… » + petit indicateur | « Loading GDTF models… » | pendant la phase des silhouettes GDTF |
| pastille de synchro (`SyncStatusBadge`) | inchangée | projet partagé ouvert ; **quitte le bas-droite** |

Absent : « %lld sur %lld calques masqués » d'iOS (Android ne masque pas les
calques MVR, D10) et le repère de build DEBUG.

Le plan n'a pas de bloc d'état ; la pastille « Plan · N projecteur(s) » est
retirée (la légende donne les nombres par calque). La pastille de synchro du
plan est posée à droite sous la rangée (§3.1, D21). Les écrans pleins
(destinations, §3.10) étant opaques et posés par-dessus la vue de base, la
pastille de synchro n'y est **plus visible** (aujourd'hui elle flotte
au-dessus de Patch, Univers, Câblage…) : elle se relit en fermant l'écran.

### 3.6 Panneau « Position GPS »

Composable `GeoPositionPanel` (fichier à part), le même dans les deux vues,
contenu décrit par une fonction pure `GeoPanelContent.make(surface,
calibrated, showSatellite, satelliteCovered, satelliteStatus)`.
`MovablePanel(GEO_POSITION_3D)` / `MovablePanel(GEO_POSITION_PLAN)` (nouveaux
`PanelID`, clés iOS `geoPosition3D` / `geoPositionPlan`), coin bas-gauche,
hauteur bornée entre ce qui occupe le haut de sa colonne (bloc d'état 3D,
pastille du plan) et ce qui occupe le bas (fiche, bannière, barre
d'affectation, panneau 3 points, mention IGN lue sans sa montée), comme iOS
§5.5 (portage de `floatingPanelSlot`). En tranche B (avant la rangée, la
pastille et le bloc d'état de la tranche C), la borne du haut est la barre
du haut 3D et, dans le plan, le titre et le champ de recherche ; la tranche C
la rebranche sur les nouveaux éléments. En-tête
« Position GPS » (en « GPS position »), croix discrète. Ouvert par : ⋯ ›
Position GPS…, l'outil `geoPanel` (nouveau), le crochet `MVR_AUTO_GEOPANEL`.

**Section « Affichage » (en « Display ») — gratuit**

1. Interrupteur « Afficher ma position » (`showLocation` de la vue ; démarre /
   arrête le GPS comme aujourd'hui). En 3D, désactivé tant que la scène n'est
   pas calibrée (absence de donnée : le marqueur 3D en dépend ; l'outil 3D
   actuel est déjà indisponible sans calibration) — §8 D20. Dans le plan,
   toujours actif (il sert à poser « je suis ici »).
2. Interrupteur « Fond satellite » : désactivé tant que la scène n'est pas
   calibrée ; **hors de France**, désactivé et suivi de « Fond satellite
   disponible en France uniquement (imagerie IGN). » (`sat_out_of_coverage`,
   §8 D8). Messages d'état existants dessous (`SatelliteUi.menuRow`).
3. Non calibré : « Calibrez d'abord (ci-dessous) pour afficher votre position
   et le satellite. »
4. Satellite affiché : « Téléchargement du satellite… » + indicateur pendant
   le chargement ; curseur « Opacité » 5–100 %, valeur « %d % ». **Seul
   endroit** où se règle l'opacité (décision 8).
5. 3D seulement, si calibré : curseur « Taille du point GPS » ×0,3–×3, valeur
   « ×%.1f » (remplace l'outil à 3 crans `GPS_MARKER_SIZE` ; même réglage
   `options.gpsMarkerScale`).

**Section « Calibration »**

6. Ligne d'état (`bodySmall`, secondaire ; orange si refus) — textes existants
   `ThreePointDisplay.statusText(GeoCalibrationStatus.of(…))` (refus, 3 points
   avec précision, 1 point, n points, non calibré).
7. « Calibrer en 3 points (guidé)… » — **Pro au toucher** ; bouton principal
   si non calibré, secondaire sinon.
8. « Je suis ici (pose rapide)… » — **Pro au toucher** ; légende dessous :
   « Touchez le plan là où vous êtes. Un 2e point corrige l'orientation et
   l'échelle. » Même comportement que l'outil `CALIBRATE` (repart de zéro
   après un relevé 3 points, sinon pose le point suivant) ; libellé fixe.
9. Isolé sous un séparateur : « Réinitialiser la calibration » (rouge) — si
   calibré — **Pro au toucher** — **nouveau sur Android** : calibration vidée,
   persistée et poussée par le chemin normal (`calibTick++`).

Raison d'écran Pro : « calibrer la position GPS » pour 7, 8, 9.

Depuis la **3D**, 7 et 8 ferment le panneau puis ouvrent le plan prêt à poser
(`PlanStartAction.THREE_POINT` / `HERE_ANCHOR`, GPS démarré ; garde Pro
**avant** l'ouverture). Dans le **plan**, ils ferment le panneau puis lancent
`startThreePoint()` ou l'armement « je suis ici » (une seule fonction
`armHereAnchor`, partagée avec l'outil et le lancement).

Disparaissent : curseur flottant d'opacité du plan, interrupteur et curseur du
satellite dans le ⋯, ligne d'état dans le ⋯, entrées de calibration dans le ⋯.
Les outils `myLocation`, `calibrate`, `threePointCalibration`, `satellite`,
`gpsMarkerSize` restent ancrables.

### 3.7 Panneau « Plan importé » (lecture pour tous, décision 6)

Composable `RefPlanPanel` (fichier à part, extrait de `PlanScreen.kt:2283-2441`)
posé **dans les deux vues** (nouveau en 3D). Coin bas-gauche, `MovablePanel`
`REF_PLAN_3D` (clé iOS `referencePlan3D`) / `REF_PLAN_PLAN_LEADING`
(`refPlanPlacementPlanLeading`), nouveaux.

- S'ouvre pour tous (⋯ › Plan & décor › Régler le plan importé…, outil
  `refPlanPanel`, crochet `MVR_AUTO_REFPLAN_PANEL`) ; plus de garde à l'entrée.
- **Lecture** (gratuit) : nature du plan, taille, pages PDF, segments,
  troncature, état des calques, valeurs.
- **Chaque modification est Pro au toucher**, raison « régler le plan
  importé » : Afficher le plan (interrupteur, `gatedSetter`), Déplacer (4
  flèches), Rotation ±5°, Rotation verticale ±5° (**3D seulement** : sans
  effet visible en 2D, elle quitte le panneau du plan — « une fenêtre ne cache
  jamais ce qu'elle règle »), Échelle ÷ ×, Homothétie (curseur, saisie, remise
  à 1), Retirer le plan (rouge, isolé) ; calques DXF : raison « modifier les
  calques du plan importé » (garde existante `SceneScreen.kt:1188`).
- Section « Calques » (DXF à plus d'un calque) : la même dans les deux
  panneaux.
- L'import (`importLauncher` et l'analyse du fichier, `PlanScreen.kt:452-516`)
  est **hissé dans `SceneScreen`** pour être appelable depuis les deux vues ;
  l'outil `importDXF` et l'entrée de menu l'appellent. Hisser l'import ne
  suffit pas — sont hissés **avec lui**, sinon un réglage fait en 3D ne
  serait ni enregistré ni partagé :
  - le **centrage** du plan importé, qui lit aujourd'hui `data.cx` /
    `data.cy` du plan : `SceneScreen` le calcule par `planData(scene,
    overrides)` (même centre, test de non-régression sur `mini.mvr` +
    `mini.dxf`) ;
  - le traitement `onSetReferencePlan` (`SceneScreen.kt:1143-1177` :
    persistance, téléversement DXP1, manifeste, calques éteints du DXF),
    devenu une fonction de `SceneScreen` appelée par les deux vues ;
  - la **persistance débouncée du placement** et `onTransformChanged`
    (`PlanScreen.kt:819-834`) : un seul effet dans `SceneScreen`, déclenché
    par un compteur de retouche commun (`refPlanTransformVersion`), que le
    panneau incrémente dans les deux vues (la 3D refait déjà son quad sur
    ce compteur).
- Après un import, le panneau s'ouvre dans la vue d'où l'import a été lancé
  (comportement actuel du plan, étendu à la 3D).

### 3.8 Panneau « Réglage des étiquettes »

Composable `LabelSettingsPanel` (extrait de `PlanScreen.kt:2803`) posé dans
les **deux** vues, même titre « Réglage des étiquettes », croix discrète
« Fermer » (au lieu du bouton texte actuel). `MovablePanel(LABELS_3D)` (clé
iOS `labels3D`, nouveau) en 3D ; `LABELS_PLAN` gardé dans le plan (déjà en
bas-gauche). Entrée toujours visible (⋯ › Affichage). Tout est gratuit,
hauteur bornée.

1. « Afficher les étiquettes » (interrupteur, existant).
2. Section « Contenu » (comme iOS, `opt_label_content`) : une case par champ
   (`LabelContent`).
3. Section « Apparence » : « Taille » (Petite · Moyenne · Grande) et « Écart
   au projecteur » (Proche · Normal · Loin) en boutons segmentés (mêmes
   valeurs persistées que les cycles actuels, §8 D9) ; plan : « Masquer les
   étiquettes trop dézoomées » (case).
4. **Plan seulement**, section « Placement sur le plan » :
   - légende « Touchez une étiquette pour la saisir, puis faites-la glisser ;
     retouchez-la pour la relâcher. » ;
   - boutons secondaires « Étiquettes de la sélection » et « Étiquettes du
     même type (ce calque) » (grisés sans sélection / sans étiquette saisie),
     « Désactiver (%d) » s'il y a des étiquettes saisies ;
   - « Blocs séparés » : une case par champ affiché, grille de deux colonnes
     (remplace les cases « ↳ bloc séparé » par champ) ;
   - « Replacer les étiquettes ».

L'outil `labelSize` (cycle) devient l'ouverture de ce panneau (comme iOS, titre
« Réglage des étiquettes »).

En 3D, ni « Blocs séparés » ni « Masquer les étiquettes trop dézoomées » :
sans effet en 3D (§2.3), ils quittent la 3D (A27).

### 3.9 Fiche de sélection et bannière multi (même fiche en 3D et dans le plan)

Composables `SelectionCard` et `MultiSelectionBanner` (fichier à part),
construits par `SceneScreen` (qui détient `overrides`, `power`, la fiche
patch) et fournis aux deux vues. Ils remplacent la pastille de texte 3D
(`Scene3DScreen.kt:2328`) et la fiche / bannière du plan (`PlanScreen.kt:2717`).

**Fiche** (un projecteur sélectionné, hors mesure ; dans le plan, ni fiche ni
bannière pendant l'assistant 3 points ou une affectation — règle existante
`PlanBottomRules.selectionBar`) :

1. En-tête : titre « Type — Nom » (nom effectif, type nettoyé, DESIGN_UI §9)
   + croix discrète « Fermer » qui désélectionne ; toucher la fiche ne fait
   rien ; toucher le vide désélectionne (inchangé).
2. Détails : « Calque : %s », « N° de projecteur : %s », « Adresse DMX : %s ».
3. Conso : « 450 W · 3 votes » (`power_watts_votes`) / « … d'après le GDTF »
   + « C'est bon » (vote, garde existante « renseigner une consommation »).
4. Jeton principal **Patch** : ouvre la fiche patch existante sur ce
   projecteur (enregistrer : Pro, garde existante).
5. Pan 180°, Tilt 180°, Étiquette ▾ et la portée : **absents** tant que ces
   fonctions n'existent pas sur Android (Q3).

Étiquettes de test : `selectionCard`, `selectionCard.close`,
`selectionCard.details`.

**Bannière multi** : « %d projecteurs sélectionnés » (pluriel « 1 projecteur
sélectionné ») · ✕ « Vider la sélection ». (« Éditer le patch » et
« Étiquette ▾ » absents : pas de patch multiple ni de côté d'étiquette sur
Android, Q3.)

**Version compacte** (téléphone en paysage, hauteur < 480 dp ;
`SelectionCardLayout.style(heightDp)`, pur) : une rangée — titre court (le nom
sans le type ; TalkBack lit le titre complet) · Patch · « Détails ⌃ » · croix ;
« Détails » déplie au-dessus (détails sur une ligne, conso et « C'est bon »),
replié à chaque nouvelle sélection ; au plus le quart de la hauteur de l'écran.
La pile bas-droite et la barre ancrée gauche montent au-dessus. Projecteur
gardé visible : si le projecteur choisi tombe sous la fiche compacte, le plan
glisse vers le haut et la 3D translate sa caméra (≈ 0,3 s, interrompu par un
geste ; jamais sous la pastille), comme iOS §8.4.

**Appui long** → fiche patch, dans les deux vues (inchangé).

### 3.10 Destinations (fenêtres)

Toutes ouvertes par `SceneScreen.open(destination)` (§4.1), en surimpression
au-dessus de la vue courante (§1.3). Barre de chaque écran plein : ← à gauche
(TalkBack « Retour » ; l'actuel « Retour 3D » serait faux ouvert depuis le
plan — et l'est déjà pour la Bibliothèque MA ouverte depuis l'accueil),
titre = ce que l'écran montre, outils en icônes à droite (§8 D1). Cela vaut
pour **les cinq** écrans qui disent aujourd'hui « Retour 3D » (§2.6).

**Sous-volets** : un écran ouvert *depuis* une destination (Bibliothèque MA
depuis Modèles de projecteurs, connexion GDTF Share depuis Modèles ou depuis
Compte & réglages, recherche GDTF Share d'un type) est un **sous-volet
interne** de cet écran, comme `GdtfSearchPane` aujourd'hui : son ← et le
retour système ramènent à l'écran parent, pas à la vue de base.
`SceneNavState` ne porte qu'une destination (pas de pile).

| Destination | Écran | Changements |
|---|---|---|
| Liste de patch | `PatchListScreen` | retour à la vue d'origine ; sinon inchangée |
| Univers DMX | `DmxUniverseScreen` | inspecteur : bouton secondaire **« Éditer »** par projecteur listé (TalkBack « Éditer le patch du projecteur %s »), qui ouvre la fiche patch existante (tranche D) |
| Câblage | `CablingScreen(initialTab)` | ouvre sur l'onglet demandé (`CablingScreenTab.opening(requested, memory.tab)` existe déjà) ; « Sur le plan » depuis une origine plan arme l'affectation sur le plan déjà ouvert (§4.3) |
| Modèles de projecteurs | `GdtfShareScreen` retitré « Modèles de projecteurs » | la liste des types (avec leur état Générique / Auto / Manuel) s'affiche **toujours** ; en tête : ligne « GDTF Share : connecté » (`CloudDone`) avec un bouton « Se déconnecter » à droite (jamais une déconnexion au simple toucher de la ligne, §1.2), ou bouton « Se connecter à GDTF Share… » (sous-volet `GdtfShareLoginPane`) ; 2e ligne « Bibliothèque MA » (sous-volet) ; « Améliorer tous » réservé à l'état connecté ; toucher un type **déconnecté** ouvre la connexion puis, connecté, la recherche de ce type (même enchaînement que la bibliothèque MA) ; relance de la résolution à la connexion inchangée |
| Bibliothèque MA | `MaLibraryScreen` | destination quand elle est ouverte par un profil reçu (par-dessus la vue courante) ou le crochet ; sous-volet de Modèles de projecteurs sinon ; l'accueil garde son hôte (`HomeMaLibraryHost`) ; rapport « OK » → « Terminé » (tranche E) |
| Calques | une feuille « Calques » (fusion de `LayersSheet` et `ClassesSheet`) | si le fichier déclare des classes, sélecteur segmenté « Calques \| Classes » en première ligne ; onglet Calques : chaque calque (pastille de couleur, nom, nombre d'objets) + en 3D le réglage « fluidité en navigation » (LOD, contenu actuel) ; depuis le plan : sans réglage LOD (liste en lecture, §8 D10) ; onglet Classes : contenu actuel |
| Compte & réglages | §3.11 | |
| Partager / Historique | dialogues actuels | ouverts par le ⋯ des deux vues ; Partager sans garde à l'entrée |

### 3.11 « Compte & réglages »

Écran plein (remplace `AccountDialog`), titre **fixe** « Compte & réglages »
(en « Account & settings »), ← (désactivée pendant une suppression de compte).
Ouvert par : ⋯ › Compte & réglages…, l'accueil (bouton « Compte & réglages »
et la carte « Créez votre compte (gratuit) »), l'outil `account`, l'outil
`gdtfShare` (tranche E, §4.6), le crochet `MVR_AUTO_DEST=account`. Depuis
l'accueil, il est hébergé par `App` (`MainActivity.kt`, comme
`HomeMaLibraryHost`) avec son propre retour système vers l'accueil ; depuis
un projet, c'est une destination de `SceneScreen`.

Sections, dans l'ordre :

1. **« Compte »** : déconnecté → formulaire actuel (Se connecter / Créer un
   compte, bascule « J'ai déjà un compte » / « Créer un compte ») ; connecté →
   nom, e-mail, serveur (« Firebase » / « Local (démo) »), « Se déconnecter »
   (`sync_sign_out` passe de « Déconnexion » à « Se déconnecter », valeur
   iOS) ; isolé en rouge : « Supprimer mon compte » (dialogue actuel,
   gratuit).
2. **« MVR Viewer Pro »** : contenu actuel de `ProAccountSection` (message
   de refus de rattachement compris), **textes passés en ressources** avec
   les valeurs iOS : « Version Pro active —
   édition débloquée » (« Pro version active — editing unlocked »), « La
   lecture est gratuite ; l'édition (patch, câblage, imports…) passe en Pro. »,
   « Débloquer la version Pro » (« Unlock Pro »), « Restaurer un achat »
   (« Restore a purchase »).
3. **« Services »** : ligne « GDTF Share » avec la valeur « Connecté » /
   « Non connecté ». Non connecté : toucher la ligne ouvre le sous-volet
   `GdtfShareLoginPane`. Connecté : bouton « Se déconnecter » au bout de la
   ligne (efface les identifiants gardés, comme le bouton actuel de l'écran
   GDTF Share) ; toucher la ligne elle-même ne déconnecte pas (§1.2). À
   l'ouverture, reconnexion silencieuse habituelle
   (`GdtfShareClient.ensureLoggedIn`, aucune requête sans identifiants
   gardés). Fermée depuis un projet : si GDTF Share est passé à « connecté »,
   la résolution des modèles repart (comme à la fermeture de l'écran GDTF
   Share aujourd'hui).
4. Pas de section « Débogage » (aucune bascule de serveur à l'écran sur
   Android, §8 D5).

### 3.12 Accueil, lecture, erreur

| Écran | Changement |
|---|---|
| Accueil | « Mon compte » / « Se connecter » → bouton « Compte & réglages » ; la carte « Créez votre compte (gratuit) » ouvre la même fenêtre ; textes de la carte en ressources (fr / en iOS). « Rejoindre un projet » et « Bibliothèque MA » restent. |
| Lecture du .mvr (`HomeScreen.kt:89-111`) | + bouton secondaire « Fermer le projet » sous l'étape → `SceneViewModel.reset()` (annule `loadJob`, déjà sûr) |
| Erreur d'ouverture | inchangée (message sur l'accueil, bouton Ouvrir présent : pas d'impasse) |
| Construction 3D | inchangée : ← et ⋯ › Fermer le projet ; vérifier qu'aucune écriture d'état n'arrive après fermeture sur un gros show |

### 3.13 Barres ancrées, téléphone et tablette

- **Tablette** (plus petite largeur ≥ 600 dp, `ToolbarIdiom.of(config)`) :
  disposition par défaut **propre à la tablette**, dans les deux vues : barre
  **gauche** = `patchList`, `universe`, `cabling`, `layers`.
- **Téléphone** : aucune barre ancrée par défaut.
- **Barre flottante historique** (bas-gauche, les deux appareils) :
  **retirée** sur téléphone **et** tablette (Q1 tranchée, §9.0). Si elle était
  gardée, elle ne s'afficherait pas sur tablette quand la disposition
  préremplie s'applique (deux barres à gauche se gêneraient).
- `ToolbarLayoutStore.load(view, idiom)` : une disposition **enregistrée** est
  toujours respectée (vidée, elle reste vide). Comme `SceneScreen.kt:141-142`
  enregistre aujourd'hui la disposition vide dès la première ouverture, la
  tranche E pose une **migration une fois** (clé `toolbar.layout.v2.done`) :
  une disposition enregistrée **vide** d'avant la migration compte comme « non
  personnalisée » (elle équivalait au défaut) et est effacée ; ensuite, on
  n'enregistre plus qu'au changement fait par l'utilisateur. La même
  migration remplace `gdtfShare` par `gdtfMapping` dans toute disposition
  enregistrée : l'ancien outil Android `gdtfShare` ouvrait l'écran des
  modèles, qui devient « Modèles de projecteurs » (`gdtfMapping`, §4.6).
- Catalogue du plan : + `patchList`, `universe`, `cabling`, `layers`,
  `multiSelect`, `geoPanel`, `refPlanPanel` ; catalogue 3D : + `multiSelect`,
  `geoPanel`, `refPlanPanel`, `importDXF`, `modelsPanel`, `gdtfMapping`
  (après `gdtfShare`, ordre iOS).
- La barre gauche **évite** la légende du plan, les panneaux flottants
  ouverts, la fiche et la bannière (cadres mesurés, `clearanceLift`) ; sans
  place, elle passe à droite de l'obstacle (iOS `leadingBarOffset`).
- `ToolbarCustomizeSheet` : texte d'aide iOS §12.1 ; puce « Aucun » → « Hors
  barres » ; + « Tout remettre hors barres » (persiste le vide) ; « Réinitialiser »
  remet le défaut de l'appareil.

### 3.14 Bandeaux de mode (plan)

Nouveau (indispensable : la barre flottante disparaît, Q1 : c'était le seul
signe qu'un mode était armé) : bandeau en haut, sous la pastille, dont l'icône
de tête quitte le mode :

| Mode | fr | en |
|---|---|---|
| Masquer (toucher) | Touchez un élément à masquer | Tap an item to hide |
| Masquer (cadre armé) | Tracez un cadre à masquer | Drag a box to hide |
| Isoler (toucher) | Touchez un élément à isoler | Tap an item to isolate |
| Isoler (cadre armé) | Tracez un cadre à isoler | Drag a box to isolate |

Les aides de calibrage et de mesure (bas-centre) restent ; l'aide de calibrage
s'ouvre aussi depuis le panneau Position GPS.

### 3.15 Récapitulatif gratuit / Pro

| Entrée | Gratuit | Pro (garde au toucher) |
|---|---|---|
| Mesurer, Étiquettes, Calques, Classes | ✔ | |
| Patch, Univers, Câblage, Modèles de projecteurs, Bibliothèque MA — ouvrir et lire | ✔ | |
| Fiche patch : ouvrir | ✔ | enregistrer (`FixtureDetail.kt:155`, inchangé) |
| « C'est bon » / vote de conso | | ✔ (inchangé) |
| Câblage : affecter, vider, distributeurs, réglages | | ✔ (inchangé) |
| Affichage (tout), Masquer & isoler (tout), Barre d'outils de cette vue | ✔ | |
| Importer un plan | | ✔ |
| Régler le plan importé — ouvrir et lire | ✔ | |
| Plan importé — chaque modification, calques DXF | | ✔ |
| Importer un modèle 3D, Modèles importés | | ✔ (inchangé) |
| Position GPS : afficher ma position, satellite, opacité, taille du point | ✔ | |
| Calibrer en 3 points, Je suis ici, Réinitialiser la calibration | | ✔ |
| Partager ce projet (ouvrir) / Historique (lire) | ✔ | publier, annuler (inchangé) |
| PDF du plan, PDF de câblage | ✔ | |
| Compte & réglages (compte, suppression, GDTF Share, achat) | ✔ | |
| Fermer le projet, pastille Vue, Recadrer, Rechercher | ✔ | |

---

## 4. Architecture

### 4.1 Destinations communes

```kotlin
enum class ProjectSurface { SCENE_3D, PLAN }

sealed interface ProjectDestination {
    val id: String                                  // stable, distinct par cas
    data object PatchList : ProjectDestination
    data object Universe : ProjectDestination
    data class Cabling(val tab: CablingScreenTab?) : ProjectDestination   // null = dernier onglet
    data class Layers(val tab: LayersTab) : ProjectDestination            // LAYERS / CLASSES
    data object FixtureModels : ProjectDestination
    data object MaLibrary : ProjectDestination      // profil reçu, crochet ; sinon sous-volet de FixtureModels
    data object AccountSettings : ProjectDestination
    data object ShareProject : ProjectDestination   // dialogue
    data object History : ProjectDestination        // dialogue
}
```

- Une seule destination à la fois (pas de pile) ; ce qui s'ouvre *depuis*
  une destination est un sous-volet interne (§3.10).

- `SceneScreen.open(dest, from: ProjectSurface)` est le **seul** point
  d'entrée (menu, outils ancrés, fiche, crochets DEBUG, profil MA reçu).
- État de navigation pur : `SceneNavState(base, destination, assign)` et un
  réducteur `SceneNav.reduce(state, event)` testé (ouvrir, fermer, retour
  système, bascule de vue, affectation). `SceneMode` disparaît au profit de
  `base: SceneBase { THREE_D, PLAN }` (valeurs persistées inchangées).
- Ordre des `BackHandler` (du moins au plus prioritaire) : 3D → fermer le
  projet ; plan → `finishAssign` en affectation, sinon 3D ; destination
  ouverte → la fermer ; page de famille du ⋯ → premier niveau ; dialogue →
  le fermer.

### 4.2 Lancement du plan

`enum class PlanStartAction { NONE, EXPORT_PDF, THREE_POINT, HERE_ANCHOR }`,
consommée **une fois** à l'apparition du plan, remise à `NONE` à sa
fermeture. `THREE_POINT` → `startThreePoint()` ; `HERE_ANCHOR` → `showLocation
= true` puis armement ; `EXPORT_PDF` → `exportMode = true`. Pro déjà vérifié
avant l'ouverture.

### 4.3 Câblage depuis le plan

`CablingOrigin { SCENE_3D, PLAN }` retenu à l'ouverture du Câblage. Fonction
pure `CablingAssignRoute` (testée), miroir d'iOS §15.4 :

| Événement | Origine 3D | Origine plan |
|---|---|---|
| « Sur le plan » | fermer le Câblage, armer, base = PLAN | fermer le Câblage, armer sur le plan déjà ouvert |
| « Terminé » de la barre d'affectation | désarmer, base = THREE_D, rouvrir le Câblage | désarmer, rouvrir le Câblage par-dessus le plan |
| ← du plan / retour système pendant l'affectation | base = THREE_D, rouvrir le Câblage | idem |
| « Vue 3D » (pastille) | désarmer, base = THREE_D | idem |
| croix « Quitter l'affectation » | désarmer, rester sur le plan | idem |

Mémoire (onglet, voie travaillée, position) : `cablingMemory`, inchangée.

### 4.4 Modèle du menu et de la pastille

- `MainMenuContext` (valeur) : surface, plan importé ?, nombre de modèles
  importés, synchro branchée ? (`sync != null`, toujours vrai dans l'app,
  faux dans les tests sans service : section Équipe absente, règle iOS),
  câblage existant ?, modes de coloration disponibles, éléments masqués ?,
  solo actif ?, sélection non vide ?. **Pas de `isPro`.**
- `MainMenuModel.sections(ctx): List<MainMenuSection>` — entrées
  (`MainMenuItemId` stable, clé de texte, icône, type : action / bascule /
  choix de valeur / famille) dans l'ordre §3.2.
- `MainMenu` dessine le modèle ; reçoit l'état des bascules et un répartiteur
  `(MainMenuItemId) -> Unit` fourni par chaque vue, qui transmet les entrées
  « projet » à `SceneScreen` (`onMainMenu`).
- `ViewPillModel.items(surface, currentPreset)` : même principe.

### 4.5 Garde Pro des réglages

`ProGate.setter(reason, isPro, throttle, now, refuse)` pur +
`ProAccess.gatedSetter(reason) { … }` ; `ProRefusalThrottle` (0,8 s
glissantes) : un glissé continu n'ouvre jamais deux écrans Pro.

### 4.6 Barres

`ToolbarIdiom { PHONE, TABLET }` (`smallestScreenWidthDp >= 600`, ou crochet
`MVR_FORCE_TABLET_LAYOUT`), `ToolbarLayout.tabletDefault`,
`ToolbarLayout.defaultLayout(view, idiom)`, `ToolbarLayoutStore.load(view,
idiom)` + migration §3.13. Table pure `ToolId.home` : l'autre place de chaque
outil (menu ⋯, panneau, bouton permanent, pastille, fenêtre), testée contre
`MainMenuModel` et `GeoPanelContent`.

Nouveaux `ToolId` (rawId = valeurs iOS), **chacun créé dans la tranche qui
en a besoin** :

| ToolId | rawId | Titre | Tranche | Pourquoi là |
|---|---|---|---|---|
| `GEO_PANEL` | `geoPanel` | Position GPS | B | ouvre le panneau créé en B (tests B) |
| `MULTI_SELECT` | `multiSelect` | Multi-sélection | C (Q2 = oui, §9.0) | le bouton permanent se masque quand l'outil est ancré : l'outil doit exister avec le bouton |
| `REF_PLAN_PANEL` | `refPlanPanel` | Régler le plan importé | E | jusque-là, l'outil `importDXF` garde ses deux états (importer / ouvrir le panneau, désormais sans garde) |
| `MODELS_PANEL` | `modelsPanel` | Modèles importés | E | idem pour `importModel` |
| `GDTF_MAPPING` | `gdtfMapping` | Modèles de projecteurs | E | catalogue 3D, ouvre Modèles de projecteurs (iOS `gdtfMapping`) |

En E, `DXF` (`importDXF`) ne fait plus qu'importer et `IMPORT_MODEL`
(`importModel`) qu'importer ; leurs libellés ne changent plus selon l'état.
`GDTF_SHARE` (`gdtfShare`) prend le sens iOS — la **connexion** GDTF Share,
titre « GDTF Share » — et ouvre « Compte & réglages » (section Services) ;
la migration v2 (§3.13) remplace `gdtfShare` par `gdtfMapping` dans les
dispositions enregistrées. Avant E, l'outil `gdtfShare` ouvre « Modèles de
projecteurs » (l'écran qu'il ouvrait déjà).

Table `ToolId.home` pour les outils propres à Android ou dont la place
diffère d'iOS (les autres suivent iOS) :

| ToolId | Autre place |
|---|---|
| `clearSelection` | ✕ « Vider la sélection » de la fiche et de la bannière (D) |
| `gpsMarkerSize`, `threePointCalibration` | intérieur du panneau Position GPS |
| `structure`, `legend` | ⋯ › Affichage (plan) |
| `labelSize` | ⋯ › Affichage › Réglage des étiquettes… |
| `solo` (3D), `showAll`, `clearSolo` | ⋯ › Masquer & isoler (iOS : `showAll` dans la feuille Éléments masqués, absente sur Android) |
| `gdtfShare` | intérieur de Compte & réglages (Services) |
| `colorMode`, `search`, `cameraPresets` | aucun `ToolSpec`, dans aucun catalogue : inertes, hors table |

### 4.7 Crochets DEBUG

Extras d'intent lus par `MainActivity` **seulement si l'app est débogable**
(modèle de `MVR_PRO`), regroupés dans un objet `DebugHooks` :

| Crochet | Effet | Tranche |
|---|---|---|
| `MVR_AUTO_PLAN=1` | ouvre le plan comme vue de base | A |
| `MVR_AUTO_HOME_DEST=account` | ouvre Compte & réglages depuis l'accueil (sans projet) | A |
| `MVR_AUTO_DEST=<patch\|universe\|cabling[:electrical\|dmx\|powers]\|layers\|classes\|models\|ma\|account\|share\|history>` | ouvre la destination ~2 s après l'affichage, par-dessus la vue courante | A |
| `MVR_AUTO_PLAN_START=<export\|cal3\|here>` | ouvre le plan avec cette action | A |
| `MVR_AUTO_GEOPANEL=1`, `MVR_AUTO_REFPLAN_PANEL=1`, `MVR_AUTO_LABELS_PANEL=1` | ouvre le panneau dans la vue courante | B |
| `MVR_AUTO_SELECT=<N°>` | sélectionne ce projecteur dès la scène prête | D |
| `MVR_RESET_TOOLBARS=1`, `MVR_FORCE_TABLET_LAYOUT=1` | efface les dispositions ; applique le défaut tablette | E |

En DEBUG, la racine pose `testTagsAsResourceId = true` pour viser les
étiquettes de test avec `uiautomator dump`.

Données d'essai : copier `mini.mvr`, `mini.dxf`, `bad.mvr` du dépôt iOS
(`MVRViewerTests/Fixtures.bundle/menus/`) dans `app/src/test/resources/menus/`
(tranche A). Sur l'émulateur : `adb push` puis `run-as` dans le stockage
interne et `am start -a android.intent.action.VIEW -d file:///…` (note de
build Android). Shows réels : `~/Downloads/Matières Fécales_Technical
Plans_V6_04_PATCHED.mvr`, `~/Downloads/test-gc-v1.mvr`.

---

## 5. Correspondance avec la table iOS (101 lignes)

« Android avant » = le code de `e110bbc` ; « n'existe pas » = aucune fonction
équivalente (la ligne reste sans objet, aucune entrée n'est créée qui
pointerait dans le vide). Tranches : §10.

| # | Élément iOS | Android avant | Android après | Tr. |
|---|---|---|---|---|
| 1 | Indicateur « GDTF Share en cours » | aucun (titre 3D « … proj. GDTF… » pendant la construction) | pastille « Modèles GDTF en cours… » du bloc d'état | C |
| 2 | Sélection par cadre | outil RECT : barre flottante (3D, plan), ⋯ › Outils | bouton permanent haut-droite des deux vues, masqué s'il est ancré ; retiré du ⋯ | C |
| 3 | Multi-sélection | n'existe pas (3D : le toucher ajoute toujours ; plan : le cadre fait office de multi) | bouton permanent + outil `multiSelect` créé dans la même tranche (Q2) | C |
| 4 | Menu ⋯ | un `SceneOptionsMenu` (⋮) au contenu différent par vue, ~25 lignes à plat | un seul menu, même place, même contenu (`MainMenuModel` + `MainMenu`) | C |
| 5 | Projets récents… | ← de la 3D, retour système | ⋯ › « Fermer le projet » (dernière ligne) ; ← gardée (§8 D2) | C |
| 6 | Mesurer | outil MEASURE (barre flottante, ⋯ › Outils) | ⋯ › rangée 1 ; outil gardé | C |
| 7 | Vue plan | ⋯ 3D 1er niveau, outil PLAN_VIEW | pastille Vue › « Vue plan 2D » ; quitte le ⋯ ; outil gardé (« Vue plan 2D ») | C |
| 8 | Calques (feuille) | ⋯ › Outils › « Calques… » (3D, LOD), légende du plan | ⋯ › rangée 1 « Calques » dans les deux vues ; légende inchangée | C |
| 9 | Classes | ⋯ › Outils › « Classes… » (deux vues), `ClassesSheet` | onglet « Classes » de la feuille Calques | A |
| 10 | Étiquettes (bascule) | ⋯ « Étiquettes » coche (deux vues) + outil | ⋯ › rangée 1 | C |
| 11 | Réglage des étiquettes… | 3D : options en ligne dans le ⋯ ; plan : panneau par l'outil ancré seulement + options dans le ⋯ | entrée toujours visible (Affichage) ; panneau « Réglage des étiquettes » dans les deux vues | B |
| 12 | Activer des étiquettes ▸ | ⋯ du plan : « Étiquettes de la sélection », « … du même type (ce calque) » (conditionnelles) | panneau, section « Placement sur le plan », boutons | B |
| 13 | Blocs séparés ▸ | case « ↳ bloc séparé » par champ (⋯ et panneau) | section Placement, une case par champ affiché | B |
| 14 | Replacer les étiquettes | ⋯ du plan | section Placement, bouton secondaire | B |
| 15 | Toucher une étiquette | geste caché | inchangé + ligne d'aide | B |
| 16 | Taille / Écart | cycles dans le ⋯ (deux vues), outil LABEL_SIZE (cycle) | panneau, section Apparence (segments) ; l'outil ouvre le panneau | B |
| 17 | Couleurs par calque | ⋯ coche (deux vues), outil | Affichage › Couleurs ; outil gardé | C |
| 18 | Colorer par câblage | ⋯ « Coloration : %s » (cycle, plan) | Affichage › Couleurs › « Colorer par câblage ▸ » (liste) | C |
| 19 | Textures / Éclairage / Sol virtuel | n'existe pas | absent | C |
| 20 | Rendu Réaliste / Léger | n'existe pas (qualité figée) | absent | C |
| 21 | Niveau de détail (plan) | n'existe pas | absent | C |
| 22 | Couleur du fond ▸ | section en ligne dans le ⋯ (préréglages + Personnalisée…) | Affichage › Couleurs › « Couleur du fond ▸ » | C |
| 23 | Personnaliser la barre d'outils… | ⋯ › Outils (dernière ligne) | Affichage › « Barre d'outils de cette vue… » | C |
| 24 | Importer un plan | outil DXF du plan (libellé variable), ⋯ › Outils ; rien en 3D | ⋯ › Plan & décor (deux vues, Pro au toucher) ; import hissé (B) ; outil `importDXF` | C |
| 25 | Régler le plan importé… | même outil DXF (2e état), plan seulement, Pro à l'entrée | lisible pour tous, deux vues (`RefPlanPanel`), modifications Pro | B |
| 26 | Carte | n'existe pas | absent | B |
| 27 | Importer un modèle 3D… | outil IMPORT_MODEL (libellé variable), ⋯ › Outils (3D) | Plan & décor › Décor 3D (dialogue des formats inchangé) | C |
| 28 | Modèles importés (n)… | même outil (2e état) | Décor 3D « Modèles importés (%d)… » (Pro) ; outil `modelsPanel` | C |
| 29 | Calques du plan DXF | section du panneau du plan (cases, Pro au toucher) | section Calques du panneau Plan importé, deux vues, lisible | B |
| 30 | Afficher ma position | outil GPS (« Ma position GPS » ; 3D « Ma position (3D) » si calibré) | panneau Position GPS 1re ligne, deux vues ; outil gardé | B |
| 31 | Calibrations | outils CALIBRATE et CALIBRATE_3, plan seulement, + ⋯ › Outils | panneau Position GPS (deux vues) ; depuis la 3D, ouvrent le plan prêt à poser ; outils gardés | B |
| 32 | Réinitialiser la calibration | n'existe pas | **ajouté** au panneau (Pro, rouge, isolé) | B |
| 33 | État de la calibration | ligne dans ⋯ › Outils (plan) | ligne d'état du panneau | B |
| 34 | Fond satellite | ⋯ coche (deux vues, si calibré) + outil | panneau (gratuit) ; outil gardé | B |
| 35 | Opacité du satellite | curseur dans le ⋯ (deux vues) + curseur flottant (plan) | panneau **seulement** (décision 8) | B |
| 36 | Taille du point GPS | outil GPS_MARKER_SIZE (3 crans, 3D) | panneau (3D), curseur ×0,3–×3 | B |
| 37 | Section Repérage | n'existe pas | sans objet | B |
| 38 | Liste de patch | ⋯ 1er niveau (3D et plan), outil PATCH (3D) ; retour toujours vers la 3D | ⋯ › rangée 2 « Patch », par-dessus la vue courante, retour à l'origine | C |
| 39 | Univers DMX | ⋯ 1er niveau (3D), outil | rangée 2 « Univers » | C |
| 40 | Câblage | ⋯ « Câblage électrique » (3D), dernier onglet retenu | rangée 2 « Câblage » + Patch & câblage › 3 entrées par onglet | C |
| 41 | Modèles de projecteurs | ⋯ « GDTF Share (modèles 3D) » (3D), outil `gdtfShare` « GDTF Share » (3D) | Patch & câblage › « Modèles de projecteurs… » (deux vues) ; outil `gdtfMapping` en E (`gdtfShare` = connexion, migration §3.13) | C |
| 42 | GDTF Share (connexion) | formulaire dans l'écran GDTF Share | Compte & réglages › Services + ligne d'état en tête de Modèles de projecteurs | A |
| 43 | Compte | ⋯ › Synchro « Compte », accueil « Mon compte / Se connecter », outil ACCOUNT ; `AlertDialog` | écran « Compte & réglages » | A |
| 44 | Partager ce projet… | ⋯ › Synchro (Pro à l'entrée) | Partager & exporter › Équipe (ouvrir gratuit, publier Pro) | C |
| 45 | Historique | ⋯ › Synchro | Partager & exporter › Équipe | C |
| 46 | Exporter le MVR | n'existe pas | absent | C |
| 47 | Mode export PDF | outil EXPORT_PDF (plan), ⋯ › Outils | Partager & exporter › « PDF du plan (composer les vues)… » (3D : ouvre le plan en mode export) ; panneau et outil gardés | C |
| 48 | PDF de câblage | menu de l'icône partage de l'écran Câblage | gardé + section « PDF de câblage » du ⋯ | C |
| 49 | Pastille point de vue 3D | menu « Points de vue » (Dessus, Face, Côté, Isométrique, Réinitialiser la vue) | pastille Vue (3D) : Perspective, Dessus, Face, Arrière, Gauche, Droite ; réinitialiser → Recadrer | C |
| 50 | Vue 1/2/3 | n'existe pas | absent | C |
| 51 | Pastille de projection du plan | n'existe pas (dessus seulement) | pastille Vue (plan) « Plan · Dessus » : Dessus + « Vue 3D » | C |
| 52 | « Génération des icônes… » | n'existe pas | sans objet | C |
| 53 | Pastilles d'état | titre de la barre 3D ; pastille synchro bas-droite au-dessus de tous les modes ; classes masquées jamais signalées | bloc d'état §3.5 (pluriels) ; synchro : bloc d'état en 3D, à droite sous la rangée dans le plan (D21), plus visible sur les écrans pleins | C |
| 54 | Bannière « Nouvelle version » | haut-centre, pleine largeur, par-dessus la barre du haut | contenu inchangé ; posée sous la rangée du haut (jamais sur ← ni ⋯) | C |
| 55 | Recherche par N° | champ permanent haut-droite (deux vues), « N° projecteur », pas de retour si rien | bas-droite, loupe → champ « N° de projecteur », « Aucun projecteur nº %s » | C |
| 56 | Recadrer | 3D : « Réinitialiser la vue » + outil CAM_RESET « Recentrer la caméra » ; plan : n'existe pas | bas-droite sous la recherche, deux vues, icône unique | C |
| 57 | Double-toucher 3D | n'existe pas | sans objet | C |
| 58 | Terminé (plan) | ← haut-gauche (affectation : retour au Câblage) | inchangé (TalkBack « Terminé ») | C |
| 59 | Bannières de mesure | 3D bas, plan bas | inchangées | C |
| 60 | Bannière multi | 3D : pastille sans action ; plan : bannière jaune sans action | bannière commune « %d projecteurs sélectionnés » + ✕ (Éditer le patch et Étiquette absents, Q3) | D |
| 61 | Fiche : Patch | n'existe pas (appui long seulement) | jeton principal « Patch » (fiche patch existante) | D |
| 62 | Fiche : toucher pour fermer | pas de fermeture au toucher | croix « Fermer » | D |
| 63 | Fiche : conso + C'est bon | dans la fiche patch seulement | aussi sur la fiche de sélection (deux vues) | D |
| 64 | Pan 180° / Tilt 180° | n'existe pas | absent (Q3) | D |
| 65 | Étiquette ▸ | n'existe pas (pas de côté par projecteur) | absent (Q3) | D |
| 66 | Appui long → patch | deux vues | inchangé | D |
| 67 | Panneau Plan importé (3D) | n'existe pas | **nouveau** : panneau partagé, avec Rotation verticale | B |
| 68 | Panneau Plan importé (plan) | Pro à l'entrée, bas-droite, avec Rotation verticale | lisible, setters Pro, bas-gauche, sans Rotation verticale | B |
| 69 | Panneau Modèles importés | flottant 3D bas-droite, Pro à l'entrée, titre « Modèles 3D (%d) » | Pro à l'entrée inchangé ; titre « Modèles importés (%d) » en E (clé `tool_models_fmt` gardée, valeur changée) ; passe en bas-gauche en C (`IMPORTED_MODELS_3D_LEADING`, §8 D13) | B |
| 70 | Feuille Calques | `Dialog` LOD « Calques · Vue 3D » (3D seulement) | feuille « Calques » des deux vues, segment Calques \| Classes | A |
| 71 | Feuille Classes | `Dialog` à part | onglet de la feuille Calques | A |
| 72 | Fenêtre « Mettre le plan à l'échelle » | n'existe pas | sans objet | C |
| 73 | Légende du plan | haut-gauche | inchangée (sous la pastille, §8 D11) | C |
| 74 | « Zoomez pour voir les étiquettes » | n'existe pas | sans objet | C |
| 75 | Masquer / Éléments masqués / Isoler | outils MASK et SOLO (barre flottante ; SOLO aussi en 3D), ⋯ › Outils | ⋯ › Masquer & isoler (§3.2.3) ; outils gardés ; pas de feuille | C |
| 76 | Tout réafficher | outil SHOW_ALL | Masquer & isoler + outil gardé | C |
| 77 | Vider l'isolement | outil CLEAR_SOLO « Vider le solo » | idem, titre « Vider l'isolement » | C |
| 78 | Bandeaux masquage / solo / ancre / mesure | aides de calibrage et de mesure (bas) ; pas de bandeau masquage ni solo | aides gardées, aide d'ancre ouverte aussi depuis le panneau GPS (B) ; bandeaux masquage / solo §3.14 (C) | B |
| 79 | Barre d'affectation | livrée (SPEC-cablage-plan, Android) | inchangée ; retour par-dessus le plan (A, §4.3) | D |
| 80 | Barre d'export PDF | panneau « Export PDF », « %d vue(s) », « PDF prêt · %d page(s) » | titre « PDF du plan », pluriels | E |
| 81 | Nommer la vue | champ du panneau + dialogue Renommer | inchangé | E |
| 82 | Vues du PDF | n'existe pas (liste dans le panneau) | sans objet | E |
| 83 | Barres ancrables (accessibilité) | `contentDescription` = libellé ; état coché par `FilledIconToggleButton` | vérifié à TalkBack ; table `ToolId.home` testée | E |
| 84 | Libellés des outils | « Sélection rectangle », « Taille des étiquettes », « Recentrer la caméra », « Calibrer : je suis ici », « Vue plan », « Export PDF », « Ma position GPS / (3D) », « Solo (sélection seule) », « Vider le solo », « Effacer la sélection », « Taille du marqueur », « Câblage électrique », « Calques » / « Compte » / « GDTF Share » en dur | titres iOS (§7.2) ; `gdtfShare` / `gdtfMapping` (§4.6) | E |
| 85 | Liste de patch | écran plein, ← « Retour 3D » (comme Univers, Câblage, GDTF Share, Bibliothèque MA) ; pas de multi | retour à la vue d'origine, ← « Retour » sur les cinq écrans ; pas de multi-édition (Q3) | A |
| 86 | Univers DMX | inspecteur sans action | + « Éditer » par projecteur | D |
| 87 | Câblage : onglets | déjà Électrique / DMX / Puissances, dernier onglet retenu | + ouverture sur l'onglet demandé | A |
| 88 | Câblage : le reste | écran Câblage | inchangé | D |
| 89 | Éditer le patch (fenêtre) | fiche patch (« N° », votes en pluriel déjà) | relecture du vocabulaire seulement | E |
| 90 | Modèles de projecteurs : ligne GDTF Share | déconnecté → formulaire seul ; « Déconnexion » en bas | ligne d'état + bouton « Se déconnecter » au bout de la ligne ; liste toujours visible ; type touché déconnecté → connexion puis recherche | A |
| 91 | GDTF Share (identifiants) | dans l'écran GDTF Share | sous-volet `GdtfShareLoginPane` depuis Compte & réglages ou la ligne d'état | A |
| 92 | Bibliothèque MA : rapport | « OK » | « Terminé » | E |
| 93 | Création d'un type MA (fin) | n'existe pas (pas d'écran de fin « OK ») | sans objet | E |
| 94 | Type personnalisé | depuis la fiche patch | inchangé | A |
| 95 | Mon compte (titre) | variable (Compte / Créer un compte / Connexion) | « Compte & réglages », fixe | A |
| 96 | Forcer le backend local | n'existe pas à l'écran | sans objet | A |
| 97 | Partager / Historique / Rejoindre | dialogues posés par `SceneScreen` ; Rejoindre aussi dans le ⋯ | ouverts par le ⋯ des deux vues ; Rejoindre : accueil seulement | A |
| 98 | Accueil | « Mon compte / Se connecter » + carte (texte en dur) | bouton « Compte & réglages » + carte, textes en ressources | A |
| 99 | Placer sur une carte (fenêtre) | n'existe pas | sans objet | B |
| 100 | Erreur et chargements | erreur sur l'accueil (pas d'impasse) ; lecture sans bouton ; 3D : ← | lecture : « Fermer le projet » ; le reste inchangé | A |
| 101 | Écran Pro | `ProPaywallHost` | inchangé ; un écran par geste sur un réglage gardé | B |

Récapitulatif : A = 15 lignes, B = 23, C = 45, D = 10, E = 8 (101).

---

## 6. Options propres à Android (absentes d'iOS)

| # | Option Android | Où aujourd'hui | Après | Tr. |
|---|---|---|---|---|
| A1 | Barre flottante historique (3D 6 outils, plan 12) | `AnchoredToolbars.kt:112`, `ToolbarLayout.kt:183-193` | Q1 tranchée (§9.0) : retirée sur téléphone et tablette, chaque outil ayant une autre place (`ToolId.home`) ; seule exception transitoire : « Vider la sélection », dont le ✕ arrive en D (entre C et D : toucher le vide désélectionne, comme aujourd'hui) | C |
| A2 | ← en 3D (ferme le projet) | `Scene3DScreen.kt:1979` | gardée dans la rangée commune (§8 D2) | C |
| A3 | Menu « Points de vue » (icône caméra) | `Scene3DScreen.kt:1987` | pastille Vue + Recadrer | C |
| A4 | Section *Outils* du ⋯ (doublon des barres, `toMenuTools`) | `SceneOptions.kt:280-307` | supprimée | C |
| A5 | « Décor / structure » (plan) | ⋯ + outil STRUCTURE | Affichage (plan) ; outil gardé | C |
| A6 | « Légende » (plan) | ⋯ + outil LEGEND | Affichage (plan) ; outil gardé | C |
| A7 | « Masquer si trop dézoomé » | ⋯ (deux vues ; sans effet en 3D) | panneau d'étiquettes (plan), libellé iOS « Masquer les étiquettes trop dézoomées » ; quitte la 3D (A27) | B |
| A8 | Taille / Écart en cycles dans le ⋯ | `SceneOptions.kt:417-442` | panneau d'étiquettes, segments | B |
| A9 | Champs + « ↳ bloc séparé » dans le ⋯ | `SceneOptions.kt:392-408` | panneau (Contenu ; Blocs séparés dans Placement) | B |
| A10 | « Taille du marqueur » (outil 3 crans) | `Scene3DScreen.kt:1860` | panneau GPS (curseur) ; outil gardé, titre « Taille du point GPS » | B |
| A11 | « Effacer la sélection » (outil) | barre flottante, ⋯ | ✕ de la fiche et de la bannière (D) ; outil gardé, titre « Vider la sélection » (E) ; entre C et D, toucher le vide désélectionne (A1) | C |
| A12 | « Calibrer en 3 points (guidé)… » en outil | `PlanScreen.kt:1121` | panneau GPS ; outil gardé | B |
| A13 | Solo en 3D (isole la sélection) | outil SOLO 3D | ⋯ › Masquer & isoler en 3D (§8 D4) | C |
| A14 | « Rejoindre un projet » dans le ⋯ | `SceneOptions.kt:317` | accueil seulement | C |
| A15 | « Bibliothèque MA » au 1er niveau du ⋯ | `SceneOptions.kt:276` | 2e ligne de Modèles de projecteurs + accueil | C |
| A16 | Écran combiné « GDTF Share (modèles 3D) » | `GdtfShareScreen.kt` | « Modèles de projecteurs » (§3.10) | A |
| A17 | Panneau Export PDF (Envoyer, Imprimer) | `PlanScreen.kt:2517` | gardé, titre « PDF du plan », bas-gauche | E |
| A18 | Rotation verticale dans le panneau du plan | `PlanScreen.kt:2342` | panneau Plan importé en 3D seulement | B |
| A19 | Pastille de synchro bas-droite, au-dessus de tous les modes | `SceneScreen.kt:1348` | bloc d'état 3D ; dans le plan, à droite sous la rangée (D21) ; plus visible sur les écrans pleins (dit au §3.5) | C |
| A20 | Titre « Plan · N projecteur(s) » | `PlanScreen.kt:1980` | retiré | C |
| A21 | Curseur d'opacité dans le ⋯ (deux vues) | `SceneOptions.kt:351` | panneau GPS | B |
| A22 | Libellés d'outils variables (DXF, IMPORT_MODEL) | `PlanScreen.kt:1140`, `Scene3DScreen.kt:1871` | outils séparés à libellé fixe (`importDXF` / `refPlanPanel`, `importModel` / `modelsPanel`) | E |
| A23 | Outils sans équivalent iOS : `clearSelection`, `gpsMarkerSize`, `threePointCalibration`, `structure`, `legend`, `colorMode`, `search`, `cameraPresets` | `ToolbarLayout.kt:30` | rawId gardés ; `colorMode`, `search` et `cameraPresets` n'ont aucun `ToolSpec` et restent inertes (hors catalogues, hors table `home`) ; les autres ont leur place dans la table `ToolId.home` (§4.6) | E |
| A24 | Rapport de diagnostic (accueil) | `HomeScreen.kt:224` | inchangé | — |
| A25 | Aide « Touchez VOTRE position… » (bas-centre) | `PlanScreen.kt:2675` | inchangée, armée aussi depuis le panneau | B |
| A26 | Outil `gdtfShare` « GDTF Share » (3D) | `Scene3DScreen.kt:1938` | A : ouvre Modèles de projecteurs ; E : sens iOS (connexion → Compte & réglages), `gdtfMapping` ajouté, migration des dispositions (§3.13, §4.6, D24) | E |
| A27 | « ↳ bloc séparé » et « Masquer si trop dézoomé » dans le ⋯ **3D** | `SceneOptions.kt:403`, `:446` | sans effet en 3D : absents du panneau 3D, présents dans celui du plan | B |
| A28 | Panneau Modèles importés : titre « Modèles 3D (%d) » | `ModelPlacementPanel.kt:84` | « Modèles importés (%d) » (même clé, valeur changée) | E |
| A29 | Plan importé : persistance du placement dans le plan | `PlanScreen.kt:819-834` | hissée dans `SceneScreen` avec l'import (le panneau 3D doit enregistrer et partager) | B |
| A30 | Bouton « Déconnexion » (compte) et bouton de déconnexion en bas de l'écran GDTF Share | `SyncUI.kt:153`, `GdtfShareScreen.kt:264` | « Se déconnecter » (compte) ; GDTF Share : bouton au bout de la ligne d'état | A |

---

## 7. Textes

### 7.1 Nouveaux textes (fr / en exacts, repris d'iOS)

Noms de ressources proposés ; « (pl.) » = `<plurals>` one / other.

| Clé | fr | en |
|---|---|---|
| `menu_more_options` | Plus d'options | More options |
| `menu_measure` | Mesurer | Measure |
| `menu_layers` | Calques | Layers |
| `menu_universes` | Univers | Universes |
| `menu_cabling` | Câblage | Cabling |
| `menu_display` | Affichage | Display |
| `menu_hide_isolate` | Masquer & isoler | Hide & isolate |
| `menu_patch_cabling` | Patch & câblage | Patch & cabling |
| `menu_plan_scenery` | Plan & décor | Plan & scenery |
| `menu_gps_position` | Position GPS… | GPS position… |
| `menu_share_export` | Partager & exporter | Share & export |
| `menu_account_settings` | Compte & réglages… | Account & settings… |
| `menu_close_project` | Fermer le projet | Close the project |
| `menu_label_settings` | Réglage des étiquettes… | Label settings… |
| `menu_section_colours` | Couleurs | Colours |
| `menu_colour_by_cabling` | Colorer par câblage | Colour by cabling |
| `menu_toolbar_this_view` | Barre d'outils de cette vue… | Toolbar for this view… |
| `menu_fixture_models` | Modèles de projecteurs… | Fixture models… |
| `menu_section_cabling` | Câblage | Cabling |
| `menu_socapex_pc16` | Socapex & lignes PC16 | Socapex & PC16 lines |
| `menu_dmx_lines` | Lignes & multipaires DMX | DMX lines & multicores |
| `menu_power_per_type` | Puissances par type | Power per type |
| `menu_section_imported_plan` | Plan importé | Imported plan |
| `menu_adjust_imported_plan` | Régler le plan importé… | Adjust the imported plan… |
| `menu_section_scenery_3d` | Décor 3D | 3D scenery |
| `menu_imported_models_fmt` | Modèles importés (%1$d)… | Imported models (%1$d)… |
| `menu_section_team` | Équipe | Team |
| `menu_share_project` | Partager ce projet… | Share this project… |
| `menu_section_export` | Exporter | Export |
| `menu_plan_pdf` | PDF du plan (composer les vues)… | Plan PDF (compose the views)… |
| `menu_section_cabling_pdf` | PDF de câblage | Cabling PDF |
| `pill_view` | Vue | View |
| `pill_3d_fmt` | 3D · %1$s | 3D · %1$s |
| `pill_plan_fmt` | Plan · %1$s | Plan · %1$s |
| `pill_section_viewpoint` | Point de vue | Viewpoint |
| `view_perspective` | Perspective | Perspective |
| `view_back` | Arrière | Back |
| `view_left` | Gauche | Left |
| `view_right` | Droite | Right |
| `pill_plan_view_2d` | Vue plan 2D | 2D plan view |
| `pill_section_projection` | Projection 2D | 2D projection |
| `perm_multi_select` | Multi-sélection | Multi-select |
| `search_fixture_cd` | Rechercher un projecteur par N° | Search for a fixture by number |
| `search_none_fmt` | Aucun projecteur nº %1$s | No fixture #%1$s |
| `reframe_cd` | Recadrer la vue | Reframe the view |
| `status_objects` (pl.) | 1 objet / %1$d objets | %1$d object / %1$d objects |
| `status_hidden_classes` (pl.) | %1$d classe masquée / %1$d classes masquées | %1$d hidden class / %1$d hidden classes |
| `status_gdtf_loading` | Modèles GDTF en cours… | Loading GDTF models… |
| `geo_panel_title` | Position GPS | GPS position |
| `geo_show_my_position` | Afficher ma position | Show my position |
| `geo_calibrate_first` | Calibrez d'abord (ci-dessous) pour afficher votre position et le satellite. | Calibrate first (below) to show your position and the satellite. |
| `geo_opacity` | Opacité | Opacity |
| `geo_dot_size` | Taille du point GPS | GPS dot size |
| `geo_section_calibration` | Calibration | Calibration |
| `geo_here_quick` | Je suis ici (pose rapide)… | I am here (quick)… |
| `geo_here_caption` | Touchez le plan là où vous êtes. Un 2e point corrige l'orientation et l'échelle. | Tap the plan where you stand. A second point corrects orientation and scale. |
| `geo_reset_calibration` | Réinitialiser la calibration | Reset the calibration |
| `refplan_panel_title` | Plan importé | Imported plan |
| `refplan_show` | Afficher le plan | Show the plan |
| `refplan_remove` | Retirer le plan | Remove the plan |
| `refplan_reading` | Lecture du plan… | Reading the plan… |
| `labels_section_appearance` | Apparence | Appearance |
| `labels_size` | Taille | Size |
| `labels_offset` | Écart au projecteur | Offset from the fixture |
| `labels_section_placement` | Placement sur le plan | Placement on the plan |
| `labels_placement_help` | Touchez une étiquette pour la saisir, puis faites-la glisser ; retouchez-la pour la relâcher. | Tap a label to pick it up, then drag it; tap it again to let go. |
| `labels_turn_off_fmt` | Désactiver (%1$d) | Turn off (%1$d) |
| `labels_separate_blocks` | Blocs séparés | Separate blocks |
| `account_settings_title` | Compte & réglages | Account & settings |
| `account_section_account` | Compte | Account |
| `account_section_services` | Services | Services |
| `account_signed_in` | Connecté | Signed in |
| `account_not_signed_in` | Non connecté | Not signed in |
| `pro_active` | Version Pro active — édition débloquée | Pro version active — editing unlocked |
| `pro_pitch` | La lecture est gratuite ; l'édition (patch, câblage, imports…) passe en Pro. | Reading is free; editing (patch, cabling, imports…) requires Pro. |
| `pro_unlock` | Débloquer la version Pro | Unlock Pro |
| `pro_restore` | Restaurer un achat | Restore a purchase |
| `home_account_prompt_title` | Créez votre compte (gratuit) | Create your account (free) |
| `home_account_prompt_body` | C'est par lui que vous recevez les projets partagés par votre chef de chantier, avec ses modifications en direct. Lire est gratuit ; seule l'édition passe en Pro. | It's how you receive projects shared by your crew chief, with their changes live. Reading is free; only editing requires Pro. |
| `home_account_prompt_action` | Créer un compte ou se connecter… | Create an account or sign in… |
| `models_title` | Modèles de projecteurs | Fixture models |
| `models_gdtf_signed_in` | GDTF Share : connecté | GDTF Share: signed in |
| `models_gdtf_sign_in` | Se connecter à GDTF Share… | Sign in to GDTF Share… |
| `layers_title` | Calques | Layers |
| `card_layer_fmt` | Calque : %1$s | Layer: %1$s |
| `card_fixture_number_fmt` | N° de projecteur : %1$s | Fixture number: %1$s |
| `card_dmx_fmt` | Adresse DMX : %1$s | DMX address: %1$s |
| `card_details` | Détails | Details |
| `banner_clear_selection` | Vider la sélection | Clear selection |
| `universe_edit` | Éditer | Edit |
| `universe_edit_cd_fmt` | Éditer le patch du projecteur %1$s | Edit the patch of fixture %1$s |
| `mode_hide_tap` | Touchez un élément à masquer | Tap an item to hide |
| `mode_hide_box` | Tracez un cadre à masquer | Drag a box to hide |
| `mode_isolate_tap` | Touchez un élément à isoler | Tap an item to isolate |
| `mode_isolate_box` | Tracez un cadre à isoler | Drag a box to isolate |
| `toolbar_all_off` | Tout remettre hors barres | Put everything back off the bars |
| `cabling_pdf_generating` | Génération du PDF… | Generating the PDF… |
| `tool_ref_plan_panel` | Régler le plan importé | Adjust the imported plan |
| `tool_models_panel` | Modèles importés | Imported models |

Titres d'outils repris de clés de ce tableau : `geoPanel` = `geo_panel_title`, `multiSelect` = `perm_multi_select`, `gdtfMapping` = `models_title`, `account` = `account_settings_title`, `layers` = `menu_layers`, `cabling` = `menu_cabling` (valeurs iOS de `ToolId.title`).

### 7.2 Textes modifiés

| Clé existante | Avant fr | Après fr | Après en |
|---|---|---|---|
| `tool_rect_select` | Sélection rectangle | Sélection par cadre | Rectangle selection |
| `tool_label_size` | Taille des étiquettes | Réglage des étiquettes | Label settings |
| `tool_recenter_camera` | Recentrer la caméra | Recadrer | Reframe |
| `tool_calibrate` | Calibrer : je suis ici | Je suis ici (pose rapide) | I am here (quick) |
| `nav_view_plan` (outil) | Vue plan | Vue plan 2D | 2D plan view |
| `tool_export_pdf` | Export PDF | PDF du plan | Plan PDF |
| `tool_gps`, `tool_gps_3d` | Ma position GPS / Ma position (3D) | Afficher ma position (une seule clé) | Show my position |
| `tool_solo` | Solo (sélection seule) | Isoler (solo) | Isolate (solo) |
| `tool_clear_solo` | Vider le solo | Vider l'isolement | Clear the solo |
| `tool_clear_selection` | Effacer la sélection | Vider la sélection | Clear selection |
| `tool_marker_size` | Taille du marqueur | Taille du point GPS | GPS dot size |
| `tool_import_model` | Importer un modèle 3D… | (outil) Importer un modèle 3D | Import a 3D model |
| `tool_import_ref_plan` | Importer un plan (DXF, image, PDF)… | (menu, inchangé) ; outil : Importer un plan (DXF, image, PDF) | Import a plan (DXF, image, PDF) |
| `opt_layer_colors` | Couleurs par calque | inchangé | Colours per layer *(était « Colour by layer »)* |
| `opt_hide_when_zoomed_out` | Masquer si trop dézoomé | Masquer les étiquettes trop dézoomées | Hide labels when zoomed too far out |
| `search_fixture_id_hint` | N° projecteur | N° de projecteur | Fixture number |
| `size_small`, `size_medium`, `size_large` (tranche B) | petite / moyenne / grande | Petite / Moyenne / Grande (segments) | Small / Medium / Large |
| `gap_near`, `gap_normal`, `gap_far` (tranche B) | proche / normal / loin | Proche / Normal / Loin (segments) | Near / Normal / Far |
| `sel_fixtures_selected_fmt` | « %1$d projecteurs sélectionnés » | → pluriel `sel_fixtures_selected` : 1 projecteur sélectionné / %1$d projecteurs sélectionnés | 1 fixture selected / %1$d fixtures selected |
| `plan_export_views_fmt` | %1$d vue(s) | → pluriel : 1 vue / %1$d vues | %1$d view / %1$d views |
| `plan_export_ready_fmt` | PDF prêt · %1$d page(s) | → pluriel : PDF prêt · 1 page / … %1$d pages | (pluriel en) |
| `cabling_export_full_menu` | Dossier câblage complet | inchangé | Full cabling pack *(était « Complete cabling pack »)* |
| `toolbar_customize_help` | Placez chaque outil… | texte iOS §12.1 | texte iOS §12.1 |
| `edge_none` | Aucun | Hors barres | Off the bars |
| `menu_options` | Options | → remplacée par `menu_more_options` | |
| — (en dur) | « Calques », « Compte », « GDTF Share » (outils), section Pro, carte de l'accueil | ressources (`menu_layers`, `account_settings_title`, `models_title`, `pro_*`, `home_account_prompt_*`) | |
| `fx_power_confirm` | C'est bon | inchangé | **Looks right** gardé (l'`en` iOS « Done » se confondrait avec « Terminé ») — §8 D22 |
| `sync_sign_out` | Déconnexion | Se déconnecter | Sign out (inchangé) |
| `tool_models_fmt` (titre du panneau Modèles importés) | Modèles 3D (%1$d) | Modèles importés (%1$d) | Imported models (%1$d) |
| `nav_power_cabling` (titre de l'outil `cabling`) | Câblage électrique | → `menu_cabling` « Câblage » (titre iOS) | Cabling |

Textes réutilisés tels quels : `opt_labels`, `patch_title`, `cam_top`,
`cam_front`, `nav_view_3d`, `menu_section_background`, `nav_custom_color`,
`tool_mask`, `tool_show_all`, `tool_calibrate_3`, `tool_satellite`,
`sat_loading`, `sat_out_of_coverage`, `nav_history`, `cabling_export_elec_menu`,
`cabling_export_dmx`, `cabling_export_plans`, `classes_title`, `opt_structure`,
`opt_legend`, `opt_show_labels`, `opt_label_content`, `opt_labels_of_selection`,
`opt_labels_same_type`, `opt_reset_label_offsets`, `common_close`,
`common_done`, `fx_power_confirm`, `power_watts_votes`, `ma_library`,
`join_project`.

### 7.3 Textes supprimés (à la tranche qui les rend inutiles)

`menu_options`, `menu_section_tools`, `menu_section_sync`, `nav_account`,
`nav_layers`, `tool_classes`, `nav_customize_toolbar`, `nav_gdtf_share_models`,
`nav_power_cabling` (si plus d'usage), `cam_viewpoints`, `cam_side`, `cam_iso`,
`cam_reset_view`, `opt_coloring_fmt`, `opt_satellite_map`, `opt_size_fmt`,
`opt_gap_fmt` (remplacés par les segments), `opt_label_detached`,
`opt_label_detached_cap` (remplacés par « Blocs séparés »), `plan_title_fmt`,
`layers_title_3d`, `home_my_account`, `common_back_3d` (ses cinq usages,
§2.6, disparaissent en A), `tool_ref_plan`, `tool_gps_3d`,
`sync_sign_in_title` (si plus d'usage). `tool_models_fmt` n'est **pas**
supprimée : elle reste le titre du panneau Modèles importés
(`ModelPlacementPanel.kt:84`), valeur changée en E. Contrôle à chaque
tranche : `grep` de la clé dans `app/src/main` (aucun usage restant) et test
de présence fr / en.

---

## 8. Divergences assumées avec iOS

« VISIBLE » = l'utilisateur la voit.

| # | Divergence | Pourquoi |
|---|---|---|
| D1 | **VISIBLE** — Les écrans pleins (Patch, Univers, Câblage, Modèles de projecteurs, Bibliothèque MA, Compte & réglages) se ferment par **← à gauche** (et le retour système), pas par « Terminé » à droite. Les dialogues et feuilles gardent « Terminé ». | Convention Material (navigation vers le haut) et geste retour du système, déjà en place dans toute l'app Android ; un « Terminé » à droite doublerait la flèche. |
| D2 | **VISIBLE** — La 3D garde une ← en haut à gauche (= Fermer le projet). | Habitude Android et existant ; même action que ⋯ › Fermer le projet. |
| D3 | **VISIBLE** — Menu ⋮ (`MoreVert`) ; familles en **pages** dans le même menu (« ‹ Affichage ») au lieu de sous-menus en cascade ; bascules qui gardent le menu ouvert. | M3 n'a pas de sous-menu en cascade ; une cascade sur téléphone sort de l'écran. Les bascules ouvertes sont le comportement Android actuel. |
| D4 | **VISIBLE** — « Masquer & isoler ▸ » existe aussi en 3D (solo de la sélection), d'où 10 lignes dans les deux vues. | Le solo 3D existe sur Android (pas sur iOS) ; il lui faut une place hors des barres. |
| D5 | **VISIBLE** — Entrées iOS absentes faute de fonction : Rendu 3D, Qualité, Fichier MVR, Placer sur une carte, Mes vues, Éléments masqués (feuille), Pan / Tilt 180°, Étiquette (côté), portée, Éditer le patch en multi, section Débogage. | Une entrée ne pointe jamais dans le vide (iOS §1.2). Porter ces fonctions = Q3. |
| D6 | **VISIBLE** — Pastille du plan avec une seule projection (Dessus) + « Vue 3D ». | Le plan Android ne sait dessiner que la vue de dessus ; la pastille garde sa fonction de bascule (décision 1). |
| D7 | **VISIBLE** — « Décor / structure » et « Légende » dans Affichage (plan). | Réglages propres à Android, sans autre maison. |
| D8 | **VISIBLE** — Satellite hors de France : interrupteur désactivé + message. | Android n'a que l'IGN (pas d'équivalent d'Apple Plans) ; règle SPEC-satellite-ign Android déjà livrée. |
| D9 | **VISIBLE** — Taille et Écart des étiquettes en 3 crans (segments), pas en curseurs. | Valeurs persistées et synchronisées actuelles (0,7 / 1 / 1,4 ; 0,5 / 1 / 2) gardées. |
| D10 | **VISIBLE** — Feuille Calques ouverte depuis le plan : liste en lecture (couleur, nombre d'objets), sans bascule de visibilité. | Le masquage des calques MVR n'existe pas sur Android (déjà constaté au portage v1.7) ; le LOD n'a de sens qu'en 3D. |
| D11 | **VISIBLE** — Légende du plan en haut à gauche (iOS : bas-gauche). | Place Android livrée ; la barre d'affectation et le panneau 3 points Android en dépendent. |
| D12 | **VISIBLE** — Accueil : « Compte & réglages » en bouton texte ; pas de « Fermer le projet » sur l'erreur. | L'accueil Android n'a pas de barre d'icônes ; l'erreur s'affiche déjà sur l'accueil, bouton Ouvrir présent (pas d'impasse). |
| D13 | **VISIBLE** — Panneaux flottants ancrés en bas à **gauche** (Plan importé, Modèles importés, Export PDF passent de droite à gauche), nouveaux `PanelID`. | Libérer le coin de la pile Rechercher / Recadrer ; ne pas réappliquer des décalages mesurés depuis l'autre coin. |
| D14 | **VISIBLE** — Bloc d'état : « (tronqué) » gardé, « proj. GDTF » retiré. | Android plafonne le nombre d'objets rendus : l'utilisateur doit le savoir ; le reste se lisait comme un journal (DESIGN_UI §3). |
| D15 | Patch : garde Pro à l'**enregistrement** de la fiche patch, pas à l'ouverture. | Comportement Android livré, conforme à « gratuit = lire » (on consulte la fiche librement). |
| D16 | Destinations en surimpression au-dessus de la vue de base (et non feuilles iOS). | Équivalent Android de « par-dessus le plan » ; évite aussi la reconstruction 3D au retour du patch. |
| D17 | Les PDF de câblage du ⋯ partagent le moteur de l'écran Câblage (fonction extraite). | Un seul code de génération. |
| D18 | rawId propres à Android (`threePointCalibration`, `clearSelection`, `gpsMarkerSize`, `structure`, `legend`, `colorMode`, `search`, `cameraPresets`) gardés. | JSON persistant : une disposition existante doit se relire. |
| D19 | **VISIBLE** — Dans le plan, « Terminé » est la flèche ← (icône, TalkBack « Terminé »), pas le mot. | Même place (gauche) et même rôle que la décision 2 ; une flèche de navigation est la forme Android (D1), et la 3D garde la même flèche (D2) : un mot à gauche dans le plan seul serait la seule exception de l'app. |
| D20 | **VISIBLE** — En 3D, « Afficher ma position » est désactivé tant que la scène n'est pas calibrée (légende « Calibrez d'abord… » dessous). | Le point GPS 3D n'existe que par la calibration (l'outil 3D actuel est déjà indisponible sans elle) ; absence de donnée, jamais Pro. |
| D21 | **VISIBLE** — Pastille de synchro aussi dans le plan (à droite sous la rangée) ; plus sur les écrans pleins. | Android la montre aujourd'hui dans le plan : la retirer ferait disparaître l'état de synchro (erreur comprise) pendant tout le travail sur le plan. Les écrans pleins, opaques, la cachent (iOS : 3D seulement). |
| D22 | **VISIBLE (en)** — « C'est bon » reste « Looks right » en anglais (iOS : « Done »). | « Done » se confondrait avec « Terminé » / « Done » des fenêtres. |
| D23 | **VISIBLE** — En 3D, « Isoler (solo) » est grisé sans sélection ni solo (iOS : une entrée sans donnée est plutôt absente). | Laisser la famille « Masquer & isoler » vide en 3D serait pire ; le grisé dit pourquoi rien ne se passe. Jamais selon Pro. |
| D24 | **VISIBLE** — L'outil `gdtfShare` (« GDTF Share ») ouvre « Compte & réglages » (Services), pas une fenêtre GDTF Share à part ; les dispositions Android qui l'avaient sont migrées vers `gdtfMapping`. | Android n'a pas de fenêtre de réglages GDTF Share séparée ; la connexion vit dans Compte & réglages (décision 3). L'ancien outil Android ouvrait les modèles : la migration garde ce que l'utilisateur avait voulu. |

---

## 9. Décisions / questions

### 9.0 Réponses de l'utilisateur (2026-10-15) — FIGÉES

Les trois questions ci-dessous ont été tranchées par l'utilisateur le
2026-10-15 (plan validé, « porte le rangement des menus sur Android et le
web »). Elles s'ajoutent aux décisions iOS du §0 et ne se re-litigent pas.

- **Q1 — Barre flottante historique : RETIRÉE.** Sur téléphone, plus de barre
  flottante par défaut (chaque outil garde une autre place, `ToolId.home`, et
  reste ancrable ; bandeaux de mode §3.14). La **tablette** (plus petite
  largeur ≥ 600 dp) reçoit la barre **gauche préremplie** Patch · Univers ·
  Câblage · Calques, dans les deux vues, avec la **migration** de la
  disposition vide déjà enregistrée (§3.13 : une disposition vide d'avant la
  migration compte comme « non personnalisée »). Une disposition réellement
  personnalisée par l'utilisateur est gardée. Tranches C (retrait) et E
  (tablette, migration).
- **Q2 — Toucher en 3D : REMPLACE la sélection**, comme le plan et iOS. Le
  bouton **Multi-sélection** permanent (rangée du haut, deux vues) sert à
  ajouter / retirer. Le **cadre de sélection s'aligne aussi** : même règle dans
  les deux vues, celle d'iOS (armer le cadre force le multi, le cadre ajoute à
  la sélection ; couper le multi vide la sélection et coupe le cadre).
  Tranche C (`ToolId.MULTI_SELECT` créé avec le bouton).
- **Q3 — Fonctions iOS absentes d'Android : HORS CHANTIER.** Pan / Tilt 180°
  avec la portée, côté d'étiquette par projecteur, patch multiple (adressage
  séquentiel), Mes vues, Placer sur une carte, export MVR, rendu 3D / qualité,
  masquage des calques MVR : chantier séparé « parité des fonctions », plus
  tard. Dans ce chantier-ci, **aucune entrée ne pointe dans le vide** : ces
  entrées restent absentes (§8 D5), seules les trois petites additions déjà
  prévues sont faites (Arrière / Gauche en 3D, Réinitialiser la calibration,
  « Éditer » dans l'univers).

Rappel du texte des questions, tel qu'il a été posé :

**Q1 — Barre flottante historique (téléphone et tablette).** Aujourd'hui, la
3D montre en bas à gauche 6 boutons d'outils et le plan 12, par défaut, sur
tous les appareils. iOS a tranché : iPhone sans barre par défaut, iPad avec
la barre gauche préremplie, tout le reste dans les boutons permanents, le ⋯
et les panneaux (décision 9). **Recommandation : la retirer** partout (une
disposition déjà personnalisée par l'utilisateur est gardée ; chaque outil
reste ancrable et a une autre place ; bandeaux de mode ajoutés, §3.14 ; la
tablette reçoit la barre gauche préremplie). Sinon : la garder sur
téléphone, réduite aux outils sans autre bouton à l'écran (Masquer, Isoler,
Tout réafficher, Vider l'isolement) — divergence visible de plus — et jamais
en même temps que la barre préremplie de la tablette.

**Q2 — Toucher en 3D.** Aujourd'hui, toucher un projecteur en 3D l'**ajoute**
à la sélection (on en accumule plusieurs sans le vouloir), alors que dans le
plan il **remplace**. iOS : toucher = remplace, et le bouton Multi-sélection
permet d'ajouter. **Recommandation : s'aligner** (même règle dans les deux
vues, bouton Multi-sélection permanent) — sinon la fiche de sélection unique
se transformerait en bannière dès le 2e toucher. Changement visible pour les
testeurs Android.

**Q3 — Fonctions iOS absentes d'Android que la structure suppose.** Pan 180° /
Tilt 180° avec la portée (décisions 4 et 5), côté d'étiquette par projecteur,
« Éditer le patch » en multi (adressage séquentiel), projections 2D du plan,
Mes vues (Vue 1·2·3), Placer sur une carte, export MVR, rendu 3D / qualité,
masquage des calques MVR. Le rangement les laisse **absentes** (rien ne pointe
dans le vide) et n'ajoute que trois petites choses (Arrière / Gauche en 3D,
Réinitialiser la calibration, « Éditer » dans l'univers). **Recommandation :
ne pas les porter dans ce chantier** ; ouvrir ensuite un chantier « parité des
fonctions » en commençant par Pan / Tilt + portée (la fiche y gagne son
contenu iOS) puis le patch multiple. Faut-il au contraire en inclure une
(tranche F) ?

---

## 10. Tranches (en série)

Même logique qu'iOS §17 : on construit **d'abord les destinations et les
panneaux, puis le menu qui les relie** (sinon le menu pointerait dans le vide
et retirer « Vue plan » du menu laisserait le plan sans accès). À la fin de
chaque tranche : app cohérente, tous les tests verts (départ 539 + 4 ignorés),
APK construit, commit propre (« Co-Authored-By: … »), cette spec mise à jour
(*Réalisé*, constats).

### Tranche A — Socle : destinations en surimpression, Compte & réglages, sorties

Périmètre :
1. `ProjectDestination`, `ProjectSurface`, `SceneNavState` + `SceneNav.reduce`,
   `SceneScreen.open(dest, from)` ; vue de base + destination en surimpression
   (§1.3, §4.1) ; `SceneMode` remplacé ; ordre des retours système.
2. Câblage : `CablingScreen(initialTab)`, `CablingOrigin`, `CablingAssignRoute`
   (§4.3) — parcours livrés inchangés.
3. Feuille « Calques » (fusion `LayersSheet` + `ClassesSheet`, segment) ;
   depuis le plan sans LOD.
4. Écran « Modèles de projecteurs » (ligne GDTF Share avec « Se
   déconnecter » au bout, sous-volets Bibliothèque MA et connexion, liste
   toujours visible, type touché déconnecté → connexion puis recherche).
   L'outil `gdtfShare` et l'ancienne entrée « GDTF Share (modèles 3D) »
   l'ouvrent (libellés inchangés jusqu'à C / E).
5. « Compte & réglages » plein écran (§3.11) + accueil (bouton, carte, textes
   en ressources ; hôte dans `App` avec son retour système) + outil `account`
   ; `sync_sign_out` → « Se déconnecter ».
6. « Fermer le projet » pendant la lecture du .mvr.
7. Les **cinq** écrans pleins qui disent « Retour 3D » (Patch, Univers,
   Câblage, Modèles de projecteurs, Bibliothèque MA — aussi depuis l'accueil)
   : ← « Retour », retour à la vue d'origine ; `common_back_3d` supprimée.
8. `PlanStartAction` (§4.2) ; crochets `MVR_AUTO_PLAN`, `MVR_AUTO_DEST`,
   `MVR_AUTO_PLAN_START`, `MVR_AUTO_HOME_DEST` ; `testTagsAsResourceId` en
   DEBUG ; données `app/src/test/resources/menus/`.
9. Les anciennes entrées du ⋯ pointent vers `open(…)` (le menu lui-même change
   en C) : « Compte » → Compte & réglages, « Classes… » → Calques sur
   l'onglet Classes, « Calques… » (3D) → Calques, « GDTF Share (modèles 3D) »
   → Modèles de projecteurs, « Bibliothèque MA » → destination MaLibrary ;
   profil MA reçu → `open(MaLibrary)` par-dessus la vue courante.

Lignes : 9, 42, 43, 70, 71, 85, 87, 90, 91, 94, 95, 96, 97, 98, 100.

Tests unitaires (`ProjectNavigationTest`) :
- `ProjectDestination.id` stables et distincts (`Cabling(DMX)` ≠ `Cabling(null)`).
- `SceneNav.reduce` : ouvrir / fermer une destination rend la base intacte ;
  retour système dans chaque état ; ouvrir le Patch depuis le plan puis
  revenir → base = PLAN.
- `CablingAssignRoute` : les 10 cas du tableau §4.3.
- `PlanStartAction` consommée une seule fois.
- `AccountSettingsSections` : ordre Compte, MVR Viewer Pro, Services ; pas de
  Débogage ; Compte absent sans synchro.
- Sous-volets : MaLibrary ouverte depuis Modèles → retour vers Modèles, pas
  vers la base (réducteur ou état de l'écran, testé).
- Textes : chaque nouvelle clé de la tranche existe en fr et en (`TestRes`).

Contrôle à l'écran (émulateur Medium_Phone, `mini.mvr`, portrait et paysage) :
- [x] depuis le plan (`MVR_AUTO_PLAN=1`, `MVR_AUTO_DEST=…`) : Patch, Univers,
      Câblage (onglet DMX demandé), Calques (avec Classes, sans LOD), Modèles
      de projecteurs, Compte & réglages, Historique s'ouvrent par-dessus ;
      ← ramène au plan **avec son zoom et sa sélection** — *vérifié depuis le
      plan pour Patch, Câblage (DMX), Calques / Classes, Modèles de
      projecteurs et Compte & réglages ; Univers et Historique vérifiés
      depuis la 3D (même chemin `open`)* ;
- [x] la surimpression cache entièrement la 3D et capte les touchers ; rendu
      3D dessous mesuré (pas de saccade au retour, pas de chauffe anormale) ;
- [x] Câblage ouvert par-dessus le plan → « Sur le plan » → toucher →
      « Terminé » de la barre → Câblage par-dessus le plan, sur la voie
      travaillée ; parcours depuis la 3D inchangé ;
- [x] Compte & réglages depuis l'accueil (bouton et carte) ; GDTF Share s'y
      connecte et s'y déconnecte (bouton, pas la ligne ; essai avec
      `MVR_GDTFSHARE_MOCK_DIR`) ; la résolution des modèles repart après
      connexion — *non rejoué à l'écran : assuré par l'effet existant de
      `SceneScreen` clé sur `GdtfShareClient.loggedInFlow`* ;
- [x] Modèles de projecteurs déconnecté : liste visible, un type touché →
      connexion → recherche ; Bibliothèque MA → ← revient à Modèles ;
- [x] ← des cinq écrans pleins : TalkBack « Retour » (`uiautomator dump`) ;
- [x] lecture de `test-gc-v1.mvr` : « Fermer le projet » → accueil, aucun
      plantage, aucun journal d'erreur après coup ;
- [x] en anglais (langue système de l'émulateur) et en français
      (`adb shell cmd locale set-app-locales com.minou.mvrviewer --locales
      fr-FR`) : titres et boutons nouveaux traduits.

*Réalisé (tranche A, 2026-10-15)* — 559 tests unitaires verts + 4 ignorés
(539 au départ ; 20 nouveaux dans `ProjectNavigationTest`), APK construit.

- Code : `ProjectNavigation.kt` (pur : `SceneBase`, `ProjectSurface`,
  `ProjectDestination` + `fromHook`, `CablingOrigin`, `CablingAssignRoute`,
  `SceneNavState` / `SceneNav.reduce`, `PlanStartAction` /
  `PlanStartRequest`, `AccountSettingsSections`, `FixtureModelsPane` /
  `FixtureModelsNav`, `LayersSheetTabs`, `RenderLifecycleRule`) ;
  `ProjectDestinationHost.kt` (`FullScreenDestination`, `SceneBaseLayer`,
  `rememberRenderLifecycle`) ; `AccountSettingsScreen.kt` ; `DebugHooks.kt` ;
  `LayersSheet.kt` fusionné (`ClassesSheet.kt` supprimé) ; `GdtfShareScreen.kt`
  → `FixtureModelsScreen` ; `SceneScreen` sans `SceneMode` (valeurs persistées
  « THREE_D » / « PLAN » inchangées, `ViewStateStore.base`).
- `SceneScreen.open(dest, from)` est le seul point d'entrée ; un seul
  `BackHandler` de scène (`SystemBack`), les écrans pleins posent le leur
  (composé après la vue de base, donc prioritaire), leurs sous-volets aussi
  (recherche et connexion GDTF Share, Bibliothèque MA, connexion de Compte &
  réglages).
- **Rendu 3D sous un écran plein : mis en PAUSE.** Mesure émulateur
  (SwiftShader) : ~600 % CPU 3D visible → ~15–25 % sous la Liste de patch.
  SceneView ne dessine qu'à l'état RESUMED (sa boucle attend sinon, vérifié
  dans le bytecode 4.22) : la `Scene` reçoit un cycle de vie plafonné à
  STARTED tant qu'un écran plein la recouvre (`rememberRenderLifecycle`). Au
  retour, caméra et sélection sont intactes, aucune reconstruction.
- La vue de base recouverte est muette pour TalkBack et **rognée à ses bornes**
  (constat paysage : étiquettes du plan et ombre de la fiche débordaient dans
  la marge de la barre de navigation, sous l'écran plein).
- **Défaut corrigé** en passant : « Fermer le projet » pendant la lecture —
  `runCatching` attrapait l'annulation, le chargement abandonné affichait
  alors une erreur et retirait le fichier des récents ; garde `isActive` avant
  toute écriture d'état (`SceneViewModel`), progression close au `reset()`.
- Entrées provisoires de l'ancien ⋯ (jusqu'à C) : « Compte & réglages… »
  (remplace « Compte », toujours présent), « Calques… » ajouté au ⋯ du plan
  (la feuille, sans LOD, n'y avait pas d'autre entrée), « Classes… » ouvre la
  feuille sur l'onglet Classes. « Partager ce projet » s'ouvre sans garde Pro
  (la garde reste à la publication) dès cette tranche, puisque `open` est le
  seul chemin. Titre de l'outil `account` passé à « Compte & réglages »
  (texte en dur retiré ; le reste des titres d'outils reste pour E).
- Modèles de projecteurs : la phrase « …ou choisis toi-même le modèle… » ne
  s'affiche qu'à l'état connecté (elle prolonge « Améliorer tous »).
  Nombre d'objets d'une classe passé en pluriel (`classes_objects`).
- Crochets : lus dans `onCreate` ET `onNewIntent` (l'émulateur relivre
  l'intention à l'instance en cours) ; `testTagsAsResourceId` en DEBUG ;
  étiquettes `destination.<id>`, `layersSheet`, `accountSettings`,
  `services.gdtfShare`, `models.gdtfStatus`, `models.gdtfSignIn`,
  `home.accountSettings`, `loading.closeProject`.
- Données d'essai : `app/src/test/resources/menus/` (`mini.mvr`, `mini.dxf`,
  `bad.mvr`, copiés d'iOS) ; `MvrParser` dépend d'`android.util.Xml`, le test
  lit donc l'archive avec `javax.xml` ; `mini.dxf` passe par le vrai
  `DxfParser`.
- Constats d'essai : avec `MVR_GDTFSHARE_MOCK_DIR`, une connexion faite dans
  le formulaire ENREGISTRE les identifiants saisis
  (`shared_prefs/mvrviewer.gdtfshare.xml`) : sans le crochet, l'app tente
  ensuite une vraie connexion avec eux — effacer ce fichier après l'essai.
  Le champ de recherche permanent garde le focus et rouvre le clavier
  (existant, il disparaît en C). « 3 projecteur(s) » des types et « Plan ·
  6 projecteur(s) » : « (s) » existants, hors tranche (§11, C).
- Captures : `~/.mvrviewer-tools/scratch/android-menus/A/`.

### Tranche B — Panneaux : Position GPS, Plan importé lisible, Réglage des étiquettes

Périmètre :
1. `GeoPositionPanel` + `GeoPanelContent` (§3.6) dans les deux vues, nouveaux
   `PanelID` (`geoPosition3D`, `geoPositionPlan`), **`ToolId.GEO_PANEL`
   créé ici** (catalogues 3D et plan), « Réinitialiser la calibration »,
   calibrations depuis la 3D par `PlanStartAction`, `armHereAnchor` unique.
2. Retraits : curseur flottant d'opacité du plan ; satellite, opacité et ligne
   d'état du ⋯.
3. `RefPlanPanel` partagé (§3.7) dans les deux vues, lecture pour tous,
   `ProGate` / `gatedSetter` + throttle ; Rotation verticale en 3D
   seulement ; `PanelID` `referencePlan3D` / `refPlanPlacementPlanLeading` ;
   **hissés dans `SceneScreen`** : import, centrage (`planData`),
   `onSetReferencePlan`, persistance débouncée du placement + push (§3.7).
   L'outil `importDXF` du plan garde ses deux états jusqu'à E (importer ;
   sinon ouvrir / fermer le panneau, **sans garde** désormais).
4. `LabelSettingsPanel` partagé (§3.8) dans les deux vues (`labels3D`
   nouveau, `labelsPlan` gardé) ; Écart et « Masquer les étiquettes trop
   dézoomées » ajoutés ; outil `labelSize` ouvre le panneau.
5. **Entrées provisoires de l'ancien ⋯** (jusqu'à C), sans quoi les panneaux
   de la 3D ne seraient joignables que par crochet :
   - les deux vues : « Position GPS… » (toujours, à la place du satellite,
     de son curseur et de la ligne d'état) ; « Réglage des étiquettes… » (à
     la place des champs, blocs, taille, écart, dézoom, sélection, même
     type, replacer ; la bascule « Étiquettes » reste) ;
   - 3D : « Importer un plan (DXF, image, PDF)… » (Pro au toucher) et, s'il
     y a un plan, « Régler le plan importé… » (gratuit) ;
   - plan : l'entrée de l'outil DXF reste (deux états, sans garde pour
     ouvrir le panneau) ; « Calibrer : je suis ici » et « Calibrer en 3
     points » quittent les *Outils* du ⋯ pour le panneau.
6. Crochets `MVR_AUTO_GEOPANEL`, `MVR_AUTO_REFPLAN_PANEL`, `MVR_AUTO_LABELS_PANEL`.

Lignes : 11, 12, 13, 14, 15, 16, 25, 26, 29, 30, 31, 32, 33, 34, 35, 36, 37,
67, 68, 69, 78, 99, 101.

Tests unitaires (`GeoPanelContentTest`, `ProGateTest`, `RefPlanPanelRulesTest`,
`LabelSettingsSectionsTest`) :
- contenu du panneau selon vue / calibration / satellite / couverture IGN
  (taille du point en 3D seulement ; message hors de France ; « Calibrez
  d'abord » sans calibration ; réinitialiser seulement si calibré) ;
- statut : refus prioritaire, 3 points, 1 point, n points, non calibré ;
- `gatedSetter` : gratuit → valeur inchangée, un seul refus par rafale de
  0,8 s ; Pro → valeur écrite ;
- panneau Plan importé : Rotation verticale en 3D seulement ; section Calques
  si plus d'un calque ;
- panneau d'étiquettes : Placement, Blocs séparés et dézoom dans le plan
  seulement ;
- `ToolId.GEO_PANEL` dans les deux catalogues, rawId « geoPanel » ;
- centrage du plan importé calculé par `SceneScreen` = centrage historique
  du plan (`mini.mvr` + `mini.dxf`).

Contrôle à l'écran (`mini.mvr` / `mini.dxf`, `--ez MVR_PRO false` puis `true`) :
- [x] 3D et plan : panneau Position GPS réductible, borné sous la barre du
      haut (3D) / le titre et la recherche (plan), au-dessus de la pastille
      de sélection et de la bannière actuelles, en portrait et en paysage
      (la borne passe sur la pastille « Vue » et le bloc d'état en C) ;
- [x] chaque panneau 3D s'ouvre par l'ancien ⋯ (entrées provisoires) ;
- [x] satellite + opacité en gratuit ; plus aucun curseur flottant —
      *rejoué en Pro ; ni l'interrupteur ni le curseur n'ont de garde
      (`GeoPositionPanel`, réglages de lecture)* ;
- [x] « Je suis ici » depuis la 3D → plan, aide d'ancre, pose → « 1 point
      posé… » ; « Réinitialiser la calibration » → « Non calibré » ;
- [x] 3 points depuis la 3D et le plan : l'assistant se lance (déroulé
      inchangé) ; gratuit → écran Pro **avant** l'ouverture du plan —
      *vérifié depuis la 3D (Pro : assistant ouvert dans le plan ; gratuit :
      écran Pro, la 3D reste) ; depuis le plan, même garde, même fonction
      `startThreePoint` que l'outil* ;
- [x] Plan importé en gratuit : tout lisible, chaque bouton / interrupteur /
      curseur / calque ouvre l'écran Pro, rien ne bouge, un seul écran Pro par
      glissé ; en Pro : modifications visibles ; en 3D, la rotation verticale
      se voit — *glissé de l'homothétie en gratuit : un écran Pro, curseur
      et manifeste inchangés ; « une rafale = un écran » prouvé en JVM
      (`ProGateTest`)* ;
- [x] import d'un plan depuis la 3D (Pro) : panneau 3D ouvert, plan centré
      comme depuis le plan ; un réglage fait en 3D est retrouvé après
      fermeture / réouverture du projet et part au projet partagé — *centrage
      (−500, −500) sur `mini.mvr`, rotation et rotation verticale relues du
      manifeste après réouverture ; le partage n'a pas été rejoué à l'écran
      (pas de projet partagé sur l'émulateur) : même chemin `commitTransform`
      qu'avant, appelé par l'effet débouncé de `SceneScreen`* ;
- [x] panneau d'étiquettes 3D et plan : champs, taille, écart ; plan :
      sélection, même type, désactiver, blocs séparés, replacer ;
- [x] en anglais (et en français).

*Réalisé (tranche B, 2026-10-15)* — 595 tests unitaires verts + 4 ignorés
(559 après A ; 36 nouveaux : `GeoPanelContentTest`, `ProGateTest`,
`RefPlanPanelRulesTest`, `LabelSettingsSectionsTest`, `FloatingPanelSlotTest` ;
`PlanScreenRulesTest` et `SatelliteScreenTest` suivent le satellite et la ligne
d'état dans le panneau), APK construit, contrôle à l'émulateur Medium_Phone
(mémoire 2 Go, démarré en 20 s).

- Code : `GeoPositionPanel.kt` (`GeoPanelRow`, `GeoPanelContent.make` pur,
  `GeoPanelContent.status`, composable), `RefPlanPanel.kt`
  (`RefPlanPanelRules`, `RefPlanImport` : lecture, plan prêt, placement
  initial ; composable), `LabelSettingsPanel.kt` (`LabelSettingsSections`,
  `LabelPlacementActions`, composable ; l'ancien panneau privé du plan est
  supprimé), `ProGate.kt` (`ProRefusalThrottle`, `ProGate.setter`) +
  `ProAccess.gatedSetter`, `ScenePanels.kt` (état des panneaux ouverts,
  `BottomLeadingPanel`, habillage commun `FloatingPanelCard`),
  `PanelPlacementMath.floatingPanelSlot` (portage iOS, mêmes cas de test).
- `ToolId.GEO_PANEL` (« geoPanel ») dans les deux catalogues, hors section
  *Outils* ; l'outil `labelSize` (3D) ouvre / referme le panneau d'étiquettes ;
  `calibrate` et `threePointCalibration` (plan) quittent la section *Outils* ;
  l'outil `importDXF` importe, sinon ouvre / ferme le panneau **sans garde**.
- **Hissé dans `SceneScreen`** : `setReferencePlan` (persistance, DXP1,
  manifeste, calques éteints du DXF), import (`importRefPlan`, garde « importer
  un plan », centrage par `planData` de la scène rendue), persistance débouncée
  du placement sur `refPlanTransformVersion` (500 ms, puis `commitTransform`),
  `resetCalibration` (garde, calibration vidée, enregistrée, `calibTick++`),
  `toggleRefPlanLayer`. Le plan ne fait plus que redessiner sur la version.
- `ScenePanels` (dans `SceneScreen`) : une bascule 3D ↔ plan referme les
  panneaux ; le **dernier ouvert passe devant** (`front`, `zIndex`) — constat
  émulateur : les trois partagent le coin bas-gauche et un panneau ouvert après
  un autre s'ouvrait derrière lui.
- Place : `BottomLeadingPanel` mesure la place naturelle avant la montée ; en
  haut la barre 3D (haut de la scène) + la recherche, ou le titre + la
  recherche du plan ; en bas la pastille de sélection, le bandeau de mesure,
  la fiche / bannière et les aides du plan, la barre d'affectation, le panneau
  3 points et la mention IGN (lue sans sa montée). La carte entière est bornée
  (en-tête compris), seul le contenu défile ; largeur 264 dp imposée aussi à
  la colonne de `MovablePanel`.
- Textes : clés §7.1 de la tranche + `refplan_reading` (« Lecture du plan… »,
  iOS, indicateur pendant un import lancé depuis la 3D) ; `size_*` / `gap_*`
  passent à la majuscule (segments « Petite · Moyenne · Grande », « Proche ·
  Normal · Loin ») ; `tool_label_size` = « Réglage des étiquettes » (l'outil
  ouvre le panneau), `opt_hide_when_zoomed_out` = libellé iOS ; supprimées :
  `opt_size_fmt`, `opt_gap_fmt`, `opt_label_detached`,
  `opt_label_detached_cap`, `opt_satellite_map`.
- Crochets `MVR_AUTO_GEOPANEL`, `MVR_AUTO_REFPLAN_PANEL`,
  `MVR_AUTO_LABELS_PANEL` (`--es … 1` ou `--ez … true`) ; étiquettes de test
  `geoPanel`, `refPlanPanel`, `labelsPanel`.
- **Défaut corrigé avant livraison** : l'import hissé était d'abord un effet
  clé sur l'URI choisie ; remettre la clé à null avant la fin l'annulait à la
  suspension suivante — le plan n'était jamais posé. Désormais une coroutine
  de la scène (`importPickedRefPlan`).
- Constats (hors tranche) : la barre de préhension de `MovablePanel` s'étire
  sur toute la largeur de l'écran pour les autres panneaux (assistant 3
  points, modèles importés, export PDF) — corrigé ici pour les trois panneaux
  du bas-gauche seulement ; ART signale `Scene3DScreen` au-delà de sa limite
  de compilation (méthode interprétée, existant) ; `SatelliteUi.menuRow` n'a
  plus d'appelant (gardé, testé) ; l'écran Pro reste en français dans les deux
  langues (existant).
- Captures : `~/.mvrviewer-tools/scratch/android-menus/B/`.

### Tranche C — Menu ⋯ unique, pastille, rangée du haut, pile bas-droite, état

Périmètre :
1. `MainMenuModel` / `MainMenu` (§3.2, pages de famille) dans les deux vues ;
   suppression de `SceneOptionsMenu` et de la section *Outils*.
2. Partager & exporter (PDF du plan via `PlanStartAction.EXPORT_PDF`, PDF de
   câblage par la fonction extraite), Fermer le projet.
3. `ViewPill` + `ViewPillModel` (§3.3), presets Arrière / Gauche, suppression
   du menu « Points de vue ».
4. `SceneTopRow` (§3.1) : suppression de la `TopAppBar` 3D et des boutons
   flottants du plan ; Multi-sélection (Q2 tranchée : oui) avec **`ToolId.MULTI_SELECT`
   créé ici** ; boutons masqués quand l'outil est ancré ; bannière « nouvelle
   version » sous la rangée.
5. Pile bas-droite Rechercher + Recadrer (§3.4) ; panneaux en bas-gauche
   (`importedModels3DLeading`, `exportPlanLeading`) ; cadrage du plan sous
   la pastille (`planFitMargins`, §3.3) pour l'affectation, les 3 points et
   Recadrer ; borne haute des panneaux rebranchée sur la pastille et le bloc
   d'état.
6. Bloc d'état 3D (§3.5), pastille de synchro déplacée (3D : bloc d'état ;
   plan : à droite sous la rangée, D21), pastille « Plan · N » retirée,
   `LEGEND_TOP_ANDROID` ajusté.
7. Masquer & isoler (§3.2.3) + bandeaux de mode (§3.14).
8. Barre flottante historique retirée (Q1 tranchée, §9.0). Seul manque transitoire jusqu'à D :
   « Vider la sélection » n'a plus de bouton à l'écran (sauf s'il est ancré)
   — toucher le vide désélectionne, comme aujourd'hui (cadre armé dans le
   plan : le couper d'abord par son bouton permanent).
9. Retrait de « Rejoindre un projet » et de « Bibliothèque MA » du ⋯ ;
   suppression des entrées provisoires de la tranche B (remplacées par le
   menu).

Lignes : 1, 2, 3, 4, 5, 6, 7, 8, 10, 17, 18, 19, 20, 21, 22, 23, 24, 27, 28,
38, 39, 40, 41, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58,
59, 72, 73, 74, 75, 76, 77.

Tests unitaires (`MainMenuModelTest`, `ViewPillModelTest`, `SelectionRulesTest`,
`ToolbarLayoutTest` mis à jour) :
- ordre exact du premier niveau (10 lignes) identique en 3D et dans le plan ;
- pas de `isPro` dans le contexte ; mêmes entrées quel que soit l'achat ;
- apparitions : sans plan → pas de « Régler le plan importé… » ; sans modèle →
  pas de « Modèles importés » ; sans câblage → pas de section PDF de câblage ;
  Décor 3D en 3D seulement ; Colorer par câblage seulement avec plus d'un
  mode ; Masquer des éléments et Tout réafficher dans le plan seulement ;
  « Réglage des étiquettes… » toujours présent ;
- profondeur : aucune action au-delà de la page de famille (seules les listes
  de valeurs vont au 3e niveau) ;
- pastille : 3D (6 points de vue + « Vue plan 2D »), plan (Dessus + « Vue 3D ») ;
- sélection : toucher remplace / ajoute selon Multi ; le cadre force le multi ;
  couper le multi vide la sélection et coupe le cadre (Q2 = oui) ;
- pile bas-droite : montée au-dessus d'une fiche, d'une barre d'affectation,
  de la mention IGN (cadres mesurés à l'émulateur) ;
- `planFitMargins` : contenu entre le bas de la pastille + 24 dp et le haut
  de la barre du bas ; règle d'origine sans pastille mesurée ;
- 3D : « Masquer & isoler » ne contient que « Isoler (solo) » (grisé sans
  sélection ni solo) et « Vider l'isolement » (si solo) ;
- textes : clés nouvelles fr / en, clés supprimées absentes.

Contrôle à l'écran (`mini.mvr`, MF, portrait et paysage, fr et en, gratuit et
Pro ; menus ouverts par `adb shell input tap`, cibles lues par `uiautomator
dump`) :
- [ ] captures du premier niveau et de chaque famille, 3D et plan ; en
      paysage, un court défilement est accepté (décision 11) ;
- [ ] Patch / Univers / Câblage en 2 touchers depuis les deux vues ;
- [ ] pastille : bascules 3D ↔ plan ; Face et Droite = mêmes côtés qu'iOS sur
      `mini.mvr` ; pendant une affectation, « Vue 3D » ramène à la 3D sans
      rouvrir le Câblage ;
- [ ] rangée du haut : ← à gauche, trois ronds à droite, actif jaune / glyphe
      noir ; outil ancré → bouton permanent masqué ;
- [ ] pile bas-droite : au-dessus de la fiche, de la barre d'affectation, du
      panneau 3 points ; jamais sur la mention IGN ; « Aucun projecteur nº 999 » ;
- [ ] légende, barre d'affectation et panneau 3 points sans chevauchement
      avec la pastille (portrait, paysage) ; en affectation, la rangée de
      projecteurs du haut n'est pas sous la pastille — tests
      `CablingPlanScreenTest` verts ;
- [ ] pastille de synchro (projet partagé) : bloc d'état en 3D, à droite sous
      la rangée dans le plan, jamais sous la pastille « Vue » ; bannière
      « nouvelle version » sans recouvrir ← ni ⋯ ;
- [ ] PDF du plan depuis la 3D (plan ouvert en mode export) ; PDF de câblage
      depuis le ⋯ (attente puis partage) ;
- [ ] « Fermer le projet » depuis les deux vues ;
- [ ] bandeaux masquage / solo, sortie par leur icône.

### Tranche D — Fiche de sélection unique, bannière multi, Univers « Éditer »

Périmètre :
1. `SelectionCard` / `MultiSelectionBanner` (§3.9) construits par
   `SceneScreen`, posés dans les deux vues ; version compacte en paysage ;
   projecteur gardé visible.
2. Univers DMX : « Éditer » par projecteur (fiche patch existante).
3. Crochet `MVR_AUTO_SELECT`.

Lignes : 60, 61, 62, 63, 64, 65, 66, 79, 86, 88.

Tests unitaires (`SelectionCardModelTest`, `SelectionCardLayoutTest`) :
- titre « Type — Nom », titre court ; détails localisés fr / en ;
- conso : « 450 W · 3 votes » / « 1 vote » / GDTF / aucune ;
- style compact sous 480 dp, budget du quart de la hauteur ; décalage de
  « garder visible » (jamais sous la pastille) ;
- bannière : pluriel « 1 projecteur sélectionné » ;
- pas de fiche pendant l'assistant 3 points ni l'affectation (règle existante).

Contrôle à l'écran :
- [ ] fiche 3D et plan identiques (captures côte à côte), portrait et paysage
      (compacte, Détails, quart de la hauteur) ;
- [ ] croix → désélection ; toucher la fiche → rien ; toucher le vide →
      désélection ;
- [ ] « Patch » → fiche patch ; gratuit : enregistrer → écran Pro ;
- [ ] « C'est bon » dans le plan (conso connue) ;
- [ ] multi dans les deux vues : bannière commune, ✕ ;
- [ ] Univers avec chevauchement : un « Éditer » par projecteur ;
- [ ] appui long → fiche patch dans les deux vues ; affectation câblage
      intacte.

### Tranche E — Finitions : outils, tablette, accessibilité, textes

Périmètre :
1. Titres des outils (§7.2, dont `cabling` → « Câblage »), nouveaux
   `ToolId` `refPlanPanel`, `modelsPanel`, `gdtfMapping` (`geoPanel` est né
   en B, `multiSelect` en C), outils à libellé fixe (`importDXF`,
   `importModel` n'importent plus que), `gdtfShare` au sens iOS (§4.6),
   catalogues (§3.13).
2. Tablette : `ToolbarIdiom`, défaut tablette, `ToolbarLayoutStore.load(view,
   idiom)`, migration v2 (dispositions vides effacées une fois, `gdtfShare` →
   `gdtfMapping`), enregistrement au seul changement utilisateur,
   barre gauche qui évite légende / panneaux / fiche ; crochets
   `MVR_RESET_TOOLBARS`, `MVR_FORCE_TABLET_LAYOUT`.
3. `ToolbarCustomizeSheet` : texte iOS, « Hors barres », « Tout remettre hors
   barres ».
4. Table `ToolId.home` testée.
5. Pluriels et vocabulaire des surfaces touchées (§7.2) ; « OK » → « Terminé »
   (rapport MA, couleur personnalisée) ; panneau Export PDF titré « PDF du
   plan » ; panneau Modèles importés titré « Modèles importés (%d) ».
6. TalkBack : boutons des barres, cases des rangées du menu, pastille, fiche.

Lignes : 80, 81, 82, 83, 84, 89, 92, 93.

Tests unitaires (`ToolbarDefaultsTest`, `ToolHomeTest`, `LocalizationTest`) :
- tablette sans disposition → barre gauche [patchList, universe, cabling,
  layers] dans les deux vues ; téléphone → vide ; disposition enregistrée vide
  → reste vide ; migration : une disposition vide d'avant compte comme non
  personnalisée, une fois ; `gdtfShare` enregistré devient `gdtfMapping`, une
  fois ;
- instantané des `rawId` de tous les `ToolId` (aucun renommé) ;
- chaque outil des deux catalogues a une autre place (`ToolId.home` contre
  `MainMenuModel` et `GeoPanelContent`) ;
- textes : clés §7 présentes en fr et en ; pluriels one / other ; plus de
  « (s) » ni de « Fixture » dans les clés touchées ; clés supprimées absentes.

Contrôle à l'écran :
- [ ] tablette simulée (`adb shell wm size 1600x2560` + `wm density 320`,
      puis `wm size reset` / `wm density reset`) : barre gauche préremplie en
      3D et dans le plan, qui ne gêne ni la légende, ni les panneaux, ni la
      fiche, en portrait et en paysage ; vidée par « Tout remettre hors
      barres » → reste vide au relancement ;
- [ ] téléphone : aucune barre par défaut (Q1 = oui), dispositions
      personnalisées d'avant gardées ;
- [ ] TalkBack (ou `uiautomator dump`) : chaque bouton nommé, état coché ;
- [ ] pluriels « 1 vue », « 2 vues », « 1 projecteur sélectionné » (fr et en) ;
- [ ] rapport d'import MA et couleur personnalisée : « Terminé » ;
- [ ] outil `gdtfShare` ancré → Compte & réglages ; une disposition d'avant
      qui contenait `gdtfShare` montre « Modèles de projecteurs » et l'ouvre ;
      outils `importDXF` / `refPlanPanel` et `importModel` / `modelsPanel` à
      libellé fixe.

---

## 11. Hors périmètre (relevé en passant)

- « (s) » restant hors des surfaces du chantier : `cabling_assigned_fmt`,
  `cabling_clear_circuit_msg_fmt`, `dmx_assigned_fmt`,
  `dmx_clear_core_msg_fmt`, `gdtf_resolved_fmt`, `gdtf_type_status_fmt`,
  `home_members_version`, `legend_more_fmt`, `legend_more_layers_fmt`,
  `patch_count_hint`, `pdf_unknown_footprint_fmt`, `pdf_unknown_watts_fmt`
  (iOS les a traités dans ses « restes ») — à faire avec la tranche E si le
  temps le permet, sinon chantier vocabulaire.
- Dialogue « Renommer le distributeur » du Câblage (`CablingScreen.kt:736`,
  `DmxCablingScreen.kt:486`) : « OK » + « Fermer » au lieu de « Annuler » /
  « Enregistrer » (DESIGN_UI §8).
- Fonctions de Q3.
- Web : même portage, document séparé dans le dépôt `MVRViewerWeb`.
- Cadre de sélection : il **ajoute** à la sélection en 3D
  (`Scene3DScreen.kt:2146`) mais la **remplace** dans le plan
  (`PlanScreen.kt:1312`). Aligné en tranche C (Q2 tranchée, §9.0 ; règle iOS : le
  cadre force le multi, donc ajoute).
- Les sections partagées `labelSides` et `fixtureOrientations` sont reçues
  mais pas appliquées (`SceneScreen.kt:545`) : un Pan / Tilt 180° fait sur
  iOS ne se voit pas sur Android. Même chantier que Q3.

---

## 12. Relecture du plan (2026-10-15) — corrections apportées

Critique en lecture seule du commit `88610b8`, re-parcours du code Android
(`SceneScreen`, `Scene3DScreen`, `PlanScreen`, `SceneOptions`,
`ToolbarLayout`, `AnchoredToolbars`, `HomeScreen`, `SyncUI`,
`GdtfShareScreen`, `MaLibraryScreen`, `CablingScreen`, `DmxUniverseScreen`,
`PatchListScreen`, `ModelPlacementPanel`, `MainActivity`). Corrigé dans ce
document :

1. **Options oubliées par l'inventaire** : outil `gdtfShare` « GDTF Share »
   (`Scene3DScreen.kt:1938`) sans devenir (A26, §4.6) ; titre « Modèles 3D
   (%d) » du panneau Modèles importés (`ModelPlacementPanel.kt:84`) dont la
   clé était promise à la suppression (A28, §7.3) ; « Retour 3D » aussi sur
   GDTF Share et la Bibliothèque MA (y compris depuis l'accueil) (§2.6,
   tranche A) ; « ↳ bloc séparé » et « Masquer si trop dézoomé » du ⋯ 3D,
   sans effet en 3D (A27) ; pastille de synchro visible au-dessus de **tous**
   les modes et bannière « nouvelle version » pleine largeur sur la barre
   (§2.1, §3.1, §3.5, D21) ; bouton « Déconnexion » (A30) ; `colorMode` et
   `search` sans `ToolSpec`, comme `cameraPresets` (§2.5, A23) — d'où la
   pile bas-droite : Rechercher n'est jamais masqué (§3.4).
2. **Hissage du plan importé incomplet** : sans la persistance débouncée du
   placement (`PlanScreen.kt:819-834`), le centrage sur les données du plan
   et `onSetReferencePlan`, un réglage fait dans le panneau 3D n'était ni
   enregistré ni partagé (§3.7, A29, tranche B).
3. **Tranche B non sûre** : les panneaux 3D (Position GPS, Plan importé,
   étiquettes) et l'import depuis la 3D n'avaient aucune entrée avant le
   menu de la tranche C (le contrôle « import depuis la 3D » était
   infaisable). Entrées provisoires de l'ancien ⋯ ajoutées (tranche B, 5) ;
   l'outil DXF garde ses deux états jusqu'à E.
4. **`ToolId` créés trop tard** : `geoPanel` (testé en B) et `multiSelect`
   (bouton masqué quand ancré, en C) étaient listés en E. Chaque `ToolId`
   naît dans sa tranche (§4.6).
5. **Manque transitoire C → D** dit explicitement : « Vider la sélection »
   n'a plus de bouton entre la tranche C et la fiche de la tranche D (toucher
   le vide désélectionne) (A1, A11, tranche C).
6. **Cadrage sous la pastille** (défaut corrigé sur iOS dans ses restes) :
   `fitAbove` ne réserve que le bas ; la pastille centrée du plan cacherait la
   rangée de projecteurs du haut pendant une affectation (§3.3, tranche C).
7. **Sous-volets** : une destination unique ne permettait pas de revenir de
   la Bibliothèque MA vers Modèles de projecteurs ; les écrans ouverts depuis
   une destination sont des sous-volets internes (§3.10, §4.1).
8. **Actions destructives au toucher d'une ligne** (« toucher → se
   déconnecter » de GDTF Share) remplacées par un bouton nommé (§1.2, §3.10,
   §3.11) ; type touché déconnecté → connexion puis recherche (plus de ligne
   inerte).
9. **Compte & réglages depuis l'accueil** : hôte dans `App` et crochet
   `MVR_AUTO_HOME_DEST` (§3.11, §4.7).
10. **Décisions** : forme de « Terminé » dans le plan (D19), « Afficher ma
    position » désactivé en 3D sans calibration (D20), « Looks right » (D22),
    « Isoler » grisé en 3D (D23), sens de `gdtfShare` (D24) écrits comme
    divergences ; barre flottante et tablette tranchées ensemble (Q1, §3.13).
11. Divers : clés iOS des `PanelID` (`labels3D`, `referencePlan3D`,
    `geoPosition3D`, `geoPositionPlan`) et nouvelles clés Android nommées ;
    `sync_sign_out` → « Se déconnecter » ; titres d'outils `refPlanPanel`,
    `modelsPanel`, `gdtfMapping`, `cabling` ; `hasSync` dans
    `MainMenuContext` ; retour système dans les pages du ⋯ à vérifier ;
    `SyncViewModel.kt:291` (et non `:292`).
