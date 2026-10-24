package de.trei.cambackup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.library.LibraryViewModel
import de.trei.cambackup.ui.OverviewScreen
import de.trei.cambackup.ui.gallery.GalleryScreen
import de.trei.cambackup.ui.settings.SettingsScreen
import de.trei.cambackup.ui.theme.CamBackupTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CamBackupTheme {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    MainScaffold(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: Int) {
    OVERVIEW("Übersicht", R.drawable.ic_dashboard),
    PHOTOS("Fotos", R.drawable.ic_photo_library),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(onOpenSettings: () -> Unit) {
    val library: LibraryViewModel = viewModel()
    var tab by rememberSaveable { mutableStateOf(Tab.OVERVIEW) }

    // Medienordner bei jeder Rückkehr in die App neu einlesen
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { library.refresh() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (tab == Tab.OVERVIEW) "CamBackup" else "Fotos") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Einstellungen") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(painterResource(t.icon), null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.OVERVIEW -> OverviewScreen(library, padding)
            Tab.PHOTOS -> GalleryScreen(library, padding)
        }
    }
}
