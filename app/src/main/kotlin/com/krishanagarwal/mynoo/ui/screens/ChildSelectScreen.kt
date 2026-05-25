package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.data.model.Child
import com.krishanagarwal.mynoo.ui.viewmodel.ChildViewModel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing

private val AVATARS = listOf("🧒", "👦", "🧑", "👧", "🐯", "🦁", "🚀", "🌟", "🎯", "🦋", "🐬", "🌈")
private val CLASSES = listOf("6", "7", "8", "9", "10")

private fun avatarFor(name: String): String =
    AVATARS[(name.firstOrNull()?.code ?: 0) % AVATARS.size]

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChildSelectScreen(
    onChildSelected: (name: String, classNum: String) -> Unit,
    onNavigateToParentDashboard: () -> Unit,
    vm: ChildViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    var deleteTarget  by remember { mutableStateOf<String?>(null) }

    var showParentPinDialog by remember { mutableStateOf(false) }
    var parentPinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    var activeSplashChild by remember { mutableStateOf<Child?>(null) }

    if (activeSplashChild != null) {
        SplashOverlay(
            child = activeSplashChild!!,
            onFinished = {
                val target = activeSplashChild!!
                activeSplashChild = null
                onChildSelected(target.name, target.classNum)
            }
        )
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(32.dp))

            Text(
                text      = "Mynoo",
                style     = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color     = MaterialTheme.colorScheme.primary,
                modifier  = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text      = "Who's learning today?",
                style     = MaterialTheme.typography.titleMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier  = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier.weight(1f)
            ) {
                when {
                    ui.loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                    ui.children.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🌱", fontSize = 64.sp)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text      = "No learners yet.\nTap 'I'm a parent' at the bottom to add a learner.",
                                style     = MaterialTheme.typography.bodyLarge,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    else -> LazyVerticalGrid(
                        columns               = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement   = Arrangement.spacedBy(12.dp),
                        contentPadding        = PaddingValues(bottom = 96.dp),
                        modifier              = Modifier.fillMaxSize(),
                    ) {
                        items(ui.children, key = { it.name }) { child ->
                            ChildCard(
                                child       = child,
                                isLastUsed  = child.name == ui.lastChild,
                                onClick     = {
                                    vm.saveLastChild(child.name, child.classNum)
                                    activeSplashChild = child
                                },
                                onLongClick = { deleteTarget = child.name },
                            )
                        }
                    }
                }
            }

            ui.error?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(msg, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    showParentPinDialog = true
                    parentPinInput = ""
                    pinError = false
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("🔑 I'm a parent", fontWeight = FontWeight.Bold, maxLines = 1)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
    }

    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon  = {
                Icon(Icons.Default.Delete, null,
                    tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("Remove $name?") },
            text  = {
                Text("$name will be removed from this list. " +
                        "Their data in Firebase is kept.")
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.deleteChild(name); deleteTarget = null },
                    colors  = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showParentPinDialog) {
        AlertDialog(
            onDismissRequest = { 
                showParentPinDialog = false
                parentPinInput = ""
                pinError = false
            },
            title = { Text("Parent Verification", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Please enter the 4-digit parent PIN to access parent settings.")
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = parentPinInput,
                        onValueChange = { 
                            if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                                parentPinInput = it
                                pinError = false
                                if (it.length >= 4) {
                                    vm.verifyPin(it) { correct ->
                                        if (correct) {
                                            showParentPinDialog = false
                                            parentPinInput = ""
                                            onNavigateToParentDashboard()
                                        } else if (it.length >= 6) {
                                            pinError = true
                                        }
                                    }
                                }
                            }
                        },
                        label = { Text("Parent PIN") },
                        singleLine = true,
                        isError = pinError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError) {
                        Text(
                            text = "Incorrect PIN. Please try again.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.verifyPin(parentPinInput) { correct ->
                            if (correct) {
                                showParentPinDialog = false
                                parentPinInput = ""
                                onNavigateToParentDashboard()
                            } else {
                                pinError = true
                            }
                        }
                    }
                ) {
                    Text("Verify")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showParentPinDialog = false
                        parentPinInput = ""
                        pinError = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChildCard(
    child:       Child,
    isLastUsed:  Boolean,
    onClick:     () -> Unit,
    onLongClick: () -> Unit,
) {
    ElevatedCard(
        modifier  = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape     = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (isLastUsed) 6.dp else 2.dp),
        colors    = CardDefaults.elevatedCardColors(
            containerColor = if (isLastUsed)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier            = Modifier.padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier         = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(avatarFor(child.name), fontSize = 32.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text      = child.name,
                style     = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                maxLines  = 1,
            )
            if (child.classNum.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text     = "Class ${child.classNum}",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            if (isLastUsed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Last used",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}



@Composable
fun SplashOverlay(
    child: Child,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val theme = remember { (1..4).random() }
    val infiniteTransition = rememberInfiniteTransition(label = "splash")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    )
    
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -100f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "float"
    )

    var ttsInstance by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    val greetings = remember {
        listOf(
            "Welcome back, ${child.name}! Let's learn!",
            "Great to see you, ${child.name}! Ready to explore?",
            "Hi ${child.name}! You're going to do great today!",
            "Let's learn something new today, ${child.name}!"
        )
    }
    val greetingText = remember { greetings.random() }

    LaunchedEffect(Unit) {
        try {
            val notificationUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = android.media.RingtoneManager.getRingtone(context, notificationUri)
            ringtone.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        ttsInstance = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                ttsInstance?.setLanguage(java.util.Locale.getDefault())
                ttsInstance?.speak(greetingText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
        
        kotlinx.coroutines.delay(2800)
        onFinished()
    }

    DisposableEffect(Unit) {
        onDispose {
            ttsInstance?.stop()
            ttsInstance?.shutdown()
        }
    }

    val gradientColors = when (theme) {
        1 -> listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
        2 -> listOf(Color(0xFF11998E), Color(0xFF38EF7D))
        3 -> listOf(Color(0xFF00C6FF), Color(0xFF0072FF))
        else -> listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))
    }

    val primaryEmoji = when (theme) {
        1 -> "🚀"
        2 -> "🦁"
        3 -> "🐬"
        else -> "✨"
    }

    val themeTitle = when (theme) {
        1 -> "COSMIC LEARNING"
        2 -> "SAFARI ADVENTURE"
        3 -> "UNDER THE SEA"
        else -> "MAGIC SCHOOL"
    }

    val themeSubtitle = when (theme) {
        1 -> "Launching into knowledge..."
        2 -> "Exploring the wild world..."
        3 -> "Diving deep into lessons..."
        else -> "Unlocking magical facts..."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(gradientColors))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        when (theme) {
            1 -> {
                Box(Modifier.fillMaxSize()) {
                    val starPositions = remember {
                        List(15) {
                            Pair((0..100).random() / 100f, (0..100).random() / 100f)
                        }
                    }
                    starPositions.forEachIndexed { idx, pos ->
                        Text(
                            text = "⭐",
                            fontSize = (10..18).random().sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(
                                    x = (pos.first * 320).dp,
                                    y = (pos.second * 700 + floatOffset).dp
                                )
                                .scale(if (idx % 2 == 0) pulseScale else 1f)
                                .graphicsLayer(alpha = 0.5f)
                        )
                    }
                }
            }
            2 -> {
                Box(Modifier.fillMaxSize()) {
                    val leafPositions = remember {
                        List(12) {
                            Pair((0..100).random() / 100f, (0..100).random() / 100f)
                        }
                    }
                    leafPositions.forEach { pos ->
                        Text(
                            text = "🌿",
                            fontSize = 24.sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(
                                    x = (pos.first * 320).dp,
                                    y = (pos.second * 700 - floatOffset / 2).dp
                                )
                                .graphicsLayer(rotationZ = rotationAngle / 2, alpha = 0.4f)
                        )
                    }
                }
            }
            3 -> {
                Box(Modifier.fillMaxSize()) {
                    val bubblePositions = remember {
                        List(20) {
                            Pair((0..100).random() / 100f, (0..100).random() / 100f)
                        }
                    }
                    bubblePositions.forEach { pos ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(
                                    x = (pos.first * 320).dp,
                                    y = (pos.second * 700 + floatOffset * 1.5f).dp
                                )
                                .size((8..20).random().dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.25f))
                                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)), CircleShape)
                        )
                    }
                }
            }
            else -> {
                Box(Modifier.fillMaxSize()) {
                    val magicItems = listOf("🎉", "✨", "🎈", "🎨", "🌟")
                    val magicPositions = remember {
                        List(16) {
                            Triple((0..100).random() / 100f, (0..100).random() / 100f, magicItems.random())
                        }
                    }
                    magicPositions.forEach { pos ->
                        Text(
                            text = pos.third,
                            fontSize = 22.sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(
                                    x = (pos.first * 320).dp,
                                    y = (pos.second * 700 + floatOffset).dp
                                )
                                .graphicsLayer(rotationZ = rotationAngle, alpha = 0.6f)
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = primaryEmoji,
                fontSize = 110.sp,
                modifier = Modifier
                    .scale(pulseScale)
                    .graphicsLayer(rotationZ = if (theme == 1 || theme == 4) rotationAngle / 6 else 0f)
            )
            
            Spacer(Modifier.height(32.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.15f)
            ) {
                Text(
                    text = themeTitle,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Welcome back,",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = child.name,
                color = Color.White,
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = themeSubtitle,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                textAlign = TextAlign.Center
            )
        }
    }
}

