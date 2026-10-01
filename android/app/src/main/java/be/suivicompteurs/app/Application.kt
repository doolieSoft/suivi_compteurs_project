package be.suivicompteurs.app

import android.app.Application
import be.suivicompteurs.app.rappel.Rappels
import be.suivicompteurs.app.sync.SyncWorker

class Application : Application() {
    override fun onCreate() {
        super.onCreate()
        Rappels.creerCanal(this)
        Rappels.appliquer(this)
        // Rattrape les relevés restés en attente si l'application a été fermée
        // avant le retour du réseau.
        SyncWorker.programmer(this)
    }
}
