package de.trei.cambackup.storage

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/** Erzeugt bzw. liest die lokale Identität dieses Smartphones. */
class LocalIdentityStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    @SuppressLint("HardwareIds") // ANDROID_ID dient nur der Wiedererkennung auf dem eigenen Speichermedium
    fun get(): LocalIdentity {
        val uuid = prefs.getString(KEY_UUID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_UUID, it).apply()
        }
        return LocalIdentity(
            deviceUuid = uuid,
            androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
        )
    }

    private companion object {
        const val KEY_UUID = "device_uuid"
    }
}
