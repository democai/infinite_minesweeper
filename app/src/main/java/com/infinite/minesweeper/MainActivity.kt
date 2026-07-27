package com.infinite.minesweeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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
fun AppRoot() {
    InfiniteMinesweeperTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // T0 placeholder: blank dark board surface.
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppRootPreview() {
    AppRoot()
}
