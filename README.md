# VOID Home Dashboard

Android-App für die private Auswertung einer **SENEC.Home V3 hybrid** mit Speicher und **SENEC Wallbox** – im Design von VOID Files.

## Installation

1. Auf dem Handy **[Releases → Latest](https://github.com/GUN642/PV-Dashboard/releases/latest)** öffnen und `VOID-Home-Dashboard-<Version>.apk` herunterladen.
2. Datei öffnen und installieren (beim ersten Mal „Apps aus dieser Quelle installieren“ erlauben).
3. Spätere Updates direkt in der App: **Einstellungen → Updates**.

## Funktionen

- **Live**: PV-Erzeugung, Hausverbrauch, Netzbezug/Einspeisung, Speicher, Ladestand, Wallbox, Autarkie, Eigenverbrauch, Tageswerte, 30-Minuten-Verlauf; Ringdiagramm, wohin der PV-Strom gerade fließt (Haus, Akku, Wallbox, Einspeisung)
  - im Heim-WLAN direkt vom Speicher (`lala.cgi`), unterwegs automatisch über die SENEC-Cloud
- **Statistik**: Tag, Monat, Jahr, Gesamt – Erzeugung, Verbrauch, Netz, Speicher, Wallbox als Diagramm und Tabelle
- **Kosten**: Stromkosten, Grundgebühr, Einspeisevergütung und Ersparnis durch PV nach eigenem Tarif
- **Wetter**: Sonnenstunden-Prognose für 7 Tage (Open-Meteo) und geschätzter PV-Ertrag nach Anlagenleistung, Neigung, Azimut und Systemverlusten
- **PVGIS-Referenz**: langjähriger Soll-Ertrag der EU (PVGIS) je Monat – automatisch geladen oder aus dem PVGIS-Bericht eingetragen; die Statistik zeigt Ist gegen Soll
- **Wallbox**: Lademodus (Schnell, Solar, Gesperrt), Speicher lädt mit, Ladeunterbrechungen verhindern, Mindestladestrom – über die SENEC-Cloud, mit Rückmeldung des tatsächlichen Zustands
- **Haus**: Strom- und Wasserzählerstände eintragen, Verbrauch je Monat (mit Vorjahresvergleich) und Jahr, Hochrechnung und Kosten nach Tarif; CSV-Import und -Export im Format der bisherigen Zähler-App; Warnung bei auffällig hohem Wasserverbrauch (mögliches Leck)
- **Finanzen** (Haus → Finanzen): Einnahmen und Ausgaben – monatlich, viertel-/halbjährlich, jährlich oder einmalig – mit Jahresbilanz, Sparquote, Diagramm je Monat (tatsächliche Zahlungstermine), Aufteilung nach Kategorien, Filter und Sortierung; Verträge mit Laufzeit, automatischer Verlängerung und Kündigungsfrist inkl. Erinnerung; **PDF-Bericht** (Überblick, Diagramm, Monatstabelle, Kategorien, alle Posten) zum Speichern oder Teilen
- **Benachrichtigungen**: „Akku voll – Überschuss nutzen“ (Prüfung im gewählten Intervall, nur tagsüber, max. einmal täglich) und Erinnerungen an Kündigungsfristen
- **Widget**: PV-Erzeugung und Autarkie groß, Aufteilung der PV-Erzeugung (Haus, Akku, Wallbox, Einspeisung), Netzbezug, Akku-Ladestand als farbige Punkte-Leiste; schwarz oder weiß mit einstellbarer Deckkraft; Aktualisierung per Tipp auf ⟳ (kein Hintergrund-Timer), Tipp auf die Werte öffnet die App
- **Design**: VOID-/Nothing-Stil mit Dot-Matrix-Schrift, sieben Hintergründen und Akzentfarben

## Einrichtung in der App

| Bereich | Wofür |
| --- | --- |
| Speicher im Heimnetz | IP-Adresse des SENEC.Home für schnelle Live-Werte zu Hause |
| SENEC-Konto | E-Mail und Passwort von mein-senec.de für unterwegs und für Statistiken. Das Passwort wird mit dem Android-Keystore verschlüsselt und nur auf dem Gerät gespeichert. |
| Stromtarif | Anbieter, Arbeitspreis, Grundgebühr, Einspeisevergütung |
| Wassertarif | Versorger, Frischwasser- und Abwasserpreis je m³, Grundgebühr |
| Standort & Anlage | Ort für das Wetter, kWp, Dachneigung, Azimut (180 = Süd, wie PVGIS) und Systemverluste für die Ertragsschätzung |
| PVGIS-Referenz | Soll-Werte je Monat für den Ist/Soll-Vergleich |

Der Cloud-Zugriff nutzt dieselbe Schnittstelle wie die offizielle SENEC-App (nach dem Vorbild der Home-Assistant-Integration [marq24/ha-senec-v3](https://github.com/marq24/ha-senec-v3)). Sie ist nicht offiziell dokumentiert und kann sich ändern. Zwei-Faktor-Anmeldung wird noch nicht unterstützt.

## Datensicherung

- **Sicherungsdatei** (Einstellungen → Datensicherung): Zählerstände, Finanzposten und alle Einstellungen als JSON-Datei an einen frei wählbaren Ort (Google Drive, Nextcloud, Download-Ordner …); „Wiederherstellen“ spielt sie auf einem neuen Handy wieder ein.
- **Android-Backup**: zusätzlich sichert Android die App-Daten ins Google-Konto bzw. überträgt sie beim Gerätewechsel.
- In beiden Fällen **ohne** SENEC-Passwort und Anmeldedaten (eigene Datei `secrets.xml`, ausgenommen). PV-Statistiken liegen in der SENEC-Cloud.

## Automatischer Build

`.github/workflows/android.yml` baut und testet bei jedem Push. Pushes auf `main` werden als Release mit Changelog aus den Commit-Nachrichten veröffentlicht; die Versionsnummer ist `0.2.<Build-Nummer>`.

### Signaturschlüssel

Damit Updates über die installierte Version passen, wird immer mit demselben Schlüssel signiert: standardmäßig `app/signing/pv-dashboard.jks` (Passwort `pvdashboard`). Da das Repository öffentlich ist, kann optional ein eigener Schlüssel über die Secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` und `SIGNING_KEY_PASSWORD` hinterlegt werden (danach einmal neu installieren).

## Lizenzen

Schriften Doto, Space Mono und Space Grotesk unter SIL Open Font License (`licenses/`). Wetterdaten von [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0). Referenzerträge von [PVGIS](https://re.jrc.ec.europa.eu/pvg_tools/) © Europäische Union.
