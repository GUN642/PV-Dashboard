# PV Dashboard

Android-App für die private Auswertung einer **SENEC.Home V3 hybrid** PV-Anlage mit Speicher und **SENEC Wallbox**.

## Installation

1. Auf dem Handy die Seite **[Releases](https://github.com/GUN642/PV-Dashboard/releases)** öffnen.
2. Bei der neuesten Version `PV-Dashboard.apk` herunterladen und öffnen.
3. Beim ersten Mal fragt Android, ob Apps aus dieser Quelle (z. B. Chrome) installiert werden dürfen → erlauben.
4. App öffnen, in den Einstellungen die **IP-Adresse des Speichers** eintragen (steht im Router, z. B. FRITZ!Box → Heimnetz).

Updates werden genauso installiert – einfach die neue APK über die alte installieren, die Einstellungen bleiben erhalten.

## Was die App kann (Version 0.1)

- Live-Werte direkt vom Speicher im Heim-WLAN (lokale Schnittstelle `lala.cgi`, kein Cloud-Login nötig):
  PV-Erzeugung, Hausverbrauch, Netzbezug/Einspeisung, Speicher-Leistung und Ladestand, Akku-Temperatur, Wallbox-Ladeleistung und ob ein Auto angesteckt ist
- Autarkie und Eigenverbrauchsquote in Echtzeit
- Verlaufsdiagramm der letzten 30 Minuten (solange die App geöffnet ist)
- Rohdaten-Ansicht mit Teilen-Funktion zur Fehlersuche

Die Werte werden nur abgefragt, solange die App im Vordergrund ist.

## Automatischer Build

Bei jedem Push baut GitHub Actions (`.github/workflows/android.yml`) die APK, führt die Tests aus und veröffentlicht sie als Release:

- Builds von `main` → reguläres Release („Latest“)
- Builds anderer Branches → Vorabversion (Pre-release)

Die Versionsnummer ist `0.1.<Build-Nummer>`, dadurch lässt sich jede neue APK als Update installieren.

### Signaturschlüssel

Damit Updates über die alte Version installiert werden können, muss jede APK mit demselben Schlüssel signiert sein.
Standardmäßig wird dafür `app/signing/pv-dashboard.jks` aus dem Repository verwendet (Passwort `pvdashboard`).
Da das Repository öffentlich ist, kann man optional einen eigenen, geheimen Schlüssel hinterlegen
(Settings → Secrets and variables → Actions):

| Secret | Inhalt |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | Keystore-Datei, base64-kodiert |
| `SIGNING_STORE_PASSWORD` | Keystore-Passwort |
| `SIGNING_KEY_ALIAS` | Schlüssel-Alias |
| `SIGNING_KEY_PASSWORD` | Schlüssel-Passwort |

Achtung: Nach einem Schlüsselwechsel muss die App einmal deinstalliert und neu installiert werden.

## Technik

Kotlin, Jetpack Compose (Material 3), minSdk 26 (Android 8), keine Fremdbibliotheken für den Datenabruf.
