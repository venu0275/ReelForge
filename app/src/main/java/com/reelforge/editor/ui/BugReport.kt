package com.reelforge.editor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.reelforge.editor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a bug report is made of. Plain data, so the text can be tested. */
data class BugReportText(
    val title: String,
    val problem: String,
    val steps: String,
    val device: String?,
) {
    /** "CutMitra – <issue title>" (the title is cut to 60 characters). */
    val subject: String
        get() = "CutMitra – " + title.trim().lineSequence().first().take(60).ifBlank { "Bug report" }

    val body: String
        get() = buildString {
            appendLine("Issue: ${title.trim()}")
            if (problem.isNotBlank()) {
                appendLine()
                appendLine("What went wrong:")
                appendLine(problem.trim())
            }
            if (steps.isNotBlank()) {
                appendLine()
                appendLine("Steps to reproduce the issue:")
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
    /** Where reports go. */
    val address: String get() = BuildConfig.SUPPORT_EMAIL.ifBlank { "developervt0909@gmail.com" }

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
        ).joinToString("\n")
    }

    private fun folder(context: Context) = File(context.cacheDir, "reports").apply {
        deleteRecursively()
        mkdirs()
    }

    /** Copies the chosen screenshots where email apps can read them. Slow; call off the main thread. */
    fun attachments(context: Context, screenshots: List<Uri>): List<Uri> {
        if (screenshots.isEmpty()) return emptyList()
        val dir = folder(context)
        val authority = "${context.packageName}.reports"
        return screenshots.mapIndexedNotNull { i, uri ->
            runCatching {
                val out = File(dir, "screenshot_${i + 1}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                if (out.length() > 0) FileProvider.getUriForFile(context, authority, out) else null
            }.getOrNull()
        }
    }

    /** The email apps on this phone (apps that open "mailto:" links). */
    fun emailApps(context: Context): List<String> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        val found = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, 0)
        }
        return found.map { it.activityInfo.packageName }.distinct()
    }

    /** "mailto:address?subject=…&body=…" with the text safely encoded. */
    fun mailto(report: BugReportText): Uri =
        Uri.parse("mailto:$address?subject=${Uri.encode(report.subject)}&body=${Uri.encode(report.body)}")

    /** A message to [address] for [app] (or for any app when null), with [files] attached. */
    fun emailFor(report: BugReportText, files: List<Uri>, app: String?): Intent {
        val send = if (files.isEmpty()) {
            // Without attachments a mailto: link opens the email app's compose screen directly.
            // Subject and text go in the link too, for email apps that only read the link.
            Intent(Intent.ACTION_SENDTO, mailto(report)).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
            }
        } else {
            Intent(if (files.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
                if (files.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
                else putExtra(Intent.EXTRA_STREAM, files[0])
                clipData = ClipData.newRawUri("", files[0]).apply { files.drop(1).forEach { addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        send.putExtra(Intent.EXTRA_SUBJECT, report.subject)
        send.putExtra(Intent.EXTRA_TEXT, report.body)
        if (app != null) send.setPackage(app)
        return send
    }

    /**
     * Opens the email app with the report ready to send. With several email apps the user
     * picks one. Returns false when the phone has no email app (the screen then offers to copy
     * the report or share it another way).
     */
    fun openEmail(context: Context, report: BugReportText, files: List<Uri>): Boolean {
        val apps = emailApps(context)
        if (apps.isEmpty()) return false
        return runCatching {
            val first = emailFor(report, files, apps[0]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (apps.size == 1) {
                context.startActivity(first)
            } else {
                val others = apps.drop(1).map { emailFor(report, files, it) }.toTypedArray()
                val chooser = Intent.createChooser(emailFor(report, files, apps[0]), "Send report with")
                    .putExtra(Intent.EXTRA_INITIAL_INTENTS, others)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
            true
        }.getOrDefault(false)
    }

    /** Shares the report with any app (WhatsApp, Telegram, …) when there is no email app. */
    fun shareAnyWay(context: Context, report: BugReportText) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, report.subject)
            putExtra(Intent.EXTRA_TEXT, "To: $address\n\n${report.body}")
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Copies the address, subject and report, to paste into any email. */
    fun copy(context: Context, report: BugReportText) {
        val clip = context.getSystemService(ClipboardManager::class.java)
        clip?.setPrimaryClip(
            ClipData.newPlainText("Bug report", "To: $address\nSubject: ${report.subject}\n\n${report.body}")
        )
    }
}

/** Settings → Report a bug. */
@Composable
fun BugReportScreen(appVersion: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    var withDevice by remember { mutableStateOf(true) }
    var shots by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    // Shown when the phone has no email app.
    var noEmailApp by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        if (uris.isNotEmpty()) shots = uris
    }
    fun report() = BugReportText(
        title = title,
        problem = problem,
        steps = steps,
        device = if (withDevice) BugReporter.deviceInfo(context, appVersion) else null,
    )

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Report a bug", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        }

        // Where it goes, so the user knows what will happen.
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(ToolColors.Time.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Email, null, tint = ToolColors.Time) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sent by email to", style = MaterialTheme.typography.bodySmall)
                    Text(BugReporter.address, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Your email app opens with everything filled in. Check it, then press Send.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedTextField(
            title, { title = it.take(80) },
            label = { Text("Issue title") },
            placeholder = { Text("e.g. App closes when I tap Export") },
            singleLine = true,
            supportingText = { Text("Used as the email subject: CutMitra – your title") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            problem, { problem = it },
            label = { Text("What went wrong? (optional)") },
            placeholder = { Text("What did you expect, and what happened instead?") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
        )
        OutlinedTextField(
            steps, { steps = it },
            label = { Text("Steps to reproduce the issue") },
            placeholder = { Text("1. Open a project\n2. Add a video\n3. Tap Export") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
        )

        Surface(
            onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, tint = ToolColors.Create)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (shots.isEmpty()) "Add screenshots (optional)" else "${shots.size} screenshot(s) added")
                    Text("Up to 3 pictures of the problem", style = MaterialTheme.typography.bodySmall)
                }
                Text(if (shots.isEmpty()) "Add" else "Change", color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Include phone model and Android version")
                Text(
                    "Helps us find problems on your kind of phone. Nothing personal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = withDevice, onCheckedChange = { withDevice = it })
        }

        WideButton(onClick = {
            if (title.isBlank()) return@WideButton
            sending = true
            scope.launch {
                val files = withContext(Dispatchers.IO) { BugReporter.attachments(context, shots) }
                noEmailApp = !BugReporter.openEmail(context, report(), files)
                sending = false
            }
        }) {
            Icon(Icons.Rounded.Send, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    sending -> "Opening your email app…"
                    title.isBlank() -> "Type an issue title to send"
                    else -> "Send report"
                }
            )
        }

        if (noEmailApp) {
            Surface(shape = RoundedCornerShape(16.dp), color = ToolColors.Style.copy(alpha = 0.14f)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("No email app on this phone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Copy the report and paste it into an email to ${BugReporter.address}, or share it another way.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    WideOutlinedButton(onClick = {
                        BugReporter.copy(context, report())
                        copied = true
                    }) { Text(if (copied) "Copied ✓" else "Copy report") }
                    TextButton(onClick = { BugReporter.shareAnyWay(context, report()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Share another way")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
