package de.trei.cambackup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import de.trei.cambackup.ui.DriveViewModel
import de.trei.cambackup.ui.MediaChangeListener
import de.trei.cambackup.ui.OverviewScreen
import de.trei.cambackup.ui.gallery.GalleryScreen
import de.trei.cambackup.ui.settings.InfoScreen
import de.trei.cambackup.ui.settings.SettingsScreen
import de.trei.cambackup.ui.theme.CamBackupTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CamBackupTheme { MainScaffold() }
        }
    }
}

private enum class Tab(val label: String, val icon: Int) {
    OVERVIEW("Übersicht", R.drawable.ic_dashboard),
    GALLERY("Galerie", R.drawable.ic_photo_library),
    SETTINGS("Einstellungen", R.drawable.ic_settings),
    INFO("Info", R.drawable.ic_info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold() {
    val library: LibraryViewModel = viewModel()
    val drive: DriveViewModel = viewModel()
    var tab by rememberSaveable { mutableStateOf(Tab.OVERVIEW) }

    // Medienordner und Speichermedium bei jeder Rückkehr in die App neu prüfen – und sofort, wenn ein
    // Datenträger ein- oder ausgesteckt wird (der Sicherungsstatus hängt davon ab, auf jedem Tab)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        library.refresh()
        drive.refresh()
    }
    MediaChangeListener(onChange = drive::refresh)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            // Einstellungen und Info haben eine eigene Titelleiste (mit Zurück auf Unterseiten)
            if (tab != Tab.SETTINGS && tab != Tab.INFO) CenterAlignedTopAppBar(title = { Text(tab.label) })
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
            Tab.GALLERY -> GalleryScreen(library, padding)
            Tab.SETTINGS -> SettingsScreen(bottomPadding = padding.calculateBottomPadding())
            Tab.INFO -> InfoScreen(bottomPadding = padding.calculateBottomPadding())
        }
    }
}
