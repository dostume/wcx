package com.Johnny.wcx.loader.utils

import android.content.Context
import com.tencent.mmkv.MMKV
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.fs.createDirsSafe
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    init {
        System.loadLibrary("dexkit")
        System.loadLibrary("wekit_native")
    }

    fun init(hostCtx: Context) {
        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirsSafe()
        }

        MMKV.initialize(hostCtx, mmkvDir.toString())

        // 使用基于包名的独立 MMKV ID，防止主微信和分身（user 999）配置混淆
        // 例如：com.tencent.mm → "wekit_prefs_com_tencent_mm"，分身自动隔离
        val safeId = WePrefs.PREFS_NAME + "_" + hostCtx.packageName.replace(".", "_")
        MMKV.mmkvWithID(safeId, MMKV.MULTI_PROCESS_MODE)
    }
}
