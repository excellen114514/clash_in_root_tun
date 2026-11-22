package com.roottun.clash.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roottun.clash.core.MihomoRelease
import com.roottun.clash.core.MihomoUpdates
import java.text.DateFormat
import java.util.Date

@Composable
internal fun KernelUpdateSettings() {
    val state by MihomoUpdates.state.collectAsStateWithLifecycle()
    SettingsSection("mihomo 内核更新") {
        SettingSwitchRow(
            title = "自动更新稳定版",
            subtitle = "打开应用时检查，最多每天一次。更新来自 MetaCubeX/mihomo 官方发布。",
            checked = state.automatic,
            enabled = state.ready && !state.rollback,
        ) { MihomoUpdates.setAutomatic(!state.automatic) }
        if (state.ready) {
            InfoRow("已选用", state.current + if (state.current == MihomoRelease.BUNDLED) "（内置）" else "")
            if (state.rollback) InfoRow("下次启动时切换至", "内置版 ${MihomoRelease.BUNDLED}")
            else state.pending?.let { InfoRow("下次启动时切换至", it) }
            if (state.checkedAt > 0) InfoRow("上次检查", DateFormat.getDateTimeInstance().format(Date(state.checkedAt)))
        }
        Text(state.result, style = MaterialTheme.typography.bodyMedium)
        AnimatedVisibility(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        FilledTonalButton(onClick = { MihomoUpdates.check() }, enabled = state.ready && !state.busy && !state.rollback) {
            Text("检查并下载更新")
        }
        if (!state.rollback && (state.current != MihomoRelease.BUNDLED || state.pending != null)) {
            TextButton(onClick = MihomoUpdates::useBundled, enabled = !state.busy) { Text("下次使用内置版并关闭自动更新") }
        }
        Text("更新和回退均在下次启动内核时生效。", style = MaterialTheme.typography.bodySmall)
    }
}
