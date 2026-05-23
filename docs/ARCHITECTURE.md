# Mynoo — Architecture Reference

## Layer Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer  (Jetpack Compose + Material3)                    │
│                                                             │
│  ChildSelectScreen   TutorScreen     LibraryScreen          │
│  ChapterListScreen   ChapterReader   ProgressScreen         │
│  AssessmentList      AssessmentScreen ParentDashboard        │
│                                                             │
│  MynooNavGraph  ─  Screen (sealed object)                   │
└────────────────────────┬────────────────────────────────────┘
                         │  hiltViewModel()
┌────────────────────────▼────────────────────────────────────┐
│  ViewModel Layer                                            │
│                                                             │
│  ChildViewModel   TutorViewModel   LibraryViewModel         │
│  ProgressViewModel  AssessmentViewModel                     │
│                                                             │
│  StateFlow<UiState>  ←  viewModelScope.launch               │
└──────────┬─────────────────┬───────────────────────────────┘
           │                 │
┌──────────▼──────┐  ┌───────▼──────────────────────────────┐
│  Service Layer  │  │  Repository Layer                     │
│                 │  │                                       │
│  TtsService     │  │  ChildRepository  (kids collection)   │
│  AudioRecorder  │  │  SessionRepository (sessions sub-col) │
│  Service        │  │  ChapterRepository (classes + Storage)│
└──────────┬──────┘  │  AssessmentRepository (assessments)  │
           │         └───────────────┬───────────────────────┘
           │                         │
┌──────────▼─────────────────────────▼───────────────────────┐
│  Data Sources                                               │
│                                                             │
│  Firebase Firestore   Firebase Storage   Gemini REST        │
│  DataStore Prefs      Sarvam STT API     OkHttp             │
└─────────────────────────────────────────────────────────────┘
```

---

## Module: `di/AppModule.kt`

Single `@Module` + `@InstallIn(SingletonComponent::class)`.

| Binding | Type | Notes |
|---|---|---|
| `provideFirestore()` | `FirebaseFirestore` | 100 MB persistent cache |
| `providePrefsStore()` | `MynooPrefsStore` | DataStore via `@Inject constructor` |
| `provideOkHttp()` | `OkHttpClient` | Shared by Retrofit + ChapterRepository |
| `provideGeminiRetrofit()` | `@Named("gemini") Retrofit` | base URL = generativelanguage.googleapis.com |
| `provideSarvamRetrofit()` | `@Named("sarvam") Retrofit` | base URL = api.sarvam.ai |
| `provideGeminiApi()` | `GeminiApi` | |
| `provideSarvamApi()` | `SarvamApi` | |

> **Rule**: Never add a second `@Provides OkHttpClient`. `ChapterRepository` receives the shared client via `@Inject constructor`.

---

## Navigation: `ui/navigation/NavGraph.kt`

### Screen sealed object

```kotlin
object Screen {
    object ChildSelect
    object Tutor
    object Library
    object Progress
    object ParentDashboard
    object ChapterList    // /{classNum}/{subject}/{lang}
    object ChapterReader  // /{classNum}/{subject}/{chapterId}/{lang}?title=
    object AssessmentList // /{lang}/{childName}?subject=
    object Assessment     // /{assessmentId}/{childName}
}
```

### Route tree

```
ChildSelect (start destination)
└── after profile selected →
    ├── Tutor        (bottom nav tab 0)
    ├── Library      (bottom nav tab 1)
    │     └── ChapterList/{classNum}/{subject}/{lang}
    │             └── ChapterReader/{classNum}/{subject}/{chapterId}/{lang}?title={title}
    └── Progress     (bottom nav tab 2)
          └── AssessmentList/{lang}/{childName}?subject={subject}
                  └── Assessment/{assessmentId}/{childName}

[TopAppBar settings icon] → ParentDashboard
```

Bottom nav shown on: `Tutor`, `Library`, `Progress`.  
TopAppBar shown on: `Tutor`, `Library`, `Progress`, `ParentDashboard`, `ChapterList`, `AssessmentList`.

---

## Data Layer

### `data/model/ChildState.kt`

```kotlin
data class ChildState(val name: String, val classNum: String) {
    val isSelected get() = name.isNotBlank()
}
data class Child(val name: String, val age: Int, val classNum: String, val createdAt: Long)
```

### `data/store/MynooPrefsStore.kt`

DataStore Preferences. Keys: `lastChildName`, `lastChildClass`.  
Methods: `saveLastChild(name, classNum)`, `clearLastChild()`.

### `data/repository/ChildRepository.kt`

Collection: `kids/{name}` (doc ID = child name).  
`loadChildren()` — cache-first with server fallback.  
`addChild(name, age, classNum)`, `deleteChild(name)`, `getClassNum(name)`.

### `data/repository/SessionRepository.kt`

Collection: `kids/{name}/sessions/{sessionId}`.  
Data class: `SessionRecord(id, date, endDate, durationMin: Double, lang, mood)`.  
`getSessions(childName)` — last 60, ordered by date desc.  
`saveSession(...)`, `saveMood(childName, sessionId, mood)`, `getStreak(childName)`.

### `data/repository/ChapterRepository.kt`

Firestore: `classes/{classNum}/subjects/{slug}/chapters` — `published==true`, ordered by `order`.  
Storage: `GET https://firebasestorage.googleapis.com/v0/b/aaravtutor-1e880.firebasestorage.app/o/{encodedPath}?alt=media`  
Path: `classes/{classNum}/{slug}/{chapterId}/content.json`  
Subject slug: `subject.lowercase().replace(' ', '_')`

### `data/repository/AssessmentRepository.kt`

Collection: `kids/{name}/assessments/{id}`.  
`getAssessments(childName)` — last 20, ordered by date desc.  
`saveAssessment(childName, assessment)` — returns new doc ID.  
`saveSummary(childName, assessmentId, summary)`.

---

## Service Layer

### `service/AudioRecorderService.kt`

- `@Singleton`
- Records 16 kHz mono PCM via `AudioRecord`
- Writes proper RIFF/WAVE header → temp WAV file
- Max 30 s; `requestStop()` stops cleanly, `requestSend()` stops and flushes

### `service/TtsService.kt`

- Calls `GeminiApi.generateSpeech()` → decodes base64 audio/L16 PCM
- Streams decoded PCM through `AudioTrack`
- Voice: `"Aoede"` · `parseSampleRate()` reads `audio/L16;rate=24000`
- `stop()` releases the `AudioTrack`

---

## AI API: `data/api/`

### `ApiInterfaces.kt`

```kotlin
interface GeminiApi {
    @POST("v1beta/models/gemini-2.0-flash:generateContent")
    suspend fun generateContent(@Query("key") apiKey: String, @Body request: GeminiRequest): GeminiResponse

    @POST("v1beta/models/gemini-2.5-flash-preview-tts:generateContent")
    suspend fun generateSpeech(@Query("key") apiKey: String, @Body request: GeminiRequest): GeminiResponse
}

interface SarvamApi {
    @Multipart @POST("speech-to-text")
    suspend fun transcribe(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language_code") lang: RequestBody,
        @Header("api-subscription-key") apiKey: String,
    ): SarvamSttResponse
}
```

### `GeminiModels.kt` (key types)

| Class | Purpose |
|---|---|
| `GeminiRequest` | Top-level request (systemInstruction?, contents, generationConfig?) |
| `GeminiContent` | `role?` + `parts: List<GeminiPart>` |
| `GeminiPart` | `text?` or `inlineData?` |
| `GeminiGenConfig` | `temperature?`, `responseModalities?`, `speechConfig?` |
| `GeminiResponse` | `candidates?`, `error?` |

---

## ViewModel: TutorViewModel state machine

```
IDLE ──start──► STARTING ──► BOT_SPEAKING
                                 │
                           ◄─── WAITING_CHILD
                                 │ pressMic
                             RECORDING
                                 │ stopMicAndSend / sendQuickReply
                             PROCESSING
                                 │
                           ◄─── BOT_SPEAKING
                                 │ endSession
                              ENDED
```

---

## Chapter Content JSON schema

Stored in Firebase Storage → `content.json`:

```json
{
  "paragraphs": [
    {
      "id": "p1",
      "type": "prose|heading|subheading|blockquote|activity|callout|note|list|verse|equation|table",
      "text": "...",
      "sentences": [{ "id": "s1", "text": "...", "meaning": "..." }],
      "items":   ["..."],
      "headers": ["Col A", "Col B"],
      "rows":    [["cell", "cell"]],
      "title":   "Activity title",
      "caption": "..."
    }
  ]
}
```

`ChapterReaderScreen` renders each type: headings bold/large, blockquotes in `secondaryContainer`, activity/callout/note in `tertiaryContainer`, lists numbered, prose with inline bold/italic.

---

## Assessment Question Types

| Type | Auto-graded | UI |
|---|---|---|
| `mcq` | ✅ | 4 tap-to-select chips; green = correct, red = wrong |
| `fill_blank` | ❌ | TextInput → reveal answer; word-bank hint shown |
| `short_answer` | ❌ | TextInput → reveal answer |
| `transformation` | ❌ | Input sentence shown → TextInput → reveal |
| `error_correction` | ❌ | Erroneous sentence shown → TextInput → reveal |
| `jumbled` | ❌ | Scrambled prompt → TextInput → reveal |
| `match_columns` | ❌ | Column A/B shown → TextInput → reveal |
| `translation` | ❌ | Source sentence shown → TextInput → reveal |

After the last question, `AssessmentViewModel` calls Gemini for a 3-sentence performance summary, then saves it to `kids/{name}/assessments/{id}.summary`.

---

## Build & Signing

| Key | Value |
|---|---|
| `compileSdk` | 36 |
| `minSdk` | 26 |
| `targetSdk` | 36 |
| `versionCode` | 1 |
| `versionName` | 1.0.0 |
| Keystore | `mynoo-release.jks` (git-ignored) |
| Alias | `mynoo` |
| Debug suffix | `.debug` |

```powershell
# Debug
.\gradlew assembleDebug > build.log 2>&1

# Signed release
.\gradlew assembleRelease > release.log 2>&1
# → app/build/outputs/apk/release/app-release.apk
```
