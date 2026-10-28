# Voice Automation — starter project

An offline Android app: you speak a command, Whistle turns it into text, Needle 3
picks the tool and fills its arguments, and Kotlin code checks the call and runs it.
No internet permission. Built from the architecture doc.

## What's in this starter

| Part | Where | Notes |
| --- | --- | --- |
| JNI bridge to the Cactus engine | `app/src/main/cpp/needle_jni.c` | Load, init, transcribe, complete, reset |
| Engine in its own process | `engine/EngineService.kt` (`:engine`) | One thread; process is killed on idle to free memory |
| Engine client | `engine/EngineClient.kt` | Binds on demand, audio passed via shared memory, idle unload by phone tier |
| Phone tiers | `engine/DeviceTier.kt` | Low / mid / high by RAM; picks model file and idle timeout |
| Push-to-talk recorder | `voice/Recorder.kt` | 16 kHz mono, stops after ~0.7 s of silence, 15 s max |
| Spoken replies (optional) | `voice/Speaker.kt` | Android text-to-speech, offline voices only |
| 8 tools | `tools/` | open app, settings pages, torch, alarm, timer, volume, create note, delete note |
| Confirmation rules | `policy/Policy.kt` | Deletes always ask; low confidence or withheld calls ask |
| Screen | `ui/` | Mic button, typed fallback, confirm card, history with undo |

## Setup

You need Android Studio (with the NDK and CMake 3.22.1 installed from SDK Manager →
SDK Tools), Python 3.10+, and an arm64 Android phone with USB debugging on.

**1. Install the Cactus tools**

```sh
pip install cactus-needle
```

**2. Get the Android engine**

```sh
needle build --platform android-arm64 --out ./needle-android
```

Copy two files from `needle-android/` into the project:

```
libneedle.a  →  app/src/main/cpp/cactus/arm64-v8a/libneedle.a
needle.h     →  app/src/main/cpp/cactus/include/needle.h
```

**3. Add the models**

Put these in `app/src/main/assets/models/`:

| File | Where from | Needed for |
| --- | --- | --- |
| `needle3.cact` | `needle-android/` from step 2 | Everything (required) |
| `whistle.cact` | [huggingface.co/Cactus-Compute/whistle](https://huggingface.co/Cactus-Compute/whistle) (Files tab) | Voice input. Without it, only typed commands work |
| `needle3-low.cact` (optional) | `needle build --platform android-arm64 --layers 8 --out ./needle-low`, then rename its `needle3.cact` | Phones with 3 GB RAM or less |

**4. Run**

Open the `voice-automation` folder in Android Studio, let Gradle sync, pick your phone, press Run.

**5. Try it**

"open WhatsApp" · "off the torch" · "set alarm for 6:30" · "timer for 5 minutes" ·
"volume 40" · "note buy garri and pepper" · "delete the note buy garri"

The last one shows the confirmation card.

## Before you trust it

- **Check `needle.h`.** The bridge uses the C signatures the `cactus-needle` Python
  package binds (engine generation 3). If the header you download differs, the C
  compiler will say so; adjust `needle_jni.c` to match the header.
- **What was verified:** the Kotlin code compiles against Android 15 and the exact
  AndroidX/Compose versions in `app/build.gradle.kts`; the JNI bridge compiles
  against those C signatures; the Kotlin and C native method names match. It has not
  yet been built with Gradle or run on a phone.
- **Confidence score:** the base `needle3.cact` reports one. Weights fine-tuned
  locally may not; the policy then asks before running anything that isn't SAFE.
- **Engine memory:** `needle_load` keeps its own copy of the model, so RAM use is
  about the file size per model. Check `peak_ram_mb` in the engine's responses.

## Next steps (from the roadmap)

1. Run the Nigerian-accent test: 200 recorded commands, 10+ speakers, score correct tool calls.
2. Fine-tune Needle on your tools with Nigerian phrasings (`needle` LoRA), then build per-tier depths.
3. Add the remaining tools (files via MediaStore, Do Not Disturb, brightness, reminders).
4. Add automations: Room, AlarmManager/WorkManager, replay worker, pending-confirmation queue.
5. Add a Quick Settings tile and widget (with a `microphone` foreground service), then Play prep.
