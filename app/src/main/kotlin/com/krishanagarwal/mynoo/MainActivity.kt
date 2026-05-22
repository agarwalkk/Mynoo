package com.krishanagarwal.mynoo

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.krishanagarwal.mynoo.data.model.ChildState
import com.krishanagarwal.mynoo.ui.navigation.MynooNavGraph
import com.krishanagarwal.mynoo.ui.theme.MynooTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen on during tutoring sessions (mirrors expo-keep-awake)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MynooTheme {
                // ChildState is hoisted here so every screen in the graph
                // can read/update the currently active child profile.
                var childState by remember { mutableStateOf(ChildState()) }
                MynooNavGraph(
                    childState  = childState,
                    onChildSet  = { childState = it },
                )
            }
        }
    }
}
