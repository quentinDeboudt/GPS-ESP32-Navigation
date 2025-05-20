# Solution envisagée ?

## Développement d'une Application de navigation GPS personnalisée (solution retenue)

Création d'un application Android (joue le rôle de planificateur d’itinéraire) ainssi que l'uttilisation
d'une carte ESP32, munie d’un écran rectangulaire intégré (assure le guidage en temps réel).

L'**application de navigation GPS** Android permet:

- [x] Saisie de la destination finale et génération d’un tracé vectoriel (via **GraphHopper**).
- [x] Clic sur **“Démarrer la navigation”** pour lancer le guidage.
- [x] Récupération en temps réel de la position GPS de l’utilisateur.
- [x] Affichage des instructions au fur et à mesure (ex. : “dans 100 m, tourner à droite” + flèches directionnelles…).
- [ ] Recalcul dynamique d’itinéraire et mise à jour en temps réel côté ESP-32
- [ ] **Affichage du nombre total de kilomètres à parcourir**
- [ ] **Estimation du temps de trajet**
- [ ] **Estimation de l’heure d’arrivée (ETA)**

- [ ] Pré-envoi des **tuiles cartographiques** et du **tracé vectoriel** complet vers l’ESP-32 **avant le démarrage**
- [ ] Gestion du protocole **BLE** (Bluetooth Low Energy) sur l’ESP-32 pour réception des données
- [ ] Adaptation de l’affichage sur l'écran ESP-32 (flèches + distance + carte)
- [ ] **Enregistrement d’itinéraires favoris**

### Connexion à l’ESP-32:

Deux approches sont envisagées:

1. **Pré-envoi des données avant démarrage**
    - **Tuiles cartographiques** (OpenMapService) et **tracé vectoriel complet** sont téléchargés et stockés côté ESP-32 avant de lancer la nav’.
    - Pendant la navigation, on n’envoie plus que la **position actuelle** et l’**instruction à exécuter** (flèche + distance).
   
    - 👉 Avantages: minimise le trafic BLE en cours de route, fiabilité accrue en cas de coupure + basse consommation.
    - 🔄 Inconvénient: latence au démarrage pour charger tout le tracé.

2. **Envoi “live” au fur et à mesure**
    - On pousse à l’ESP-32 l’**ensemble des données** (tuiles, segments du tracé et instructions) **en temps réel**, à mesure qu’elles deviennent nécessaires.
   
    - 👉 Avantages: démarrage instantané, possibilité d’ajuster le tracé “à la volée” (recalcul d’itinéraire en cas de détours).
    - 🔄 Inconvénient: nécessite une connexion BLE stable tout au long de la navigation + consommation plus importante.