package com.Johnny.wcx.ui.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.Johnny.wcx.ui.utils.theme.ModuleTheme

// useful for showing a compose dialog in non-compose context,
// or when you don't want to manage the state for a dialog inside a composable
//
// note that you should use AlertDialogContent instead of AlertDialog inside 'content' to avoid
// creating multiple windows
fun showComposeDialog(
    context: Context,
    directlyDismissable: Boolean = true,
    fullScreen: Boolean = false,
    content: @Composable ShowComposeDialogScope.() -> Unit
) {
    val context = CommonContextWrapper(context)

    // 全屏时必须换成「非浮动」主题。
    // Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth 是浮动窗口主题
    // (windowIsFloating=true), 它给窗口自带最小宽度 65% / 尺寸约束与内外边距,
    // 单靠 setLayout(MATCH_PARENT, MATCH_PARENT) 是压不住主题约束的 ——
    // 表现就是"对话框只铺满中间一块, 左右留边、上下不贴边"。
    val dialogTheme = if (fullScreen) {
        android.R.style.Theme_DeviceDefault_Light_NoActionBar
    } else {
        android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth
    }

    val dialog = Dialog(context, dialogTheme)
    val lifecycleOwner = XposedLifecycleOwner.create()

    dialog.apply {
        window!!.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            requestFeature(Window.FEATURE_NO_TITLE)
            if (fullScreen) {
                // 铺满全屏: 窗口尺寸匹配父容器 + FILL 重力, 解除平台 Dialog 的默认尺寸约束
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setGravity(Gravity.FILL)
                // 全屏内容自身不透明, 不需要背景压暗; 压暗反而会在状态栏/导航栏露出暗边
                setDimAmount(0f)
            }
        }

        setCancelable(directlyDismissable)

        val scope = ShowComposeDialogScope(context, this, window!!, ::dismiss)

        setContentView(
            ComposeView(context).apply {
                setLifecycleOwner(lifecycleOwner)

                // Disable view state saving to prevent NotSerializableException.
                // Dialogs shown via showComposeDialog are ephemeral and don't need
                // state restoration. When the host Activity's onSaveInstanceState
                // fires, the ComposeView would try to serialize its SavedStateRegistry
                // which may contain non-serializable Compose internal objects.
                isSaveEnabled = false

                setContent {
                    CompositionLocalProvider(LocalContext provides context) {
                        ModuleTheme {
                            Box(
                                modifier = if (fullScreen) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier.wrapContentSize()
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                scope.content()
                            }
                        }
                    }
                }
            }
        )

        window!!.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setOnDismissListener { lifecycleOwner.onDestroy() }
        show()

        if (fullScreen) {
            // show() 之后 WindowManager 会用窗口属性重新计算一次布局,
            // 主题里的尺寸约束可能在这里又把窗口收回去, 所以 show 完再钉一次。
            window!!.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            window!!.setGravity(Gravity.FILL)
        }
    }
}

class ShowComposeDialogScope(
    val context: Context,
    val dialog: Dialog,
    val window: Window,
    val onDismiss: () -> Unit
)

fun View.setLifecycleOwner(lifecycleOwner: XposedLifecycleOwner) {
    apply {
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }
}
