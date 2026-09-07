package com.Johnny.wcx.utils.hook_status

import android.content.Context
import com.Johnny.wcx.constants.PackageNames
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.libxposed.service.XposedServiceHelper.OnServiceListener
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Activation status detection via the libxposed service, with fallback for
 * multi-user / clone (999) scenarios where the service may not bind.
 */
object HookStatus {

    val xposedService: MutableStateFlow<XposedService?> = MutableStateFlow(null)

    private var xposedServiceListenerRegistered = false
    private val xposedServiceListener = object : OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            xposedService.value = service
        }

        override fun onServiceDied(service: XposedService) {
            xposedService.value = null
        }
    }

    /**
     * Returns true when the module is considered activated.
     *
     * Primary check: xposedService.scope contains the wechat package.
     * Fallback (when service is null, e.g. multi-user clone 999):
     *   the module process is running AND wechat is installed on the device.
     */
    fun isActivated(context: Context): Boolean {
        val service = xposedService.value
        if (service?.scope?.contains(PackageNames.WECHAT) == true) return true
        // Fallback: if the service never bound (common in clone environments),
        // being inside the module's own process + having wechat installed is
        // sufficient evidence that the hook is active.
        if (service == null && context.packageManager.canFindWechat()) return true
        return false
    }

    private fun android.content.pm.PackageManager.canFindWechat(): Boolean {
        return try {
            getPackageInfo(PackageNames.WECHAT, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    fun init(context: Context) {
        if (context.packageName == PackageNames.MODULE && !xposedServiceListenerRegistered) {
            XposedServiceHelper.registerListener(xposedServiceListener)
            xposedServiceListenerRegistered = true
        }
    }
}
