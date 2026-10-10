package com.Johnny.wcx.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AlertDialogContent(
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)?,
    text: @Composable (() -> Unit)?,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null
) {
    Surface(
        // 全屏时贴满宿主界面: 不留圆角与阴影, 视觉上像一个原生页面而不是悬浮对话框
        shape = if (fullScreen) RectangleShape else MaterialTheme.shapes.extraLarge,
        tonalElevation = if (fullScreen) 0.dp else 6.dp,
        modifier = modifier
            .fillMaxSize()
            .then(if (fullScreen) Modifier else Modifier.wrapContentHeight())
    ) {
        Column(
            modifier = Modifier
                .padding(
                    if (fullScreen) PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
                    else PaddingValues(20.dp)
                )
                .then(if (fullScreen) Modifier.fillMaxSize() else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                            icon()
                        }
                    }
                }
                title?.let {
                    val customStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    )
                    CompositionLocalProvider(LocalTextStyle provides customStyle) {
                        it()
                    }
                }
            }

            HorizontalDivider()

            if (text != null) {
                val bodyStyle = MaterialTheme.typography.bodyMedium
                val bodyColor = MaterialTheme.colorScheme.onSurface

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 全屏时占满剩余高度, 列表才能撑到屏幕底部
                        .weight(1f, fill = fullScreen)
                ) {
                    CompositionLocalProvider(
                        LocalTextStyle provides bodyStyle,
                        LocalContentColor provides bodyColor
                    ) {
                        if (fullScreen) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                            ) {
                                text()
                            }
                        } else {
                            text()
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                val buttonTextStyle = MaterialTheme.typography.labelLarge
                CompositionLocalProvider(LocalTextStyle provides buttonTextStyle) {
                    dismissButton?.invoke()
                    confirmButton?.invoke()
                }
            }
        }
    }
}
