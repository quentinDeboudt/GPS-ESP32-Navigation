# Existe-t-il des projets équivalents ?

## 1. Projet: ESP32 + Sygic

Ce projet utilise une application mobile pour envoyer les instructions de navigation à l'ESP32 via **Bluetooth Low Energy (BLE)**.

- **Le téléphone** joue le rôle de **BLE Central** (aussi appelé *client* ou *maître*).
- **L'ESP32** agit comme un **périphérique BLE** (aussi appelé *serveur* ou *esclave*).

🔗 **GitHub du projet:** [BLE-HUD-navigation-ESP32](https://github.com/alexanderlavrushko/BLE-HUD-navigation-ESP32/blob/main/README.md)

🎥 **Vidéo de démonstration:** [YouTube - Chronos ESP32](https://www.youtube.com/watch?v=LHUQlj09ZNA&ab_channel=Sygica.s.)

>⚠️ <span style="color:rgb(255, 0, 0)">**Limite actuelle:** </span>
>Ce projet n’est compatible qu’avec l’application **Sygic GPS Navigation & Maps**, disponible uniquement sur **iOS**.

---

## 2. Projet: ESP32 (Montre) + Chronos App

Ce projet repose sur une **montre ESP32** qui communique avec l’application **Chronos App** via **BLE**. L’application permet de contrôler plusieurs fonctions de la montre.

### Fonctionnalités principales:

- Synchronisation de **l’heure**
- Réception de **notifications** et alertes d’**appel**
- Synchronisation de la **météo**
- Transmission de liens QR et de **contacts**
- Contrôle de la **musique**, de l’appareil **photo**, et fonction **"recherche du téléphone"**
- **Envoi des instructions de navigation**

🔗 **GitHub du projet:** [Chronos ESP32](https://github.com/fbiego/esp32-c3-mini)  
🎥 **Vidéo de démonstration:** [YouTube - Chronos ESP32](https://www.youtube.com/watch?v=qGODX6ALO_U&t=9s&ab_channel=fbiego)

>⚠️ <span style="color:rgb(255, 0, 0)">**Remarque importante:** </span>
>Ce projet offre un grand nombre de fonctionnalités, mais cela peut entraîner une **surcharge du système**, affectant les **performances** et réduisant considérablement **l'autonomie de la batterie**.

---

## 3. Projet: ESP32 (ttgo t display) + CarPlayBLE project

Ce projet est divisé en deux programmes: 
- l'**application** qui est écrite en Android/Java.
- l'**ESP32** qui utilise Arduino/C.
Ces deux **programmes** utilisent le **BLE** pour communiquer: l'ESP32 est configuré comme serveur GATT et l'application Android comme client.

### Fonctionnalités:
- Heure d'**arrivée estimée**
- Il reste quelques minutes avant d'arriver
- **Distance restante** avant d'arriver
- **Destination**
- **Rue/lieu** où vous vous trouvez actuellement
- N/A: **distance avant** de **changer de direction/distance** avant de devoir exécuter l'instruction qui vous est donnée (tourner à gauche, tourner à droite, etc.), N/A signifie qu'il n'y a pas encore de données et qu'il faut continuer.

🔗 **GitHub du projet:** [CarPlayBle ESP32](https://github.com/appleshaman/CarPlayBLE)  
🎥 **Vidéo de démonstration:** [Image - ESP32](https://github.com/appleshaman/CarPlayBLE/blob/main/docs/5.jpg)

