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
    private const val MINUTE = 60_000L

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

    /**
     * Programme le rappel. L'échéance déjà prévue est conservée : enregistrer
     * les réglages ou redémarrer le téléphone ne repousse pas le rappel. Elle
     * repart de zéro si l'intervalle change ; un rappel manqué téléphone éteint
     * arrive dans la minute.
     */
    fun programmer(contexte: Context, jours: Int) {
        val reglages = Reglages(contexte)
        val alarmes = contexte.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intervalle = jours.toLong() * AlarmManager.INTERVAL_DAY
        val maintenant = System.currentTimeMillis()
        val prevu = reglages.rappelProchain
        val prochain = when {
            prevu == 0L || reglages.rappelJoursProgrammes != jours -> maintenant + intervalle
            prevu <= maintenant -> maintenant + MINUTE
            else -> prevu
        }
        reglages.rappelProchain = prochain
        reglages.rappelJoursProgrammes = jours
        // Alarme inexacte : le système la regroupe avec d'autres pour préserver
        // la batterie. Un rappel de relevé n'a pas besoin d'être à la minute.
        alarmes.setInexactRepeating(AlarmManager.RTC, prochain, intervalle, intention(contexte))
    }

    /** Après un rappel : l'échéance suivante est un intervalle plus loin. */
    fun avancer(contexte: Context) {
        val reglages = Reglages(contexte)
        val intervalle = reglages.rappelJours.toLong() * AlarmManager.INTERVAL_DAY
        var prochain = reglages.rappelProchain
        val maintenant = System.currentTimeMillis()
        if (prochain == 0L) prochain = maintenant
        while (prochain <= maintenant) prochain += intervalle
        reglages.rappelProchain = prochain
    }

    fun annuler(contexte: Context) {
        val alarmes = contexte.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmes.cancel(intention(contexte))
        Reglages(contexte).rappelProchain = 0L
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
        Rappels.avancer(contexte)
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
