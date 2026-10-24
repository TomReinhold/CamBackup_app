package de.trei.cambackup.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MediaAccess {
    /** Zugriff auf alle Fotos und Videos – nötig für ein vollständiges Backup. */
    FULL,
    /** Android 14+: Nutzer hat nur ausgewählte Fotos freigegeben. */
    PARTIAL,
    NONE,
}

object MediaPermissions {

    /** Berechtigungen, die beim Nutzer angefragt werden. */
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun currentAccess(context: Context): MediaAccess {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

        if (required.all(::granted)) return MediaAccess.FULL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        ) {
            return MediaAccess.PARTIAL
        }
        return MediaAccess.NONE
    }
}
