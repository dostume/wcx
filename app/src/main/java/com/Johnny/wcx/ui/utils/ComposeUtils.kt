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

    // 全屏弹窗必须用非浮动主题: 浮动主题 (windowIsFloating=true) 自带最小宽度约束,
    // 只靠 setLayout(MATCH_PARENT, MATCH_PARENT) 压不住窗口。
    val theme = if (fullScreen) {
        android.R.style.Theme_DeviceDefault_Light_NoActionBar
    } else {
        android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth
    }
    val dialog = Dialog(context, theme)
    val lifecycleOwner = XposedLifecycleOwner.create()

    dialog.apply {
        window!!.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            requestFeature(Window.FEATURE_NO_TITLE)
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
                                modifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.wrapContentSize(),
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
            // show() 之后再钉一次 setLayout, 否则部分 ROM 下窗口尺寸不生效。
            window!!.setGravity(Gravity.FILL)
            window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
