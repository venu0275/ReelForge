package com.reelforge.editor.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.reelforge.editor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a bug report is made of. Plain data, so the text can be tested. */
data class BugReportText(
    val problem: String,
    val steps: String,
    val device: String?,
) {
    val subject: String get() = "CutMitra bug: " + problem.lineSequence().first().take(60)

    val body: String
        get() = buildString {
            appendLine("What went wrong:")
            appendLine(problem.trim())
            if (steps.isNotBlank()) {
                appendLine()
                appendLine("How to make it happen:")
                appendLine(steps.trim())
            }
            if (device != null) {
                appendLine()
                appendLine("Phone and app:")
                appendLine(device)
            }
        }
}

object BugReporter {
    /** The phone and app details that help find a bug. Nothing personal. */
    fun deviceInfo(context: Context, appVersion: String): String {
        val free = runCatching {
            StatFs(Environment.getDataDirectory().path).availableBytes / (1024 * 1024)
        }.getOrDefault(-1)
        val metrics = context.resources.displayMetrics
        return listOf(
            "App: CutMitra $appVersion",
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Screen: ${metrics.widthPixels}×${metrics.heightPixels}",
            "Free space: $free MB",
            "Memory for the app: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB",
        ).joinToString("\n")
    }

    /** The app's own recent log lines (Android only lets an app read its own). */
    fun appLog(): String = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-t", "400", "--pid=${android.os.Process.myPid()}")
        )
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private fun folder(context: Context) = File(context.cacheDir, "reports").apply {
        deleteRecursively()
        mkdirs()
    }

    /**
     * Builds the message: to the support address if one is set up (else any app can be picked),
     * with screenshots and the log attached as copies in the app's cache. Slow; call off the
     * main thread.
     */
    fun prepare(context: Context, report: BugReportText, screenshots: List<Uri>, includeLog: Boolean): Intent {
        val dir = folder(context)
        val authority = "${context.packageName}.reports"
        val files = mutableListOf<Uri>()
        screenshots.forEachIndexed { i, uri ->
            runCatching {
                val out = File(dir, "screenshot_${i + 1}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                if (out.length() > 0) files += FileProvider.getUriForFile(context, authority, out)
            }
        }
        if (includeLog) {
            val log = appLog()
            if (log.isNotBlank()) {
                val out = File(dir, "app_log.txt").apply { writeText(log) }
                files += FileProvider.getUriForFile(context, authority, out)
            }
        }

        val email = BuildConfig.SUPPORT_EMAIL
        val send = Intent(if (files.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = if (files.isEmpty()) "text/plain" else "*/*"
            if (email.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, report.subject)
            putExtra(Intent.EXTRA_TEXT, report.body)
            when {
                files.size > 1 -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
                files.size == 1 -> putExtra(Intent.EXTRA_STREAM, files[0])
            }
            if (files.isNotEmpty()) {
                clipData = ClipData.newRawUri("", files[0]).apply { files.drop(1).forEach { addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // With an address set, offer only email apps.
            if (email.isNotBlank()) selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
        return send
    }

    /** Opens the email app (or share sheet) with a message from [prepare]. Main thread. */
    fun open(context: Context, send: Intent) {
        try {
            context.startActivity(Intent.createChooser(send, "Send bug report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // No email app: try again with any app that can share text.
            send.selector = null
            runCatching {
                context.startActivity(Intent.createChooser(send, "Send bug report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** Settings → Report a bug. */
@Composable
fun BugReportScreen(appVersion: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    var withDevice by remember { mutableStateOf(true) }
    var withLog by remember { mutableStateOf(true) }
    var shots by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        if (uris.isNotEmpty()) shots = uris
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Report a bug", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Tell us what went wrong. Your report opens in your email app so you can check it before sending.")
        OutlinedTextField(
            problem, { problem = it },
            label = { Text("What went wrong?") },
            placeholder = { Text("e.g. The app closed when I tapped Export") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
        )
        OutlinedTextField(
            steps, { steps = it },
            label = { Text("How can we make it happen? (optional)") },
            placeholder = { Text("1. Open a project  2. Add a video  3. Tap Export") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (shots.isEmpty()) "No screenshots added" else "${shots.size} screenshot(s) added",
                Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (shots.isEmpty()) "Add screenshots" else "Change") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include phone model and Android version", Modifier.weight(1f))
            Switch(checked = withDevice, onCheckedChange = { withDevice = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include the app's recent log", Modifier.weight(1f))
            Switch(checked = withLog, onCheckedChange = { withLog = it })
        }
        Text(
            "The log only has the app's own technical messages, which help find crashes. Nothing is " +
                "sent until you press Send in your email app.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                sending = true
                scope.launch {
                    val report = BugReportText(
                        problem = problem,
                        steps = steps,
                        device = if (withDevice) BugReporter.deviceInfo(context, appVersion) else null,
                    )
                    // Copying screenshots and reading the log is done off the main thread.
                    val message = withContext(Dispatchers.IO) { BugReporter.prepare(context, report, shots, withLog) }
                    BugReporter.open(context, message)
                    sending = false
                }
            },
            enabled = problem.isNotBlank() && !sending,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (sending) "Preparing…" else "Send report") }
    }
}
