# Squadrats Overlay for Karoo

A Hammerhead Karoo extension that displays uncollected [Squadrats](https://squadrats.com/) as colored grid outlines on the map during your rides.

This project is an independent, unofficial community project. It is not affiliated with, endorsed by, or associated with [Hammerhead](https://www.hammerhead.io/) (makers of the Karoo) or [Squadrats](https://squadrats.com/) in any way.

## Setup

### Prerequisites

- A [Squadrats](https://squadrats.com/) account with activities synced from Strava
- A Hammerhead Karoo 2 or Karoo 3
- WiFi connection for the initial data sync

### 1. Install the APK

Build the project and sideload the APK onto your Karoo:

```bash
./gradlew app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Get your Squadrats token and timestamp

Squadrats doesn't have a public API, but serves your data as vector map tiles. You need to extract two short values from your personal tile URL:

1. Open [squadrats.com](https://squadrats.com/) in a desktop browser
2. Navigate to your map view so your tiles are visible
3. Open **Developer Tools** (press `F12` or `Cmd+Option+I` on Mac)
4. Go to the **Network** tab
5. In the filter box, type **`.pbf`**
6. Reload the page (`Cmd+R` / `F5`) — you should see tile requests appearing
7. Click on any of the `.pbf` requests and look at its URL. It will look like:
   ```
   https://tiles2.squadrats.com/abcDEF123xyz/trophies/1754233411401/12/2081/1367.pbf
                                ^^^^^^^^^^^^          ^^^^^^^^^^^^^
                                   TOKEN                TIMESTAMP
   ```
8. Note down the two values:
   - **Token** — the string right after `tiles2.squadrats.com/` (e.g. `abcDEF123xyz`)
   - **Timestamp** — the number right after `trophies/` (e.g. `1754233411401`)

> **Note:** Your token is personal. Keep it private.

### 3. Configure the extension on your Karoo

1. Open the **Squadrats Overlay** app on your Karoo
2. Enter your **Token** and **Timestamp**
3. Set your **sync center** — either tap "Use Current Location" for GPS, or manually enter the latitude/longitude of the area you ride in
4. Set the **sync radius** (default: 30 km) — this determines how large an area of tile data to download
5. Tap **Sync Data** — the Karoo will download tile data over WiFi.

### 4. Go for a ride

1. Once synced, uncollected Squadrats appear as purple rectangle outlines on map pages.
2. As you ride into a Squadrat and collect it, it will still show until you re-sync (since data is pre-cached).
3. Re-sync data at home before each ride (while on WiFi). Previously collected squares will no longer appear as outlines.

### Token/timestamp expiry

The Squadrats timestamp may change over time. If sync stops working, repeat the steps above to get a fresh timestamp and update it in the app. The token typically stays the same.

## Building from Source

### Requirements

- Android Studio (with bundled JDK)
- A GitHub account with a Personal Access Token that has `read:packages` scope

Add your credentials to `local.properties` (this file is gitignored):

```properties
sdk.dir=/Users/you/Library/Android/sdk
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PAT
```

### Build

```bash
./gradlew app:assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.
