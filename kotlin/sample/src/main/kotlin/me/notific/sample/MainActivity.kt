package me.notific.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: SampleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDebugExtras(intent)
        setContent { SampleTheme { SampleApp(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugExtras(intent)
    }

    /**
     * Debug builds accept a server and a person token from adb, for the lab:
     * `adb shell am start -n me.notific.sample/.MainActivity --es nm.baseUrl http://10.0.2.2:8080 --es nm.token nm_dlg_…`
     * or `--es nm.mode demo`.
     */
    private fun handleDebugExtras(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra("nm.baseUrl")?.let(vm::setBaseUrl)
        val token = intent.getStringExtra("nm.token")
        when {
            token != null -> vm.usePersonToken(token)
            intent.getStringExtra("nm.mode") == "demo" -> vm.openDemo()
        }
        // Once: a recreated activity (rotation, process restore) must not apply them again.
        listOf("nm.baseUrl", "nm.token", "nm.mode").forEach(intent::removeExtra)
    }
}
