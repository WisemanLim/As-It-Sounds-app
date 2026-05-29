package com.asitsounds.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.asitsounds.android.ui.AsItSoundsTheme
import com.asitsounds.android.ui.RootScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme by AppSettings.get(this).colorScheme.collectAsState()
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = when (scheme) {
                ColorSchemePref.SYSTEM -> systemDark
                ColorSchemePref.DARK -> true
                ColorSchemePref.LIGHT -> false
            }
            AsItSoundsTheme(darkTheme = dark) {
                RootScreen()
            }
        }
    }
}
