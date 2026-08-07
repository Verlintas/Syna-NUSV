package com.syna.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 大屏（平板/桌面）内容最大宽度，小屏手机保持全宽 */
const val CONTENT_MAX_WIDTH_DP = 640

/** 聊天气泡最大宽度（按可用宽度比例，适配手机/平板/桌面） */
const val BUBBLE_MAX_WIDTH_RATIO = 0.72f
const val BUBBLE_ABSOLUTE_MAX_DP = 560

/**
 * 让内容在窄屏占满、宽屏居中且不超过 [maxWidth]（微信桌面版式布局）。
 * 用于会话列表 / 联系人列表 / 设置等页面。
 */
@Composable
fun MaxWidthContainer(
    modifier: Modifier = Modifier,
    maxWidth: Dp = CONTENT_MAX_WIDTH_DP.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = maxWidth),
        ) {
            content()
        }
    }
}
