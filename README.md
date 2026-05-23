# Mynoo — AI Learning Companion for Kids

Mynoo is a Kotlin/Jetpack Compose Android app that provides an AI-powered tutoring experience for school children (Classes 6–10, CBSE). It uses Google Gemini for conversational tutoring and text-to-speech, Sarvam AI for speech-to-text, and Firebase as the backend.

## Features

| Feature | Description |
|---|---|
| **AI Tutor** | Voice-based conversational tutor (EN / HI / PA) with mood check-in and session logging |
| **Chapter Library** | Browse and read CBSE textbook chapters from Firebase Storage with rich paragraph rendering |
| **Progress** | 30-day heatmap, session streak, mood stats, and total study minutes |
| **Assessments** | Gemini-generated quizzes (8 question types) with instant MCQ grading and AI feedback |
| **Parent Dashboard** | Model selector and per-language difficulty sliders |
| **Child Profiles** | Multi-child support with Firebase Firestore, last-used child persisted via DataStore |

## Tech Stack

| Layer | Library / Tool |
|---|---|
| Language | Kotlin 2.1.0 |
| UI | Jetpack Compose + Material3 (BOM 2024.12.01) |
| Build | AGP 8.7.3 · Gradle 8.11.1 · compileSdk 36 · minSdk 26 |
| DI | Hilt 2.51.1 (kapt) |
| Backend | Firebase Firestore + Firebase Storage |
| AI | Google Gemini 2.0 Flash (chat & TTS) · Sarvam saarika:v2.5 (STT) |
| Networking | Retrofit 2.11.0 + OkHttp 4.12.0 |
| Persistence | DataStore Preferences 1.1.1 |

## Project Structure

```
app/src/main/kotlin/com/krishanagarwal/mynoo/
├── data/
│   ├── api/              # Retrofit interfaces + Gemini/Sarvam data models
│   ├── model/            # Shared data classes (ChildState, etc.)
│   ├── repository/       # Firestore CRUD (Child, Session, Chapter, Assessment)
│   └── store/            # DataStore (last selected child)
├── di/
│   └── AppModule.kt      # Hilt SingletonComponent — Firebase, Retrofit, OkHttp
├── service/
│   ├── AudioRecorderService.kt   # AudioRecord → WAV (16 kHz mono)
│   └── TtsService.kt             # Gemini TTS REST → PCM → AudioTrack
├── ui/
│   ├── navigation/       # NavGraph + Screen route definitions
│   ├── screens/          # All Composable screens
│   ├── theme/            # Material3 theme, typography, colours
│   └── viewmodel/        # Hilt ViewModels (one per feature)
├── MainActivity.kt       # Single activity; hosts MynooNavGraph
└── MynooApp.kt           # @HiltAndroidApp Application class
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full layer diagram, Firestore schema, and API contracts.

## Getting Started

### Prerequisites

- Android Studio Meerkat or later
- JDK 17
- Android SDK with API 36

### Secrets (`local.properties`)

Create `local.properties` at the repo root (already git-ignored):

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk

GEMINI_API_KEY=...
SARVAM_API_KEY=...
ELEVENLABS_API_KEY=...
XAI_API_KEY=...
OPENAI_API_KEY=...
GOOGLE_CLIENT_EMAIL=...
GIST_API_KEY=...

# Release signing
KEYSTORE_PATH=C\:\\path\\to\\mynoo-release.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=mynoo
KEY_PASSWORD=...
```

### Build

```bash
# Debug APK
./gradlew assembleDebug

# Signed release APK  (requires signing keys in local.properties)
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Firestore Schema

```
kids/{name}
  ├── name, age, class, createdAt
  ├── sessions/{sessionId}
  │     id, date, endDate, durationMin, lang, mood, aiSummary?
  └── assessments/{assessmentId}
        subject, classNum, lang, date, status, questions[], summary

classes/{classNum}/subjects/{slug}/chapters/{chapterId}
  title, order, wordCount, sentenceCount, published
```

Chapter content is stored in Firebase Storage at:
`classes/{classNum}/{slug}/{chapterId}/content.json`

## Firebase Storage Bucket

`aaravtutor-1e880.firebasestorage.app`

## AI Endpoints

| Purpose | Endpoint |
|---|---|
| Chat (tutor) | `POST generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent` |
| TTS | `POST generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent` |
| STT | `POST api.sarvam.ai/speech-to-text` (multipart, model=saarika:v2.5) |

## License

Private — all rights reserved.
