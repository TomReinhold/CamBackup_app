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
import androidx.compose.ui.res.stringResource
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
            stringResource(R.string.about_version, info.versionName.orEmpty(), info.longVersionCode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }

    SettingsSection(stringResource(R.string.about_how)) {
        Step(1, stringResource(R.string.about_step1_title), stringResource(R.string.about_step1))
        Step(2, stringResource(R.string.about_step2_title), stringResource(R.string.about_step2))
        Step(3, stringResource(R.string.about_step3_title), stringResource(R.string.about_step3))
        Step(4, stringResource(R.string.about_step4_title), stringResource(R.string.about_step4))
    }

    SettingsSection(stringResource(R.string.about_safety)) {
        Bullet(stringResource(R.string.about_safety1))
        Bullet(stringResource(R.string.about_safety2))
        Bullet(stringResource(R.string.about_safety3))
        Bullet(stringResource(R.string.about_safety4))
    }

    SettingsSection(stringResource(R.string.about_this_phone)) {
        InfoRow(stringResource(R.string.about_device), "${identity.manufacturer} ${identity.model}")
        InfoRow(stringResource(R.string.about_name), folder?.first ?: "–")
        InfoRow(stringResource(R.string.storage_preview_root), folder?.second ?: stringResource(R.string.about_not_connected))
        InfoRow(stringResource(R.string.about_id), identity.deviceUuid.take(8))
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) { Text(stringResource(R.string.about_permissions)) }
    }

    SettingsSection(stringResource(R.string.about_libraries), stringResource(R.string.about_libraries_license)) {
        listOf(
            "Jetpack Compose & Material 3" to stringResource(R.string.about_lib_ui),
            "AndroidX Media3 Transformer" to stringResource(R.string.about_lib_video),
            "AndroidX WorkManager" to stringResource(R.string.about_lib_work),
            "AndroidX ExifInterface" to stringResource(R.string.about_lib_exif),
            "AndroidX DataStore" to stringResource(R.string.tab_settings),
            "AndroidX DocumentFile" to stringResource(R.string.about_lib_saf),
            "Kotlin Coroutines & Serialization" to stringResource(R.string.about_lib_kotlin),
        ).forEach { (name, use) -> InfoRow(name, use) }
    }

    Text(
        stringResource(R.string.about_package, context.packageName),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    // Unauffälliger Zugang zur technischen Ansicht
    TextButton(onClick = onShowMetadata, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.about_show_metadata), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
