# CarLink Assets Directory

## Vosk Speech Model (required for "GUNNU" wake word)

Place the Vosk small English model zip here as:

    app/src/main/assets/vosk-model-small-en-us.zip

### How to download it (FREE, no signup):

1. Go to: https://alphacephei.com/vosk/models
2. Download: **vosk-model-small-en-us-0.15** (~40 MB)
   Direct link: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
3. Rename the file to: `vosk-model-small-en-us.zip`
4. Copy it into this directory (`app/src/main/assets/`)
5. Rebuild the app: `./gradlew assembleDebug`

The app extracts the model to device storage on first launch automatically.

### Why Vosk?
- ✅ 100% offline — no internet needed for wake word detection
- ✅ No account, no signup, no email required
- ✅ Free and open source (Apache 2.0 license)
- ✅ Runs well on low-power Android devices like Ambrane CarLink Stream

---

## Enabling Ad-Skipping on Ambrane (No Accessibility Settings UI)

Connect your Ambrane device to PC via USB with Developer Options enabled, then run:

```bash
adb shell settings put secure enabled_accessibility_services carlink.com/carlink.com.service.CarLinkAccessibilityService

adb shell settings put secure accessibility_enabled 1
```

Run these **once** — the setting persists across reboots.

### Enabling Developer Options on Ambrane:
1. Settings → About device
2. Tap **Build number** 7 times rapidly
3. Settings → Developer Options → enable **USB Debugging**
4. Connect USB to PC → accept the pairing prompt on device
