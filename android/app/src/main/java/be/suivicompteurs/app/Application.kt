package be.suivicompteurs.app

import android.app.Application
import be.suivicompteurs.app.rappel.Rappels
import be.suivicompteurs.app.sync.SyncWorker

class Application : Application() {
    override fun onCreate() {
        super.onCreate()
        Rappels.creerCanal(this)
        Rappels.appliquer(this)
        // La météo (degrés-jours) se met à jour d'elle-même chaque jour.
        SyncWorker.programmer(this)
    }
}
