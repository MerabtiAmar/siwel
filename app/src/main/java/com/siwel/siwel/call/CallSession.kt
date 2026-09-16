package com.siwel.siwel.call

/**
 * État processus de l'appel courant. Sert à savoir si CE device est actuellement DANS un appel,
 * afin que la bannière "Rejoindre" (pilotée par Firebase) ne s'affiche pas pour l'appel auquel
 * on participe déjà. Renseigné par CallActivity (onCreate/onDestroy).
 */
object CallSession {
    @Volatile
    var inCallId: String? = null
}
