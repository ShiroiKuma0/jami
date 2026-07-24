/*
 *  shiroikuma fork — programmatic Firebase init for the dual-backend push flavor.
 *
 *  We do NOT ship a google-services.json (the gradle google-services plugin is not applied to this
 *  flavor). Instead FirebaseApp is initialized in code with GNU Jami's own public project options,
 *  read out of the official Play-Store `cx.ring` APK (res/values strings gcm_defaultSenderId /
 *  google_app_id / google_api_key / project_id). These are not secrets — an app id / sender id /
 *  browser API key are public client identifiers embedded in every distributed APK. Using Jami's
 *  own project means the dhtproxy servers (which hold the matching server key) can push to our
 *  tokens exactly as they do for the stock app.
 *
 *  microG stands in for Google Play services on the Mate XT; token issuance for our
 *  shiroikuma.jami package under Jami's project is validated by the on-device test window.
 */
package cx.ring.application

import com.google.firebase.FirebaseOptions

object JamiFirebaseConfig {
    // GNU Jami's public Firebase project (extracted from the official cx.ring release, 2026-07-24).
    private const val APP_ID = "1:46459832812:android:e3ff7f02d31c42d7"
    private const val SENDER_ID = "46459832812"
    private const val API_KEY = "AIzaSyA1yIHNA5jNd6RkGQngEwzDSh91lUQJSR0"
    private const val PROJECT_ID = "gnu-ring"

    val options: FirebaseOptions = FirebaseOptions.Builder()
        .setApplicationId(APP_ID)
        .setGcmSenderId(SENDER_ID)
        .setApiKey(API_KEY)
        .setProjectId(PROJECT_ID)
        .build()
}
