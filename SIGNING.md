# Signing release builds

No keys or passwords live in this repository. `app/build.gradle.kts` reads
the signing values at build time from, in order of precedence:

1. environment variables of the same name, then
2. the properties file named by `STORIES_ANDROID_SIGNING_PROPERTIES`, or
   by default `~/.local/android/release-keys/stories.properties`.

| Key                              | Meaning                    |
|----------------------------------|----------------------------|
| `STORIES_ANDROID_STORE_FILE`     | path to the keystore       |
| `STORIES_ANDROID_STORE_PASSWORD` | keystore password          |
| `STORIES_ANDROID_KEY_ALIAS`      | key alias (`stories`)      |
| `STORIES_ANDROID_KEY_PASSWORD`   | key password               |

Without them, `assembleRelease` signs with the local debug key.

## The maintainer's setup

The key is alias `stories` (RSA 3072, valid 10000 days) in the shared
`~/.local/android/release-keys/release.keystore`. Its certificate's SHA-256 is

    65:E3:F1:58:8D:83:3E:B1:23:AF:54:36:75:2E:E8:3F:DB:C6:1C:75:24:9B:5C:61:6A:31:A1:F8:80:21:48:78

which `apksigner verify --print-certs` must show for published APKs.
