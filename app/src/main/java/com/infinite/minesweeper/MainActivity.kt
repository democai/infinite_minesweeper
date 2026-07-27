package com.infinite.minesweeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.infinite.minesweeper.ui.game.GameScreen
import com.infinite.minesweeper.ui.game.GameViewModel
import com.infinite.minesweeper.ui.theme.InfiniteMinesweeperTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
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
