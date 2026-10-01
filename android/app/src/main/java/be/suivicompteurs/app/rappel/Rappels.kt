package be.suivicompteurs.app.rappel

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import be.suivicompteurs.app.MainActivity
import be.suivicompteurs.app.R
import be.suivicompteurs.app.Reglages

/**
 * Rappel périodique de relevé.
 *
 * Programmé localement par [AlarmManager] : aucun serveur de notifications
 * n'est impliqué, le rappel fonctionne donc même si le PC ne se rallume jamais.
 */
object Rappels {

    const val CANAL = "rappels-releve"
    private const val CODE = 1001

    fun creerCanal(contexte: Context) {
        val canal = NotificationChannel(
            CANAL,
            contexte.getString(R.string.canal_rappels),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = contexte.getString(R.string.canal_rappels_description)
        }
        NotificationManagerCompat.from(contexte).createNotificationChannel(canal)
    }

    fun appliquer(contexte: Context) {
        val reglages = Reglages(contexte)
        if (reglages.rappelActif) programmer(contexte, reglages.rappelJours)
        else annuler(contexte)
    }

    private fun intention(contexte: Context): PendingIntent =
        PendingIntent.getBroadcast(
            contexte,
            CODE,
            Intent(contexte, RappelReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun programmer(contexte: Context, jours: Int) {
        val alarmes = contexte.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intervalle = jours.toLong() * AlarmManager.INTERVAL_DAY
        // Alarme inexacte : le système la regroupe avec d'autres pour préserver
        // la batterie. Un rappel de relevé n'a pas besoin d'être à la minute.
        alarmes.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + intervalle,
            intervalle,
            intention(contexte),
        )
    }

    fun annuler(contexte: Context) {
        val alarmes = contexte.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmes.cancel(intention(contexte))
    }

    fun notifier(contexte: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val accorde = ContextCompat.checkSelfPermission(
                contexte,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!accorde) return
        }

        val ouvrir = PendingIntent.getActivity(
            contexte,
            0,
            Intent(contexte, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(contexte, CANAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contexte.getString(R.string.rappel_titre))
            .setContentText(contexte.getString(R.string.rappel_texte))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(ouvrir)
            .build()

        NotificationManagerCompat.from(contexte).notify(CODE, notification)
    }
}

class RappelReceiver : BroadcastReceiver() {
    override fun onReceive(contexte: Context, intent: Intent) {
        Rappels.notifier(contexte)
    }
}

/** Les alarmes ne survivent pas au redémarrage : il faut les reposer. */
class DemarrageReceiver : BroadcastReceiver() {
    override fun onReceive(contexte: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Rappels.appliquer(contexte)
        }
    }
}
