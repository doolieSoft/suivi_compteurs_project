package be.suivicompteurs.app

import android.annotation.SuppressLint
import android.view.Menu
import androidx.appcompat.view.menu.MenuBuilder

/**
 * Affiche les icônes des entrées du menu « ⋮ », qu'Android masque par défaut
 * dans ce menu déroulant.
 */
@SuppressLint("RestrictedApi")
fun Menu.afficherIcones() {
    (this as? MenuBuilder)?.setOptionalIconsVisible(true)
}
