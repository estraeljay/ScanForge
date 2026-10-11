package app.scanforge.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.scanforge.android.ui.ScanForgeNavHost
import app.scanforge.android.ui.theme.ScanForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScanForgeTheme { ScanForgeNavHost() } }
    }
}
