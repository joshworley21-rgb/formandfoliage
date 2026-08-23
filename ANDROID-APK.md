# Building the Android app

The app is a real Android app built with [Capacitor](https://capacitorjs.com).
The whole CRM is bundled inside the APK and runs on the phone; it talks straight
to Supabase. There is no website in the middle, and nothing to keep hosted.

> This replaces the old PWABuilder approach, which wrapped the live Lovable site
> in a browser shell. That shell stopped working the moment the hosting went
> away — this does not.

## The easy way: let GitHub build it

No Android Studio, no SDK, nothing to install.

1. Push your changes.
2. On GitHub, open **Actions → Android APK → Run workflow**.
3. When it finishes, download **jw-garden-services-apk** from the run's
   Artifacts section.
4. Copy the `.apk` to the phone, open it, and allow "Install unknown apps" for
   your file manager when prompted.

### Signing it properly (worth doing once)

Without a signing key the workflow falls back to a debug APK. Those install
fine, but each build is signed with a different throwaway key, so you have to
uninstall the old app before installing a new one — which wipes nothing (your
data lives in Supabase) but is a nuisance.

Create a key once:

```sh
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias jwgarden
base64 -w0 release.jks   # copy the output
```

Then add four repository secrets under **Settings → Secrets and variables →
Actions**:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the base64 output above |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password you chose |
| `ANDROID_KEY_ALIAS` | `jwgarden` |
| `ANDROID_KEY_PASSWORD` | the key password you chose |

Keep `release.jks` and the passwords somewhere safe. Losing them means future
builds can no longer update an installed app.

## Building on your own machine

Needs Android Studio (for the SDK) and a JDK.

```sh
bun install
bun run app:sync    # builds the web assets and copies them into android/
bun run app:open    # opens the project in Android Studio
```

Then use **Build → Build Bundle(s) / APK(s) → Build APK(s)**, or from the
terminal:

```sh
cd android && ./gradlew assembleDebug
```

The APK lands in `android/app/build/outputs/apk/`.

## After changing the app

The APK contains a copy of the web assets, so a code change is not live until
you rebuild:

```sh
bun run app:sync
```

Then rebuild the APK (or re-run the workflow) and reinstall.

## Releasing a version

`versionCode` and `versionName` are in `android/app/build.gradle`. Bump
`versionCode` by one for every build you install over a previous one.

Pushing a tag beginning with `v` also triggers the workflow, so
`git tag v1.1 && git push --tags` builds that release.

## What still needs the internet

Everything is on the phone except the data itself, which lives in Supabase. The
app keeps working offline for reading and queues changes made while offline (see
`src/lib/offline-queue.ts`); invoice emails and new invoice numbers need a
connection, because those go through the Edge Functions.
