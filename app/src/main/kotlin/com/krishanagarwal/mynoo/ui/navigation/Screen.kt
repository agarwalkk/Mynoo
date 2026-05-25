package com.krishanagarwal.mynoo.ui.navigation

/**
 * Sealed class of all navigation destinations in the Mynoo app.
 *
 * Navigation map:
 *
 *   ChildSelect  →  (child chosen)  →  Main
 *   Main (bottom-nav host):
 *     ├── Tutor         (tab 1 — AI voice tutor)
 *     ├── Library       (tab 2 — subject/chapter browser)
 *     └── Progress      (tab 3 — streak, sessions, assessments)
 *   ParentDashboard  ←── gear icon in TopAppBar (any main screen)
 *   ChapterList      ←── Library tab or ParentDashboard
 *   ChapterReader    ←── ChapterList
 *   AssessmentList   ←── Progress tab
 *   Assessment       ←── AssessmentList or Progress pending card
 */
sealed class Screen(val route: String) {

    // ── Gate ──────────────────────────────────────────────────────────────────
    object ChildSelect : Screen("child_select")

    // ── Main bottom-tab host ──────────────────────────────────────────────────
    object Main     : Screen("main")
    object Tutor    : Screen("tutor")
    object Learn    : Screen("learn")
    object Progress : Screen("progress?childName={childName}&classNum={classNum}") {
        fun route(childName: String = "", classNum: String = "") =
            "progress?childName=$childName&classNum=$classNum"
    }

    // ── Top-level stack screens ───────────────────────────────────────────────
    object ParentDashboard : Screen("parent_dashboard")

    object ChapterList : Screen(
        "chapter_list/{classNum}/{subject}/{lang}"
    ) {
        fun route(classNum: String, subject: String, lang: String) =
            "chapter_list/$classNum/$subject/$lang"
    }

    object ChapterReader : Screen(
        "chapter_reader/{classNum}/{subject}/{chapterId}/{lang}?title={title}"
    ) {
        fun route(
            classNum:  String,
            subject:   String,
            chapterId: String,
            lang:      String,
            title:     String = "",
        ) = "chapter_reader/$classNum/$subject/$chapterId/$lang?title=${
            java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        }"
    }

    object AssessmentList : Screen(
        "assessment_list/{lang}/{childName}?subject={subject}"
    ) {
        fun route(lang: String, childName: String, subject: String = "") =
            "assessment_list/$lang/$childName?subject=$subject"
    }

    object Assessment : Screen(
        "assessment/{assessmentId}/{childName}"
    ) {
        fun route(assessmentId: String, childName: String) =
            "assessment/$assessmentId/$childName"
    }

    object PlacementQuiz : Screen(
        "placement_quiz?childName={childName}&lang={lang}"
    ) {
        fun route(childName: String, lang: String = "") =
            "placement_quiz?childName=$childName&lang=$lang"
    }
}
