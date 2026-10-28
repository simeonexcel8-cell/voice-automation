package com.voiceauto.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Asked on the first mic tap, never on launch. Typed commands work without it.
    @SuppressLint("MissingPermission")
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onMicTapped() else viewModel.onTextOnlyMode()
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    onMic = {
                        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) viewModel.onMicTapped() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSend = viewModel::onTextSubmitted,
                    onConfirm = viewModel::onConfirm,
                    onCancel = viewModel::onCancel,
                    onUndo = viewModel::onUndo,
                    onSpeakReplies = viewModel::onSpeakRepliesChanged,
                )
            }
        }
    }
}
