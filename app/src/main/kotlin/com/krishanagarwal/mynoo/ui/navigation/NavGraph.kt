package com.krishanagarwal.mynoo.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.graphics.Color
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
import com.krishanagarwal.mynoo.ui.screens.LearnScreen
import com.krishanagarwal.mynoo.ui.screens.ParentDashboardScreen
import com.krishanagarwal.mynoo.ui.screens.ProgressScreen
import com.krishanagarwal.mynoo.ui.screens.PlacementQuizScreen
import com.krishanagarwal.mynoo.ui.screens.TutorScreen
import androidx.navigation.NavGraph.Companion.findStartDestination

// Screens that show the bottom navigation bar
private val bottomNavRoutes = setOf(
    Screen.Tutor.route,
    Screen.Learn.route,
)

// Screens that show the TopAppBar (excludes ChildSelect and full-screen modals)
private val topBarRoutes = setOf(
    Screen.Tutor.route,
    Screen.Learn.route,
    Screen.Progress.route,
    Screen.ParentDashboard.route,
    Screen.ChapterList.route,
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

    val subjectArg = navBackStackEntry?.arguments?.getString("subject").orEmpty()
    val isChapterList = currentRoute == Screen.ChapterList.route
    
    val topBarBgColor = if (isChapterList && subjectArg.isNotBlank()) {
        val slug = subjectArg.lowercase().trim()
        when (slug) {
            "hindi" -> Color(0xFFE67E22)
            "english" -> Color(0xFF27AE60)
            "punjabi" -> Color(0xFF8E44AD)
            "mathematics", "math" -> Color(0xFF2980B9)
            "science" -> Color(0xFF16A085)
            "social studies", "social_studies" -> Color(0xFFC0392B)
            "computer" -> Color(0xFF7F8C8D)
            else -> Color(0xFF2980B9)
        }
    } else {
        MaterialTheme.colorScheme.surface
    }

    val topBarContentColor = if (isChapterList && subjectArg.isNotBlank()) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Scaffold(
        topBar = {
            if (currentRoute in topBarRoutes && currentRoute?.startsWith("assessment_list") != true) {
                TopAppBar(
                    title = {
                        val titleText = when {
                            currentRoute == Screen.Tutor.route -> "AI Tutor"
                            currentRoute == Screen.Learn.route -> "Learn"
                            currentRoute == Screen.Progress.route -> "My Progress"
                            currentRoute == Screen.ChapterList.route -> {
                                navBackStackEntry?.arguments?.getString("subject") ?: "Chapters"
                            }
                            currentRoute?.startsWith(Screen.AssessmentList.route) == true -> "Assessments"
                            currentRoute == Screen.Assessment.route -> "Assessment"
                            currentRoute == Screen.ParentDashboard.route -> "Parent Dashboard"
                            else -> "Mynoo"
                        }
                        if (currentRoute == Screen.ChapterList.route) {
                            val classNum = navBackStackEntry?.arguments?.getString("classNum") ?: ""
                            androidx.compose.foundation.layout.Column {
                                Text(
                                    text = titleText,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                )
                                if (classNum.isNotBlank()) {
                                    Text(
                                        text = "Class $classNum",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            )
                        }
                    },
                    navigationIcon = {
                        if (currentRoute !in bottomNavRoutes && currentRoute != Screen.ChildSelect.route) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = topBarContentColor)
                            }
                        }
                    },
                    actions = {
                        if (currentRoute in bottomNavRoutes) {
                            IconButton(onClick = {
                                onChildSet(ChildState())
                                navController.navigate(Screen.ChildSelect.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                }
                            }) {
                                Icon(Icons.Default.Home, contentDescription = "Home")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarBgColor,
                        titleContentColor = topBarContentColor,
                        navigationIconContentColor = topBarContentColor,
                        actionIconContentColor = topBarContentColor
                    )
                )
            }
        },
        bottomBar = {
            if (currentRoute in bottomNavRoutes) {
                MynooBottomBar(navController = navController)
            }
        }
    ) { innerPadding ->
        val startDestination = if (childState.isSelected) Screen.Learn.route else Screen.ChildSelect.route
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ── Main Tabs ─────────────────────────────────────────────────────
            composable(Screen.Tutor.route) {
                TutorScreen(
                    childState   = childState,
                    onChildReset = { onChildSet(ChildState()) },
                    onNavigateToPlacementQuiz = { childName ->
                        navController.navigate(Screen.PlacementQuiz.route(childName))
                    }
                )
            }
            composable(Screen.Learn.route) {
                LearnScreen(
                    childState               = childState,
                    onNavigateToChapterList  = { classNum, subject, lang ->
                        navController.navigate(Screen.ChapterList.route(classNum, subject, lang))
                    },
                    onNavigateToAssessmentList = { lang, childName, subject ->
                        navController.navigate(Screen.AssessmentList.route(lang, childName, subject))
                    },
                )
            }
            composable(
                route = Screen.Progress.route,
                arguments = listOf(
                    navArgument("childName") { type = NavType.StringType; defaultValue = "" },
                    navArgument("classNum") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val childName = entry.arguments?.getString("childName").orEmpty()
                val classNum = entry.arguments?.getString("classNum").orEmpty()
                val resolvedChildState = if (childName.isNotBlank()) {
                    com.krishanagarwal.mynoo.data.model.ChildState(name = childName, classNum = classNum)
                } else {
                    childState
                }
                ProgressScreen(
                    childState                 = resolvedChildState,
                    onNavigateToAssessmentList = { lang, name, subject ->
                        navController.navigate(Screen.AssessmentList.route(lang, name, subject))
                    },
                    onNavigateToAssessment     = { assessmentId, name ->
                        navController.navigate(Screen.Assessment.route(assessmentId, name))
                    },
                )
            }

            // ── Selection & Auth ──────────────────────────────────────────────
            composable(Screen.ChildSelect.route) {
                ChildSelectScreen(
                    onChildSelected = { name, classNum ->
                        onChildSet(ChildState(name = name, classNum = classNum))
                    },
                    onNavigateToParentDashboard = {
                        navController.navigate(Screen.ParentDashboard.route)
                    }
                )
            }

            // ── Parent Dashboard ──────────────────────────────────────────────
            composable(Screen.ParentDashboard.route) {
                ParentDashboardScreen(
                    childState = childState,
                    onNavigateToProgress = { childName, classNum ->
                        navController.navigate(Screen.Progress.route(childName, classNum))
                    },
                    onNavigateToAssessment = { assessmentId, childName ->
                        navController.navigate(Screen.Assessment.route(assessmentId, childName))
                    }
                )
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
                    onChapterClick = { chapterId: String, title: String ->
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
                    onBackClick = { navController.popBackStack() },
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
                    },
                    onBackClick = {
                        navController.popBackStack()
                    },
                )
            }

            // ── Assessment Player ─────────────────────────────────────────────
            composable(
                route     = Screen.Assessment.route,
                arguments = listOf(
                    navArgument("assessmentId") { type = NavType.StringType },
                    navArgument("childName")    { type = NavType.StringType },
                ),
            ) { entry ->
                AssessmentScreen(
                    assessmentId = entry.arguments?.getString("assessmentId") ?: "",
                    childName    = entry.arguments?.getString("childName")    ?: "",
                    onFinish     = { navController.popBackStack() },
                )
            }

            // ── Placement Quiz ────────────────────────────────────────────────
            composable(
                route     = Screen.PlacementQuiz.route,
                arguments = listOf(
                    navArgument("childName") { type = NavType.StringType },
                    navArgument("lang")      { type = NavType.StringType; nullable = true; defaultValue = null }
                ),
            ) { entry ->
                PlacementQuizScreen(
                    childName    = entry.arguments?.getString("childName") ?: "",
                    langOverride = entry.arguments?.getString("lang"),
                    onExit       = { navController.popBackStack() },
                    onFinish     = {
                        navController.navigate(Screen.Tutor.route) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
