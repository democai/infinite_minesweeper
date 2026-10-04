package com.infinite.minesweeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.infinite.minesweeper.ui.game.GameScreen
import com.infinite.minesweeper.ui.game.GameViewModel
import com.infinite.minesweeper.ui.theme.InfiniteMinesweeperTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hold the splash until the board's first hydrated frame so the app never shows an empty map.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !viewModel.boardReady.value }
        enableEdgeToEdge()
        setContent {
            AppRoot(viewModel)
        }
    }
}

@Composable
fun AppRoot(viewModel: GameViewModel = hiltViewModel()) {
    InfiniteMinesweeperTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GameScreen(viewModel = viewModel)
        }
    }
}
