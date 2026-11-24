package com.scaletape

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val (opened, lost) = openStore(applicationContext)
        setContent {
            AppTheme {
                var store by remember { mutableStateOf(opened) }
                var notice by remember { mutableStateOf(lost) }
                var openId by rememberSaveable { mutableStateOf<String?>(null) }
                notice?.let {
                    AlertDialog(
                        onDismissRequest = { notice = null },
                        confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
                        text = { Text(it) },
                    )
                }
                when (val id = openId) {
                    null -> HomeScreen(store, onStore = { store = it }, onOpen = { openId = it })
                    else -> key(id) {
                        val job = remember { store.load(id) }
                        if (job == null) LaunchedEffect(Unit) { openId = null } // deleted or unreadable
                        else JobScreen(store, job, onBack = { openId = null })
                    }
                }
            }
        }
    }
}

/** Material 3, with Material You dynamic color on Android 12+. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
