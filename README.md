# Siwel — appels de groupe audio/vidéo en WebRTC pour Android

Application Android en **Kotlin** pour passer des appels de groupe audio et vidéo entre les membres d'un petit cercle privé (*siwel* : appeler, en kabyle). Projet personnel de 2026, qui fonctionne entre téléphones sur des réseaux différents (4G, Wi-Fi, pays différents).

## Architecture

```
Téléphone A ──┐        Firebase Realtime Database (signalisation : offres/réponses SDP, ICE, statut d'appel)
Téléphone B ──┼──►     ▲
Téléphone C ──┘        │ child_added
                       │
                 Démon Node.js (server/siwel-push) ──► Firebase Cloud Messaging ──► notification d'appel entrant

Média : WebRTC en maillage (une PeerConnection par paire de membres)
        STUN public + TURN coturn auto-hébergé pour traverser les NAT symétriques / CGNAT
```

- **Signalisation** : chaque appel est un nœud `siwel/calls/{callId}` (initiateur, membres, statut, connexions). Pour chaque paire de membres, l'ordre alphabétique des noms désigne celui qui émet l'offre SDP, ce qui évite les offres croisées. Les candidats ICE transitent par la base.
- **Appel entrant** : le démon `server/siwel-push` (Node.js + firebase-admin, service systemd) détecte les nouveaux appels et envoie une push FCM haute priorité. Le téléphone démarre alors un service éphémère (`CallListenerService`) qui affiche une notification plein écran, fait sonner et gère un délai de 45 s ou le refus. Aucun service ne tourne en permanence, pour économiser la batterie.
- **Appel** : `CallViewModel` gère le maillage de pairs, le niveau audio de chaque participant (statistiques WebRTC), la sortie haut-parleur, la tonalité de retour d'appel et le nettoyage des appels orphelins.
- **Session** : connexion par membre avec une session chiffrée (`EncryptedSharedPreferences`), présence en ligne et exemption d'optimisation batterie pour fiabiliser la réception des push.

## Configuration

1. Créer un projet Firebase (Realtime Database + Cloud Messaging), enregistrer l'application Android `com.siwel.siwel` et placer `google-services.json` dans `app/`. Ce fichier est exclu du dépôt.
2. Installer un serveur TURN, par exemple [coturn](https://github.com/coturn/coturn) avec `lt-cred-mech`.
3. Copier `local.properties.example` en `local.properties` et renseigner le SDK Android, le serveur TURN et l'URL de la base. Ces valeurs sont injectées dans `BuildConfig` à la compilation.
4. Adapter la liste des membres dans `app/src/main/assets/members.json` (comptes de démonstration par défaut).
5. Démon de notifications :

```bash
cd server/siwel-push
npm install
SIWEL_SERVICE_ACCOUNT=./serviceAccountKey.json \
SIWEL_DATABASE_URL=https://<projet>-default-rtdb.<region>.firebasedatabase.app \
node index.js          # ou via siwel-push.service (systemd)
```

Compilation : `./gradlew assembleDebug` (JDK 17+, Android SDK 34).

## Sécurité — à savoir

- Les membres et leurs mots de passe sont embarqués dans l'application (`members.json`). Ce choix simple convient à un cercle privé, pas à une diffusion publique : Firebase Authentication serait la solution propre.
- Aucun identifiant réel n'est versionné : TURN, Firebase et compte de service passent par `local.properties`, `google-services.json` ou des variables d'environnement.

## Stack

Kotlin · Android (ViewModel, coroutines/Flow, View Binding) · WebRTC (`stream-webrtc-android`) · Firebase Realtime Database & Cloud Messaging · Node.js (firebase-admin) · coturn

## Licence

Code distribué sous [licence MIT](LICENSE).

## Auteur

**Amar Merabti** — 2026.
