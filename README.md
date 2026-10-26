# VOID Music — 0.4.0

Application Android native légère en Kotlin et Android Views, avec navigation Accueil, Recherche, Bibliothèque et Paramètres.

## Catalogues intégrés

- Deezer : recherche publique de titres, sans jeton.
- Audius : recherche publique de pistes par son API REST en lecture seule ; aucun identifiant n’est nécessaire pour les requêtes de lecture.
- SoundCloud : recherche de pistes avec jeton d’application OAuth Client Credentials. L’utilisateur doit fournir le Client ID et le Client Secret de sa propre application enregistrée dans Paramètres. Aucun identifiant n’est inclus dans le dépôt.
- Le sélecteur Recherche permet de choisir une source ou de lancer une recherche sur toutes les sources.
- Les résultats affichent titre, artiste, source, durée et pochette lorsque disponible. Les liens ouvrent la page officielle du morceau.
- Les requêtes réseau ont des timeouts et sont annulées lorsqu’une recherche est remplacée ou que l’écran est détruit.

## Identifiants SoundCloud

SoundCloud exige qu’une application soit enregistrée et que le Client ID et le Client Secret soient configurés. Le secret et les jetons d’accès/renouvellement sont chiffrés avec AES-GCM via Android Keystore sur cet appareil. Le jeton d’application est mis en cache et renouvelé à l’expiration. Les identifiants peuvent être effacés dans Paramètres.

Le chiffrement local protège les données au repos, mais ne peut pas empêcher l’extraction d’un secret sur un appareil compromis pendant que l’application l’utilise. Pour une application publique à grande échelle, un service serveur dédié est préférable. SoundCloud demande actuellement un abonnement Artist Pro pour enregistrer une application API.

Audius autorise les requêtes de lecture sans identifiants ; une clé API facultative augmente les limites. VOID Music n’en a pas besoin pour la recherche.

## Bibliothèque et export

- Favoris et playlists persistés localement ; les pistes de fournisseurs différents restent distinctes.
- Créer ou supprimer une playlist, ajouter un titre par appui long depuis Recherche, et le retirer par appui long dans une playlist.
- Cache mémoire des pochettes configurable de 1 à 16 Mo, avec commande de vidage.
- Export des playlists via le sélecteur de fichiers Android : CSV correctement échappé, JSON et README dans un ZIP. L’export contient uniquement des métadonnées, jamais d’audio.
- Les données locales sont stockées en JSON dans SharedPreferences.

## Lecture

L’application n’intègre pas encore de lecteur audio. Audius et SoundCloud proposent des pistes streamables via leurs API, mais les conditions d’accès varient selon le morceau. Cette version ouvre les liens officiels et ne contourne ni restrictions, ni authentification, ni DRM.

## Compiler et vérifier

Versions : Android Gradle Plugin 8.7.3, Gradle 8.9, Kotlin 2.1.20, Java 17. Dans un environnement avec Android SDK et JDK 17 :

    gradle --no-daemon testDebugUnitTest lint assembleDebug

Le workflow GitHub Actions exécute ces commandes sur chaque push vers main et sur lancement manuel. Il publie app/build/outputs/apk/debug/app-debug.apk comme artefact VOID-Music-APK. Ouvrir Actions, sélectionner Build VOID Music APK, puis télécharger l’artefact de l’exécution.

L’archive source d’origine ne contenait pas de Gradle Wrapper. Le workflow fixe Gradle 8.9 via gradle/actions/setup-gradle.

## Confidentialité

Seule la permission Internet est déclarée. Les recherches sont envoyées aux fournisseurs choisis. Favoris/playlists, identifiants SoundCloud chiffrés et jetons restent sur l’appareil. Aucun identifiant ni secret n’est intégré au code.
