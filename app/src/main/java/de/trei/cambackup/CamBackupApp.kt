package de.trei.cambackup

import android.app.Application
import de.trei.cambackup.work.Notifications

class CamBackupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
