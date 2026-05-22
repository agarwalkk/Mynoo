package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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

private val AVATARS = listOf("🧒", "👦", "🧑", "👧", "🐯", "🦁", "🚀", "🌟", "🎯", "🦋", "🐬", "🌈")
private val CLASSES = listOf("6", "7", "8", "9", "10")

private fun avatarFor(name: String): String =
    AVATARS[(name.firstOrNull()?.code ?: 0) % AVATARS.size]

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChildSelectScreen(
    onChildSelected: (name: String, classNum: String) -> Unit,
    vm: ChildViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    var showAddSheet  by remember { mutableStateOf(false) }
    var deleteTarget  by remember { mutableStateOf<String?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick        = { showAddSheet = true },
                icon           = { Icon(Icons.Default.Add, contentDescription = null) },
                text           = { Text("Add learner") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor   = MaterialTheme.colorScheme.onPrimary,
            )
        },
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
                            text      = "No learners yet.\nTap + to add the first one.",
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
                ) {
                    items(ui.children, key = { it.name }) { child ->
                        ChildCard(
                            child       = child,
                            isLastUsed  = child.name == ui.lastChild,
                            onClick     = {
                                vm.saveLastChild(child.name, child.classNum)
                                onChildSelected(child.name, child.classNum)
                            },
                            onLongClick = { deleteTarget = child.name },
                        )
                    }
                }
            }

            ui.error?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(msg, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showAddSheet) {
        AddChildSheet(
            existing  = ui.children.map { it.name },
            onDismiss = { showAddSheet = false },
            onAdd     = { name, age, cls ->
                showAddSheet = false
                vm.addChild(name, age, cls) { child ->
                    vm.saveLastChild(child.name, child.classNum)
                    onChildSelected(child.name, child.classNum)
                }
            },
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddChildSheet(
    existing:  List<String>,
    onDismiss: () -> Unit,
    onAdd:     (name: String, age: String, classNum: String) -> Unit,
) {
    var name  by remember { mutableStateOf("") }
    var age   by remember { mutableStateOf("") }
    var cls   by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text(
                "Add a learner",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value         = name,
                onValueChange = { name = it; error = null },
                label         = { Text("Name *") },
                singleLine    = true,
                isError       = error != null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier      = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value           = age,
                onValueChange   = { age = it },
                label           = { Text("Age (optional)") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier        = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Text("Class", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CLASSES.forEach { c ->
                    FilterChip(
                        selected = cls == c,
                        onClick  = { cls = if (cls == c) "" else c },
                        label    = { Text(c) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick  = {
                    val t = name.trim()
                    when {
                        t.isBlank() ->
                            error = "Name is required"
                        !t.matches(Regex("^[A-Za-z][A-Za-z0-9 ]{0,29}$")) ->
                            error = "Must start with a letter (1–30 chars)"
                        existing.any { it.equals(t, ignoreCase = true) } ->
                            error = "$t already exists"
                        else -> onAdd(t, age.trim(), cls)
                    }
                },
            ) { Text("Add learner") }
        }
    }
}

