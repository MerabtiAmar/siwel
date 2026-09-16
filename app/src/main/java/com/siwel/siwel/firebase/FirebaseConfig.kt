package com.siwel.siwel.firebase

import com.siwel.siwel.BuildConfig

/** Configuration Firebase centralisée (URL lue dans local.properties : siwel.firebase.url). */
object FirebaseConfig {
    val DATABASE_URL: String = BuildConfig.FIREBASE_DATABASE_URL
}
