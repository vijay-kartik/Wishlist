package com.example.app.wishlist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import com.example.app.wishlist.ui.debug.DebugScreen
import com.example.app.wishlist.ui.debug.Dbg
import com.example.app.wishlist.ui.theme.WishlistTheme

/**
 * Launches straight into the debug inspector.
 *
 * Intentional for now: there is no product UI yet, and the pipeline is what needs
 * exercising. When real screens land, this becomes a nav host and the inspector moves
 * behind a debug-only entry point.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WishlistTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                    color = Dbg.AppBg,
                ) {
                    DebugScreen()
                }
            }
        }
    }
}
