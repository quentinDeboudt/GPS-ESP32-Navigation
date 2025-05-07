# ESP32 Véhicule Navigation

## <span style="color:rgb(0, 166, 255)">Description de l’idée:</span>

Le projet `ESP32 Véhicule Navigation` consiste à afficher des informations de **navigation GPS** sur un petit écran. L'objectif est de permettre aux conducteurs (voiture, moto, vélo, trottinette, etc.) de suivre leur itinéraire sans avoir à regarder leur téléphone. L'interface sera **simple** et **compacte**. Les informations affichées pourraient inclure :

- **L'itinéraire** à suivre (par exemple : "Dans 500 mètres, tourner à droite").
- Le **temps restant** avant d'arriver à destination.
- L'heure d'**arrivée estimée**.


## <span style="color:rgb(0, 166, 255)">Pistes de Recherche:</span>

### 1. Intégration avec des applications de navigation existantes
Une application doit être développée pour **récupérer** les données provenant d'applications de navigation populaires, telles que `Google Maps`ou `Waze`. Cette application aura pour rôle **d'écouter** les notifications envoyées par ces applications de navigation et de transmettre les informations pertinentes à l'ESP32.

### 2. Développement d'une application de navigation personnalisée
Une autre option serait de développer une application de navigation sur mesure, en utilisant une **API de navigation**. Cette application enverrait directement **ses propres données de navigation** à l'ESP32 pour un contrôle total sur les informations affichées.

### 3. Possibilité d'interaction avec d'autres applications de navigation
Il n'est pas exclu que certaines applications de navigation, bien que **moins connues**, puissent déjà envoyer leurs données via **Bluetooth** ou d'autres technologies sans fil. L'ESP32 pourrait alors récupérer ces informations sans nécessiter de développement supplémentaire.


## <span style="color:rgb(0, 166, 255)">Cible Utilisateur:</span>
Les utilisateurs potentiels de ce projet incluent tous les conducteurs, qu'ils soient en **voiture**, **moto**, **vélo**, **trottinette**, ou tout autre moyen de transport. Ce système est conçu pour être universel et pratique, facilitant la navigation tout en permettant de garder les yeux sur la route.
