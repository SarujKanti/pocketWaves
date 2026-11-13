package com.skd.pocketwaves

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Checks Google Play for a newer version of the app every time the screen comes
 * to the foreground and walks the user through installing it.
 *
 *  - Normal updates are FLEXIBLE: they download in the background while the app
 *    keeps working, then a "Restart" snackbar installs them.
 *  - Updates marked high priority (4 or 5, set per release in Play Console under
 *    "Update priority") are IMMEDIATE: a full-screen flow the user can't skip.
 *
 * Google does not allow an app to silently replace itself; this is the
 * supported way to get users onto the latest version quickly. It only does
 * anything for builds installed from Google Play (sideloaded/debug builds are
 * ignored), and each new release needs a higher versionCode than the last.
 *
 * Create it in Activity.onCreate (the result launcher must be registered before
 * the Activity is STARTED).
 */
class InAppUpdateManager(
    private val activity: ComponentActivity,
    private val snackbarRoot: () -> View,
    private val snackbarAnchor: () -> View?
) : DefaultLifecycleObserver {

    private val updateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    private val updateLauncher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            // Nothing to do: if the user declined or cancelled, we simply ask again
            // the next time the app is opened (onResume).
        }

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) showRestartPrompt()
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        updateManager.registerListener(installListener)
        checkForUpdate()
    }

    override fun onPause(owner: LifecycleOwner) {
        updateManager.unregisterListener(installListener)
    }

    private fun checkForUpdate() {
        updateManager.appUpdateInfo
            .addOnSuccessListener { info -> handle(info) }
            // Not installed from Play / Play unavailable / offline: nothing to do.
            .addOnFailureListener { }
    }

    private fun handle(info: AppUpdateInfo) {
        // A flexible update was already downloaded earlier but not installed yet.
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            showRestartPrompt()
            return
        }
        // An immediate update was interrupted (app closed mid-update): resume it.
        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            start(info, AppUpdateType.IMMEDIATE)
            return
        }
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return

        val wantsImmediate = info.updatePriority() >= IMMEDIATE_PRIORITY &&
            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        when {
            wantsImmediate -> start(info, AppUpdateType.IMMEDIATE)
            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> start(info, AppUpdateType.FLEXIBLE)
            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> start(info, AppUpdateType.IMMEDIATE)
        }
    }

    private fun start(info: AppUpdateInfo, type: Int) {
        try {
            updateManager.startUpdateFlowForResult(
                info,
                updateLauncher,
                AppUpdateOptions.newBuilder(type).build()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showRestartPrompt() {
        Snackbar.make(snackbarRoot(), "A new version has been downloaded.", Snackbar.LENGTH_INDEFINITE)
            .setAction("Restart") { updateManager.completeUpdate() }
            .also { bar -> snackbarAnchor()?.takeIf { it.visibility == View.VISIBLE }?.let { bar.anchorView = it } }
            .show()
    }

    private companion object {
        const val IMMEDIATE_PRIORITY = 4
    }
}
