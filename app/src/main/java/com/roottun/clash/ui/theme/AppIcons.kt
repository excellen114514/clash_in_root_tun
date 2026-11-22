package com.roottun.clash.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Material 图标集中缺少的两个图标（保持 MD3 24dp/2dp 网格规范）。 */
object AppIcons {

    /** Material `dns`：用于「节点」入口。 */
    val Nodes: ImageVector by lazy {
        build(
            "Nodes",
            "M20,13H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1v-6c0,-0.55 -0.45,-1 -1,-1z" +
                "M7,19c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z" +
                "M20,3H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45,-1 -1,-1z" +
                "M7,9c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z",
        )
    }

    /** Material `file_download`：用于「下载日志」与下载流量。 */
    val Download: ImageVector by lazy {
        build("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    }

    /** Material `file_upload`：用于上传流量。 */
    val Upload: ImageVector by lazy {
        build("Upload", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z")
    }

    /** 应用图标缺失时的通用网格图标（Material `apps`）。 */
    val Apps: ImageVector by lazy {
        build(
            "Apps",
            "M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4z" +
                "M10,14h4v-4h-4v4zM16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z",
        )
    }

    private fun build(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
}
