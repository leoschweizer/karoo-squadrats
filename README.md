# Squadrats Overlay for Karoo

A Hammerhead Karoo extension that displays uncollected Squadrats as colored grid outlines on the map during your rides.

This project is an independent, unofficial community project. It is not affiliated with, endorsed by, or associated with [Hammerhead](https://www.hammerhead.io/) or [Squadrats](https://squadrats.com/) in any way.

## Setup

### 1. Install the APK

Releases are available via [GitHub Releases](https://github.com/leoschweizer/karoo-squadrats/releases/latest).

- Karoo (latest generation): Share the release link above to the Karoo Companion App — see [Companion App - Sideloading](https://support.hammerhead.io/hc/en-us/articles/31576497036827-Companion-App-Sideloading)
- Karoo 2: Download the APK and sideload via `adb install app-release.apk` - see [DC Rainmakers tutorial](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html) for instructions

### 2. Get your Squadrats token and timestamp

Squadrats doesn't have a public API, but serves your data as vector map tiles. You need to extract two short values from your personal tile URL:

1. Open [squadrats.com](https://squadrats.com/) in a desktop browser and navigate to your map view so your tiles are visible
2. Open **Developer Tools** (press `F12` or `Cmd+Option+I` on Mac)
3. Go to the **Network** tab
4. In the filter box, type `.pbf`
5. Reload the page - you should see tile requests appearing
6. Click on any of the `.pbf` requests and look at its URL. Note down the token and timestamp:
   ```
   https://tiles2.squadrats.com/abcDEF123xyz/trophies/1754233411401/12/2081/1367.pbf
                                ^^^^^^^^^^^^          ^^^^^^^^^^^^^
                                   TOKEN                TIMESTAMP
   ```

> **Note:** Your token is personal. Keep it private.

> **Note:** The timestamp may change over time. If sync stops working, repeat the steps above to get a fresh timestamp and update it in the app. The token typically stays the same.

### 3. Configure the extension on your Karoo

1. Open the **Squadrats Overlay** app on your Karoo
2. Enter your **Token** and **Timestamp**
3. Set your **sync center** - either tap "Use Current Location" for GPS, or manually enter the latitude/longitude of the area you ride in
4. Set the **sync radius** - this determines how large an area of tile data to download
5. Tap **Sync Data** - the Karoo will download tile data over WiFi.

### 4. Go for a ride

1. Once synced, uncollected Squadrats appear as purple rectangle outlines on map pages
2. As you ride into a Squadrat and collect it, it will still show until you re-sync (since data is pre-cached)
3. Re-sync data at home before each ride (while on WiFi). Previously collected squares will no longer appear as outlines.

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
