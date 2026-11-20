package com.reelforge.editor.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Each time the app is opened (or comes back to the front), asks the Play Store whether a newer
 * version is published. If so, shows the app's own "Update available" box. "Update now" installs
 * it from inside the app through Play; "Later" hides it until the app is next opened.
 *
 * This only works for copies installed from the Play Store. Copies installed from Android Studio
 * never see an update, so nothing is shown while developing.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val manager = remember { AppUpdateManagerFactory.create(context) }
    var info by remember { mutableStateOf<AppUpdateInfo?>(null) }
    // Hidden with "Later" until the app is next started.
    var later by rememberSaveable { mutableStateOf(false) }

    // Shown under the buttons when the update could not start or failed.
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        busy = false
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> Unit
            // Closed by the user: the box stays up so they can try again.
            android.app.Activity.RESULT_CANCELED -> problem = null
            // Play could not install it (no space, no connection, Play busy): offer the store page.
            else -> {
                problem = "The update could not be installed here. Opening the Play Store instead."
                openStoreListing(context)
            }
        }
    }

    // A flexible update downloads in the background; once it is downloaded it is installed
    // (the app restarts into the new version).
    val installListener = remember {
        com.google.android.play.core.install.InstallStateUpdatedListener { state ->
            when (state.installStatus()) {
                com.google.android.play.core.install.model.InstallStatus.DOWNLOADED -> manager.completeUpdate()
                com.google.android.play.core.install.model.InstallStatus.FAILED -> {
                    busy = false
                    problem = "The download failed. Opening the Play Store instead."
                    openStoreListing(context)
                }
                else -> Unit
            }
        }
    }
    DisposableEffect(manager) {
        manager.registerListener(installListener)
        onDispose { manager.unregisterListener(installListener) }
    }

    /** Starts the update from fresh details (Play allows each set of details to be used once). */
    fun startUpdate() {
        busy = true
        problem = null
        manager.appUpdateInfo
            .addOnSuccessListener { fresh ->
                val type = when {
                    fresh.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                        AppUpdateType.IMMEDIATE
                    fresh.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE -> null
                    fresh.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                    fresh.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                    else -> null
                }
                val started = type != null && runCatching {
                    manager.startUpdateFlowForResult(fresh, launcher, AppUpdateOptions.newBuilder(type).build())
                }.getOrDefault(false)
                if (!started) {
                    busy = false
                    openStoreListing(context)
                }
            }
            .addOnFailureListener {
                busy = false
                openStoreListing(context)
            }
    }

    /** Carries on with an update that is already downloaded or installing. */
    fun resume(update: AppUpdateInfo) {
        if (update.installStatus() == com.google.android.play.core.install.model.InstallStatus.DOWNLOADED) {
            manager.completeUpdate()
        } else {
            runCatching {
                manager.startUpdateFlowForResult(
                    update, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                )
            }
        }
    }

    // Checked whenever the app comes to the front, which includes opening it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            manager.appUpdateInfo.addOnSuccessListener { update ->
                when (update.updateAvailability()) {
                    UpdateAvailability.UPDATE_AVAILABLE -> {
                        info = update
                        // Downloaded earlier in the background but not installed yet.
                        if (update.installStatus() == com.google.android.play.core.install.model.InstallStatus.DOWNLOADED) {
                            manager.completeUpdate()
                        }
                    }
                    // An update the user started earlier is still installing: carry on with it.
                    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                        info = update
                        resume(update)
                    }
                    else -> info = null
                }
            }
            // No Play Store, no internet and so on: just carry on without the box.
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (info == null || later) return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("⬆", fontSize = 30.sp, color = MaterialTheme.colorScheme.onPrimary) }
                Text(
                    "Update available",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "A new version of CutMitra is ready with new features and fixes. " +
                        "Update now to keep using the latest version.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { startUpdate() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Starting…" else "Update now")
                }
                problem?.let {
                    Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { later = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Later")
                }
            }
        }
    }
}

/** Opens the app's page in the Play Store (or in the browser if there is no Play Store). */
fun openStoreListing(context: Context) {
    val id = context.packageName
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
