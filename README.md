# Bilancio calorico

App Android personale di Carlo: calorie assunte (Yazio), bruciate (riposo Garmin + ScanWatch Withings + corsa Fenix) e differenza, lette da athletedata ogni ora, con widget per la schermata Home.

- Ogni push su `main` compila l'APK firmato e lo pubblica nelle *Releases*.
- La chiave athletedata si inserisce nelle impostazioni dell'app e resta solo sul telefono.
- `app/personal.keystore` è la chiave di firma dell'app: serve perché gli aggiornamenti si installino sopra la versione precedente. Tenere il repository privato.
