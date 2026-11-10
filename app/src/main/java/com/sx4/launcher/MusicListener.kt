package com.sx4.launcher

import android.app.Notification
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Datos de cancion que llegan por rutas distintas a MediaSession. */
object MusicBridge {
    @Volatile var nTitle: String? = null
    @Volatile var nText: String? = null
    @Volatile var nPkg: String? = null
    @Volatile var nArt: Bitmap? = null

    @Volatile var bTitle: String? = null
    @Volatile var bArtist: String? = null
    @Volatile var bPlaying: Boolean? = null
    @Volatile var bAction: String? = null

    val recent = LinkedHashMap<String, String>()
}

/**
 * Ademas de dar permiso de "Acceso a notificaciones", lee las notificaciones
 * de la app de musica: muchas apps muestran ahi el titulo aunque no usen MediaSession.
 */
class MusicListener : NotificationListenerService() {

    override fun onListenerConnected() {
        try {
            val all = activeNotifications
            if (all != null) for (s in all) handle(s)
        } catch (e: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && sbn.packageName == MusicBridge.nPkg) {
            MusicBridge.nTitle = null
            MusicBridge.nText = null
            MusicBridge.nArt = null
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val pkg = sbn.packageName

        synchronized(MusicBridge.recent) {
            MusicBridge.recent.remove(pkg)
            MusicBridge.recent[pkg] = (title ?: "") + " | " + (text ?: "")
            while (MusicBridge.recent.size > 12) {
                MusicBridge.recent.remove(MusicBridge.recent.keys.first())
            }
        }

        val musicPkg = getSharedPreferences("sx4", MODE_PRIVATE).getString("music_pkg", null)
        val isMusic = pkg == musicPkg ||
            n.category == Notification.CATEGORY_TRANSPORT ||
            ex.containsKey(Notification.EXTRA_MEDIA_SESSION)
        if (isMusic && !title.isNullOrBlank()) {
            MusicBridge.nTitle = title
            MusicBridge.nText = text
            MusicBridge.nPkg = pkg
            try {
                MusicBridge.nArt = ex.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            } catch (e: Exception) {
            }
        }
    }
}
