# Activer la synchronisation Firebase — Android (comptes + projets partagés)

L'app Android fonctionne **dès maintenant** sur un backend **local de démo**
(aucune synchro entre appareils — juste pour tester l'UI dans un seul APK). Pour
la **vraie** synchronisation, il faut brancher **le MÊME projet Firebase que
l'iOS** (`mvrviewermulti`) : ainsi un projet publié depuis un iPhone est
**rejoignable et modifiable depuis Android**, et inversement. À faire **une
seule fois** (~10 min). Tant que ce n'est pas fait, rien ne casse : l'app reste
sur le backend local (`BackendSelector` → `LocalSyncService`).

> Backend PARTAGÉ iOS ↔ Android : mêmes collections Firestore, mêmes noms de
> champs, même enveloppe de section, mêmes blobs Storage. Le plan de repère DXF
> transite dans le **format d'échange iOS (`DXP1`, little-endian)** via
> `RefPlanInterop` (le format binaire LOCAL Android `DXP2` n'est pas envoyé).

## 1. Ajouter l'app Android au projet Firebase existant

1. https://console.firebase.google.com → ouvrir le projet **`mvrviewermulti`**
   (le même que l'iOS — **ne pas en créer un nouveau**).
2. ⚙️ *Paramètres du projet* → **Ajouter une application** → **Android**.
3. **Nom du package** : `com.minou.mvrviewer` (identique à `applicationId`).
4. (Surnom / SHA-1 facultatifs — inutiles pour Auth e-mail/Firestore/Storage.)
5. Télécharger **`google-services.json`**.

L'authentification e-mail, Firestore et Storage sont **déjà activés** (partagés
avec l'iOS) et les **règles sont déjà déployées** — rien à refaire côté console.

## 2. Déposer le fichier + activer le plugin

1. Copier **`google-services.json`** dans **`app/`** (à côté de `app/build.gradle.kts`).
   Il contient des identifiants publics d'app (pas un secret serveur) mais
   évitez de le committer (à ajouter au `.gitignore`).
2. **Décommenter** la ligne du plugin dans **`app/build.gradle.kts`** :
   ```kotlin
   plugins {
       …
       alias(libs.plugins.google.services)   // ← retirer les //
   }
   ```
   (Le plugin est déjà déclaré au niveau racine et dans le version catalog ; les
   dépendances Firebase — auth/firestore/storage + coroutines-play-services —
   sont déjà présentes.)
3. Resynchroniser Gradle / rebuild.

C'est tout côté code : `FirebaseSyncService.kt` est déjà écrit. Sans
`google-services.json`, aucun `FirebaseApp` par défaut n'est initialisé →
`BackendSelector` reste sur le backend local. Dès que le fichier est présent +
le plugin appliqué, `FirebaseApp.getApps()` n'est plus vide → l'app bascule
**automatiquement** sur Firebase.

## 3. Vérifier la parité cross-platform

1. **iOS** : ouvrir un `.mvr`, se connecter, *Partager ce projet* → noter le code.
2. **Android** (build avec `google-services.json`) : ouvrir **le même** `.mvr`
   (même contenu → même empreinte de réunion), se connecter avec un **autre**
   compte, menu ☁ → *Rejoindre un projet* → saisir le code.
3. Sur iOS, changer l'adresse DMX d'un projecteur → l'Android reçoit le patch en
   quasi temps réel ; menu ☁ → *Historique* montre **qui** a changé **quoi**
   (adresse avant → après). Inversement Android → iOS.
4. Le **fichier `.mvr` d'origine n'est jamais modifié** (comme sur iOS) : seul
   l'état (patch, calibration, placement du plan, journal) transite par le cloud.

## Notes

- **Compte** : e-mail + mot de passe. Session persistée par le SDK FirebaseAuth
  (reconnexion silencieuse). Le stub local, lui, persiste via `SyncCredentialStore`
  (AES/GCM Android Keystore, alias distinct de GDTF Share).
- **Divergences de modèle gérées** par `LocalMapper` : `refTransform`↔`refPlanTransform`,
  adresse unique↔`addresses[]`, ancres GPS, etc. Seuls les projecteurs à `uuid`
  non nul se synchronisent (clé stable cross-platform).
- **v1** : couleurs de calque / côtés d'étiquette / retournements / mappings GDTF
  sont *reçus* mais pas appliqués côté Android (pas d'éditeur dédié encore) — le
  patch, la calibration et le placement du plan sont, eux, entièrement partagés.

## Bibliothèque de puissances (`powerLibrary`) — outil de câblage

Collection Firestore **RACINE** `powerLibrary`, **GLOBALE** (hors projet) : un
document par TYPE de projecteur, id = spec GDTF normalisée
(`powerLibraryDocId` : minuscules, trim, `/ . # $ [ ]` → `_`). Champs partagés
iOS/Android : `spec` (String d'origine), `watts` (Int, puissance max en W),
`updatedBy` (uid ou ""), `updatedAt` (Number, epoch **millisecondes**). Fusion
dernier écrivain gagne sur `updatedAt`.

**Règle Firestore** (à ajouter côté iOS dans `firestore.rules` ; **rien à écrire
côté Android**, mais la MÊME règle s'applique — c'est une base communautaire) :

```
match /powerLibrary/{docId} {
  allow read, write: if request.auth != null; // authentifiés uniquement
}
```

Sans compte / hors ligne, la résolution fonctionne quand même : un **cache
disque global** (`powerLibrary.json`) mémorise les saisies et sert de repli ;
le cloud n'est fusionné (LWW) que lorsqu'on est connecté. En backend LOCAL
(sans `google-services.json`), le stub reproduit la collection sous
`CloudSim/powerLibrary/` pour rester testable.
