package com.roottun.clash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roottun.clash.core.LogBus
import com.roottun.clash.core.LogExporter
import com.roottun.clash.core.LogLevel
import com.roottun.clash.core.LogLine
import com.roottun.clash.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogConsoleScreen(snackbarHostState: SnackbarHostState) {
    val allLines by LogBus.lines.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var minLevel by rememberSaveable { mutableStateOf(LogLevel.DEBUG) }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    val filtered = remember(allLines, minLevel) {
        allLines.filter { it.level.ordinal >= minLevel.ordinal }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日志控制台") },
                actions = {
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            imageVector = if (autoScroll) {
                                Icons.Filled.KeyboardArrowDown
                            } else {
                                Icons.Filled.KeyboardArrowUp
                            },
                            contentDescription = if (autoScroll) "关闭自动滚动" else "开启自动滚动",
                        )
                    }
                    IconButton(
                        enabled = !exporting,
                        onClick = {
                            exporting = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { LogExporter.export(context) }
                                exporting = false
                                result.onSuccess { export ->
                                    snackbarHostState.showSnackbar(
                                        "日志已下载到 ${export.location}（${export.bytes} 字节）",
                                    )
                                }.onFailure { throwable ->
                                    snackbarHostState.showSnackbar(
                                        "下载日志失败：${throwable.message ?: throwable.javaClass.simpleName}",
                                    )
                                }
                            }
                        },
                    ) {
                        if (exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(AppIcons.Download, contentDescription = "下载日志")
                        }
                    }
                    IconButton(onClick = { LogBus.clear() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空日志")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LevelFilterChip("全部", LogLevel.DEBUG, minLevel) { minLevel = it }
                LevelFilterChip("INFO", LogLevel.INFO, minLevel) { minLevel = it }
                LevelFilterChip("WARN", LogLevel.WARN, minLevel) { minLevel = it }
                LevelFilterChip("ERROR", LogLevel.ERROR, minLevel) { minLevel = it }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "自动滚动 ${if (autoScroll) "开" else "关"} · 显示 ${filtered.size}/${allLines.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
            }
            HorizontalDivider()

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无日志（级别过滤：${if (minLevel == LogLevel.DEBUG) "全部" else minLevel.name}）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items = filtered, key = { it.id }) { line ->
                        LogRow(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelFilterChip(
    label: String,
    level: LogLevel,
    selectedLevel: LogLevel,
    onSelect: (LogLevel) -> Unit,
) {
    FilterChip(
        selected = selectedLevel == level,
        onClick = { onSelect(level) },
        label = { Text(label) },
    )
}

@Composable
private fun LogRow(line: LogLine) {
    val colorScheme = MaterialTheme.colorScheme
    val levelColor = when (line.level) {
        LogLevel.DEBUG -> colorScheme.onSurfaceVariant
        LogLevel.INFO -> colorScheme.primary
        LogLevel.WARN -> colorScheme.tertiary
        LogLevel.ERROR -> colorScheme.error
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = colorScheme.onSurfaceVariant)) {
            append(line.timeText)
            append("  ")
        }
        withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.SemiBold)) {
            append(line.level.name.padEnd(5))
        }
        withStyle(SpanStyle(color = colorScheme.secondary)) {
            append("[${line.tag}] ")
        }
        withStyle(SpanStyle(color = colorScheme.onSurface)) {
            append(line.message)
        }
    }
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}
