package com.krishanagarwal.mynoo.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.krishanagarwal.mynoo.data.model.ChildState
import com.krishanagarwal.mynoo.ui.screens.AssessmentListScreen
import com.krishanagarwal.mynoo.ui.screens.AssessmentScreen
import com.krishanagarwal.mynoo.ui.screens.ChapterListScreen
import com.krishanagarwal.mynoo.ui.screens.ChapterReaderScreen
import com.krishanagarwal.mynoo.ui.screens.ChildSelectScreen
import com.krishanagarwal.mynoo.ui.screens.LibraryScreen
import com.krishanagarwal.mynoo.ui.screens.ParentDashboardScreen
import com.krishanagarwal.mynoo.ui.screens.ProgressScreen
import com.krishanagarwal.mynoo.ui.screens.TutorScreen

// Screens that show the bottom navigation bar
private val bottomNavRoutes = setOf(
    Screen.Tutor.route,
    Screen.Library.route,
    Screen.Progress.route,
)

// Screens that show the TopAppBar (excludes ChildSelect and full-screen modals)
private val topBarRoutes = setOf(
    Screen.Tutor.route,
    Screen.Library.route,
    Screen.Progress.route,
    Screen.ParentDashboard.route,
    Screen.ChapterList.route,
    Screen.AssessmentList.route,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MynooNavGraph(
    childState: ChildState,
    onChildSet: (ChildState) -> Unit,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomNavRoutes
    val showTopBar    = currentRoute in topBarRoutes
    val showBack      = currentRoute != null &&
            currentRoute !in bottomNavRoutes &&
            currentRoute != Screen.ParentDashboard.route

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = {
                        Text(
                            text  = topBarTitle(currentRoute, childState.name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        if (showBack) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        // Gear icon on all main tabs → Parent Dashboard
                        if (currentRoute in bottomNavRoutes) {
                            IconButton(onClick = {
                                navController.navigate(Screen.ParentDashboard.route)
                            }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                MynooBottomBar(navController = navController)
            }
        },
    ) { innerPadding ->

        NavHost(
            navController    = navController,
            startDestination = Screen.ChildSelect.route,
            modifier         = Modifier.padding(innerPadding),
            enterTransition  = { fadeIn() },
            exitTransition   = { fadeOut() },
            popEnterTransition = { slideInHorizontally { -it / 4 } + fadeIn() },
            popExitTransition  = { slideOutHorizontally { it / 4 } + fadeOut() },
        ) {

            // ── Gate: child profile picker ────────────────────────────────────
            composable(Screen.ChildSelect.route) {
                ChildSelectScreen(
                    onChildSelected = { name, classNum ->
                        onChildSet(ChildState(name = name, classNum = classNum))
                        navController.navigate(Screen.Tutor.route) {
                            popUpTo(Screen.ChildSelect.route) { inclusive = true }
                        }
                    }
                )
            }

            // ── Main bottom-tab destinations ──────────────────────────────────
            composable(Screen.Tutor.route) {
                TutorScreen(
                    childState  = childState,
                    onChildReset = {
                        onChildSet(ChildState())
                        navController.navigate(Screen.ChildSelect.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Library.route) {
                LibraryScreen(
                    childState  = childState,
                    onNavigateToChapterList = { classNum, subject, lang ->
                        navController.navigate(Screen.ChapterList.route(classNum, subject, lang))
                    }
                )
            }

            composable(Screen.Progress.route) {
                ProgressScreen(
                    childState = childState,
                    onNavigateToAssessmentList = { lang, childName, subject ->
                        navController.navigate(
                            Screen.AssessmentList.route(lang, childName, subject)
                        )
                    },
                    onNavigateToAssessment = { assessmentId, childName ->
                        navController.navigate(Screen.Assessment.route(assessmentId, childName))
                    }
                )
            }

            // ── Parent Dashboard ──────────────────────────────────────────────
            composable(Screen.ParentDashboard.route) {
                ParentDashboardScreen(childState = childState)
            }

            // ── Chapter List ──────────────────────────────────────────────────
            composable(
                route     = Screen.ChapterList.route,
                arguments = listOf(
                    navArgument("classNum") { type = NavType.StringType },
                    navArgument("subject")  { type = NavType.StringType },
                    navArgument("lang")     { type = NavType.StringType },
                ),
                enterTransition = { slideInHorizontally { it } + fadeIn() },
                exitTransition  = { slideOutHorizontally { -it / 4 } + fadeOut() },
                popExitTransition = { slideOutHorizontally { it } + fadeOut() },
            ) { entry ->
                ChapterListScreen(
                    classNum = entry.arguments?.getString("classNum") ?: "",
                    subject  = entry.arguments?.getString("subject")  ?: "",
                    lang     = entry.arguments?.getString("lang")     ?: "en",
                    onChapterClick = { chapterId, title ->
                        val classNum = entry.arguments?.getString("classNum") ?: ""
                        val subject  = entry.arguments?.getString("subject")  ?: ""
                        val lang     = entry.arguments?.getString("lang")     ?: "en"
                        navController.navigate(
                            Screen.ChapterReader.route(classNum, subject, chapterId, lang, title)
                        )
                    }
                )
            }

            // ── Chapter Reader ────────────────────────────────────────────────
            composable(
                route     = Screen.ChapterReader.route,
                arguments = listOf(
                    navArgument("classNum")  { type = NavType.StringType },
                    navArgument("subject")   { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                    navArgument("lang")      { type = NavType.StringType },
                    navArgument("title")     { type = NavType.StringType; defaultValue = "" },
                ),
                enterTransition = { slideInHorizontally { it } + fadeIn() },
                exitTransition  = { slideOutHorizontally { -it / 4 } + fadeOut() },
                popExitTransition = { slideOutHorizontally { it } + fadeOut() },
            ) { entry ->
                ChapterReaderScreen(
                    classNum  = entry.arguments?.getString("classNum")  ?: "",
                    subject   = entry.arguments?.getString("subject")   ?: "",
                    chapterId = entry.arguments?.getString("chapterId") ?: "",
                    lang      = entry.arguments?.getString("lang")      ?: "en",
                    title     = entry.arguments?.getString("title")     ?: "",
                )
            }

            // ── Assessment List ───────────────────────────────────────────────
            composable(
                route     = Screen.AssessmentList.route,
                arguments = listOf(
                    navArgument("lang")      { type = NavType.StringType },
                    navArgument("childName") { type = NavType.StringType },
                    navArgument("subject")   { type = NavType.StringType; defaultValue = "" },
                ),
                enterTransition = { slideInHorizontally { it } + fadeIn() },
                popExitTransition = { slideOutHorizontally { it } + fadeOut() },
            ) { entry ->
                AssessmentListScreen(
                    lang      = entry.arguments?.getString("lang")      ?: "en",
                    childName = entry.arguments?.getString("childName") ?: "",
                    subject   = entry.arguments?.getString("subject")   ?: "",
                    onStartAssessment = { assessmentId, childName ->
                        navController.navigate(Screen.Assessment.route(assessmentId, childName))
                    },
                    onNavigateToAssessment = { assessmentId, childName ->
                        navController.navigate(Screen.Assessment.route(assessmentId, childName))
                    }
                )
            }

            // ── Assessment ────────────────────────────────────────────────────
            composable(
                route     = Screen.Assessment.route,
                arguments = listOf(
                    navArgument("assessmentId") { type = NavType.StringType },
                    navArgument("childName")    { type = NavType.StringType },
                ),
                enterTransition  = { fadeIn() },
                exitTransition   = { fadeOut() },
            ) { entry ->
                AssessmentScreen(
                    assessmentId = entry.arguments?.getString("assessmentId") ?: "",
                    childName    = entry.arguments?.getString("childName")    ?: "",
                )
            }
        }
    }
}

/** TopAppBar title per route. */
private fun topBarTitle(route: String?, childName: String): String = when (route) {
    Screen.Tutor.route            -> if (childName.isNotBlank()) "Hi, $childName! 👋" else "Mynoo"
    Screen.Library.route          -> "Library 📚"
    Screen.Progress.route         -> if (childName.isNotBlank()) "$childName's Progress 📈" else "Progress"
    Screen.ParentDashboard.route  -> "Parent Dashboard"
    Screen.ChapterList.route      -> "Chapters"
    Screen.AssessmentList.route   -> "Assessments"
    else                          -> "Mynoo"
}
