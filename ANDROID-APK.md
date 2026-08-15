# Building an Android APK with PWABuilder

No Android Studio needed. Everything the packager requires is already in this project
(manifest, icons, maskable icon, screenshots, service worker).

## 1. Publish
Publish the app so it is live at your public URL (e.g. https://jwgardencrm.lovable.app).

## 2. Package
1. Go to https://www.pwabuilder.com
2. Paste your published URL and press **Start**.
3. On the report card, press **Package for stores** → **Android**.
4. Options:
   - Package ID: `app.lovable.jwgardencrm` (or your own reverse-domain name)
   - App name: `JW Garden Services CRM`
   - Signing key: **Create new** (download and keep `signing.keystore` + the password safe —
     you need the same key for every future update).
5. Download the ZIP. It contains:
   - `app-release-signed.apk` — install this directly on a phone
   - `app-release-bundle.aab` — only needed for Google Play
   - `assetlinks.json` — see step 3
   - `signing-key-info.txt` — keep private

## 3. Remove the browser address bar (Digital Asset Links)
Copy the contents of the downloaded `assetlinks.json` into this project at:

```
public/.well-known/assetlinks.json
```

Then publish again. Without this the app still works, but shows a URL bar at the top.

## 4. Install on your phone
Transfer the `.apk` to the phone, open it, and allow "Install unknown apps" for your
file manager when prompted.

## Notes
- The APK is a thin wrapper around the live site, so updates you publish appear
  automatically — no reinstall needed.
- Offline mode and installed-app behaviour come from the same service worker the PWA uses.
