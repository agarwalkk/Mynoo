# GitHub Copilot Instructions — Mynoo

## Project identity

Mynoo is a **Kotlin/Jetpack Compose** Android app (package `com.krishanagarwal.mynoo`).  
Target: CBSE school children (Classes 6–10) learning in English, Hindi, and Punjabi.  
Backend: **Firebase Firestore + Firebase Storage**. AI: **Google Gemini** (chat + TTS) + **Sarvam AI** (STT).

---

## Code style & conventions

- **Language**: Kotlin 2.1.0 — use idiomatic Kotlin (data classes, sealed classes, `when` expressions, coroutines, Flows).
- **UI**: Jetpack Compose + Material3 only. No XML layouts, no View system.
- **Theming**: Use `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hardcoded colours or text sizes.
- **DI**: Hilt (`@HiltViewModel`, `@Singleton`, `@Inject constructor`). Never use `by viewModels()` directly in Composables — use `hiltViewModel()`.
- **Async**: `viewModelScope.launch` + `suspend` functions. No `GlobalScope`, no `runBlocking` in UI code.
- **State**: `StateFlow<UiState>` exposed from ViewModels. Collect with `collectAsState()` in Composables.
- **Formatting**: 4-space indent. Named parameters for Composables with more than 2 arguments.

---

## Architecture

```
Composable screen
  └── hiltViewModel() → ViewModel (viewModelScope)
        └── Repository (Firestore / Storage / Retrofit)
              └── Firestore SDK / OkHttp / Retrofit
```

- **Screens** live in `ui/screens/` — one file per screen, no business logic.
- **ViewModels** live in `ui/viewmodel/` — one per feature, expose `UiState` data class via `StateFlow`.
- **Repositories** live in `data/repository/` — one per Firestore collection, cache-first where possible.
- **DI** wiring is in `di/AppModule.kt` (SingletonComponent). Never add a second `@Module`.

---

## Firestore data model (do not change collection paths)

| Collection path | Doc ID | Key fields |
|---|---|---|
| `kids/{name}` | child name | `name, age, class, createdAt` |
| `kids/{name}/sessions/{id}` | auto | `date, endDate, durationMin, lang, mood` |
| `kids/{name}/assessments/{id}` | auto | `subject, classNum, lang, date, status, questions[], summary` |
| `classes/{classNum}/subjects/{slug}/chapters/{id}` | chapter id | `title, order, wordCount, published` |

Subject slugs are **lowercase with underscores**: `hindi`, `english`, `punjabi`, `mathematics`, `science`, `social_studies`, `computer`.

Chapter content JSON lives in **Firebase Storage** at:
`classes/{classNum}/{slug}/{chapterId}/content.json`

---

## AI API conventions

All Gemini calls go through `GeminiApi` (Retrofit interface, base URL `https://generativelanguage.googleapis.com/`).  
Always pass `BuildConfig.GEMINI_API_KEY` as the first `@Query("key")` argument.

```kotlin
// ✅ correct
val resp = geminiApi.generateContent(BuildConfig.GEMINI_API_KEY, request)

// ❌ wrong — missing API key
val resp = geminiApi.generateContent(request)
```

Sarvam STT calls go through `SarvamApi` — pass `BuildConfig.SARVAM_API_KEY` as the `@Header("api-subscription-key")` argument.

---

## Secrets

**Never hardcode API keys.** All keys come from `local.properties` → `BuildConfig` fields:

- `BuildConfig.GEMINI_API_KEY`
- `BuildConfig.SARVAM_API_KEY`
- `BuildConfig.ELEVENLABS_API_KEY`
- `BuildConfig.XAI_API_KEY`
- `BuildConfig.OPENAI_API_KEY`

`local.properties` is git-ignored. Never suggest committing it.

---

## Navigation

Routes are defined as `object Screen` sealed hierarchy in `NavGraph.kt`.  
All navigation calls go through `navController.navigate(Screen.XYZ.route(...))`.  
Do not add hard-coded route strings anywhere outside `NavGraph.kt`.

Current route tree:
```
ChildSelect (start)
└── [bottom nav]
    ├── Tutor
    ├── Library → ChapterList/{classNum}/{subject}/{lang}
    │                └── ChapterReader/{classNum}/{subject}/{chapterId}/{lang}?title=
    └── Progress → AssessmentList/{lang}/{childName}?subject=
                      └── Assessment/{assessmentId}/{childName}
[top bar]
└── Settings gear → ParentDashboard
```

---

## Build commands

```powershell
# Debug
.\gradlew assembleDebug > build.log 2>&1

# Release (signed)
.\gradlew assembleRelease > release.log 2>&1
```

APK output: `app/build/outputs/apk/release/app-release.apk`  
Package name: `com.krishanagarwal.mynoo` (debug gets `.debug` suffix)

---

## Common pitfalls

- `ChapterRepository` needs `OkHttpClient` injected — do **not** add another `@Provides OkHttpClient` in `AppModule`; only one binding is allowed.
- `SessionRecord` (not `Session`) is the data class in `SessionRepository`.
- `durationMin` in `SessionRecord` is `Double` — call `.toInt()` before displaying.
- `Icons.Filled.ArrowBack` and `Icons.Filled.TrendingUp` are deprecated — use the `AutoMirrored` variants.
- When adding a new screen, register it in both the `Screen` sealed object and `MynooNavGraph` composable.
