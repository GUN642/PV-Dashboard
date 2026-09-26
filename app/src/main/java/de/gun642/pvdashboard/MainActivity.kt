package de.gun642.pvdashboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import de.gun642.pvdashboard.notify.BackgroundChecks
import de.gun642.pvdashboard.notify.Notifier
import de.gun642.pvdashboard.ui.App
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannels(this)
        BackgroundChecks.apply(this, viewModel.settings.value)
        openTabFrom(intent)
        setContent { App(viewModel, ::installApk, ::shareFile) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTabFrom(intent)
    }

    /** Aus einer Benachrichtigung heraus direkt den passenden Tab öffnen. */
    private fun openTabFrom(intent: Intent?) {
        val name = intent?.getStringExtra(Notifier.EXTRA_TAB) ?: return
        Tab.entries.firstOrNull { it.name == name }?.let {
            viewModel.tab = it
            viewModel.screen = Screen.MAIN
            if (it == Tab.HOME) viewModel.showContracts = true
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.start()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stop()
    }

    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, file.name))
    }

    private fun installApk(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Bitte \"Unbekannte Apps installieren\" für VOID Home Dashboard erlauben und dann erneut aktualisieren", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
