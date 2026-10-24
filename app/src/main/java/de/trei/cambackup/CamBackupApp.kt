package de.trei.cambackup

import android.app.Application
import android.content.Context
import de.trei.cambackup.util.AppLanguage
import de.trei.cambackup.util.L10n
import de.trei.cambackup.work.Notifications

class CamBackupApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        L10n.init(this)
        Notifications.createChannels(this)
    }
}
