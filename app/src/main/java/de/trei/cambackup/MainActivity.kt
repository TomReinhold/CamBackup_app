package de.trei.cambackup

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.requiredSize
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.trei.cambackup.ui.components.HazardStripe
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.library.LibraryViewModel
import de.trei.cambackup.ui.DriveState
import de.trei.cambackup.ui.DriveViewModel
import de.trei.cambackup.ui.MediaChangeListener
import de.trei.cambackup.ui.OverviewScreen
import de.trei.cambackup.ui.gallery.GalleryScreen
import de.trei.cambackup.ui.settings.InfoScreen
import de.trei.cambackup.ui.settings.SettingsScreen
import de.trei.cambackup.ui.theme.CamBackupTheme

class MainActivity : ComponentActivity() {
    // Vor Android 13 setzt die App die gewählte Sprache selbst (ab 13 übernimmt das System)
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(de.trei.cambackup.util.AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CamBackupTheme { MainScaffold() }
        }
    }
}

/** Kleines Schild oben rechts: ist der Stick verbunden? */
@Composable
private fun DriveChip(connected: Boolean) {
    Text(
        stringResource(if (connected) R.string.chip_drive_connected else R.string.chip_drive_missing),
        style = MaterialTheme.typography.labelMedium,
        color = if (connected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(if (connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private enum class Tab(val label: Int, val icon: Int) {
    OVERVIEW(R.string.tab_overview, R.drawable.ic_dashboard),
    GALLERY(R.string.tab_gallery, R.drawable.ic_photo_library),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings),
    INFO(R.string.tab_info, R.drawable.ic_info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold() {
    val library: LibraryViewModel = viewModel()
    val drive: DriveViewModel = viewModel()
    val driveState by drive.state.collectAsStateWithLifecycle()
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
            // Warnstreifen ganz oben auf jedem Tab; Einstellungen und Info haben darunter eine eigene
            // Titelleiste (mit Zurück auf Unterseiten)
            // Deckender Hintergrund auch hinter der Statusleiste, sonst scheinen beim Scrollen die Karten durch
            Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                HazardStripe()
                when (tab) {
                    Tab.OVERVIEW -> TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppLogo()
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                            }
                        },
                        actions = { DriveChip(driveState is DriveState.Ready) },
                        windowInsets = WindowInsets(0),
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                    Tab.GALLERY -> CenterAlignedTopAppBar(
                        title = { Text(stringResource(tab.label)) },
                        windowInsets = WindowInsets(0),
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                    Tab.SETTINGS, Tab.INFO -> Unit
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(painterResource(t.icon), null) },
                        label = { Text(stringResource(t.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.OVERVIEW -> OverviewScreen(library, padding)
            Tab.GALLERY -> GalleryScreen(library, padding, folderUri = (driveState as? DriveState.Ready)?.info?.folderUri?.toString())
            Tab.SETTINGS -> Box(Modifier.padding(top = padding.calculateTopPadding())) {
                SettingsScreen(bottomPadding = padding.calculateBottomPadding())
            }
            Tab.INFO -> Box(Modifier.padding(top = padding.calculateTopPadding())) {
                InfoScreen(bottomPadding = padding.calculateBottomPadding())
            }
        }
    }
}

/** Das App-Symbol im Kopf: beide Ebenen des adaptiven Icons übereinander, auf den sichtbaren Bereich (72 von 108 dp) zugeschnitten. */
@Composable
private fun AppLogo(size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(size * 108 / 72)
        Image(painterResource(R.drawable.ic_launcher_background), null, layer)
        Image(painterResource(R.drawable.ic_launcher_foreground), null, layer)
    }
}
