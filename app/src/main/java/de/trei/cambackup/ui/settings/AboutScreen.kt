package de.trei.cambackup.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.trei.cambackup.R
import de.trei.cambackup.storage.DeviceRegistry
import de.trei.cambackup.storage.DriveRepository
import de.trei.cambackup.storage.LocalIdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-Info: Version, Funktionsweise, Sicherheitsregeln, Gerät, Bibliotheken. */
@Composable
fun AboutScreen(onShowMetadata: () -> Unit) {
    val context = LocalContext.current
    val info = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    val identity = remember { LocalIdentityStore(context).get() }
    // Name und Ort des Backup-Ordners (nur, wenn das Medium gerade eingesteckt ist)
    val folder by produceState<Pair<String, String>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drives = DriveRepository(context)
                val dir = drives.openActive() ?: return@runCatching null
                val record = DeviceRegistry(context, DeviceRegistry.appVersion(context)).read(dir) ?: return@runCatching null
                val i = drives.info(dir.uri)
                record.displayName to (i.volumeLabel + if (i.path.isNotEmpty()) " / ${i.path}" else "")
            }.getOrNull()
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(24.dp))) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
        Text("CamBackup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Version ${info.versionName} (${info.longVersionCode})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Sichert Fotos und Videos in Originalqualität auf USB-Stick oder SD-Karte und schafft Platz auf dem Smartphone.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }

    SettingsSection("So funktioniert's") {
        Step(1, "Sichern", "Neue Fotos und Videos werden bitgenau in den Backup-Ordner auf dem Speichermedium kopiert.")
        Step(2, "Prüfen", "Jede Kopie wird zurückgelesen und per SHA-256-Prüfsumme mit dem Original verglichen.")
        Step(3, "Verkleinern", "Auf Wunsch ersetzt die App ältere Originale auf dem Smartphone durch kleinere Versionen.")
        Step(4, "Zurückholen", "Gelöschte oder verkleinerte Dateien lassen sich jederzeit in Originalqualität zurückholen.")
    }

    SettingsSection("Sicherheit") {
        Bullet("Ersetzt wird nur, was nachweislich bitgenau gesichert ist – und nur bei eingestecktem Medium.")
        Bullet("Android fragt vor jedem Ersetzen um deine Zustimmung; im Probelauf wird nie etwas ersetzt.")
        Bullet("Nach dem Ersetzen wird erneut geprüft; bei einem Fehler wird das Original zurückgespielt.")
        Bullet("Aufnahmedatum, Kameradaten und (wahlweise) der Standort bleiben erhalten.")
    }

    SettingsSection("Dieses Smartphone") {
        InfoRow("Gerät", "${identity.manufacturer} ${identity.model}")
        InfoRow("Name im Backup", folder?.first ?: "–")
        InfoRow("Backup-Ordner", folder?.second ?: "Speichermedium nicht verbunden")
        InfoRow("Kennung", identity.deviceUuid.take(8))
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) { Text("Berechtigungen verwalten") }
    }

    SettingsSection("Open-Source-Bibliotheken", "Alle unter Apache License 2.0") {
        listOf(
            "Jetpack Compose & Material 3" to "Oberfläche",
            "AndroidX Media3 Transformer" to "Videos verkleinern",
            "AndroidX WorkManager" to "Arbeit im Hintergrund",
            "AndroidX ExifInterface" to "Foto-Metadaten",
            "AndroidX DataStore" to "Einstellungen",
            "AndroidX DocumentFile" to "Zugriff auf das Speichermedium",
            "Kotlin Coroutines & Serialization" to "Abläufe und Metadaten",
        ).forEach { (name, use) -> InfoRow(name, use) }
    }

    Text(
        "Paket ${context.packageName}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    // Unauffälliger Zugang zur technischen Ansicht
    TextButton(onClick = onShowMetadata, modifier = Modifier.fillMaxWidth()) {
        Text("Metadaten anzeigen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Step(number: Int, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(Modifier.size(28.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Hint(text)
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(R.drawable.ic_shield), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.4f))
    }
}
