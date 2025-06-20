package be.mygod.vpnhotspot.net.wifi

import android.annotation.TargetApi
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Parcelable
import androidx.annotation.RequiresApi
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.net.wifi.WifiApManager.EXTRA_WIFI_AP_STATE
import be.mygod.vpnhotspot.net.wifi.WifiApManager.WIFI_AP_STATE_CHANGED_ACTION
import be.mygod.vpnhotspot.net.wifi.WifiApManager.WIFI_AP_STATE_DISABLED
import be.mygod.vpnhotspot.net.wifi.WifiApManager.WIFI_AP_STATE_DISABLING
import be.mygod.vpnhotspot.net.wifi.WifiApManager.WIFI_AP_STATE_ENABLED
import be.mygod.vpnhotspot.net.wifi.WifiApManager.WIFI_AP_STATE_ENABLING
import be.mygod.vpnhotspot.net.wifi.WifiApManager.WIFI_AP_STATE_FAILED
import be.mygod.vpnhotspot.util.*
import timber.log.Timber
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

object WifiApManager {
    /**
     * https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/000ad45/service/java/com/android/server/wifi/WifiContext.java#41
     */
    @RequiresApi(30)
    private const val ACTION_RESOURCES_APK = "com.android.server.wifi.intent.action.SERVICE_WIFI_RESOURCES_APK"
    @RequiresApi(30)
    const val RESOURCES_PACKAGE = "com.android.wifi.resources"
    /**
     * Based on: https://cs.android.com/android/platform/superproject/+/master:packages/modules/Wifi/framework/java/android/net/wifi/WifiContext.java;l=66;drc=5ca657189aac546af0aafaba11bbc9c5d889eab3
     */
    @get:RequiresApi(30)
    val resolvedActivity: ActivityInfo get() {
        val list = app.packageManager.queryIntentActivities(Intent(ACTION_RESOURCES_APK),
            PackageManager.MATCH_SYSTEM_ONLY).distinctBy { it.activityInfo.applicationInfo.packageName }
        require(list.isNotEmpty()) { "Missing $ACTION_RESOURCES_APK" }
        if (list.size > 1) {
            list.singleOrNull {
                it.activityInfo.applicationInfo.sourceDir.startsWith("/apex/com.android.wifi")
            }?.let { return it.activityInfo }
            Timber.w(Exception("Found > 1 apk: " + list.joinToString {
                val info = it.activityInfo.applicationInfo
                "${info.packageName} (${info.sourceDir})"
            }))
        }
        return list[0].activityInfo
    }

    private const val CONFIG_P2P_MAC_RANDOMIZATION_SUPPORTED = "config_wifi_p2p_mac_randomization_supported"
    val p2pMacRandomizationSupported get() = try {
        when (Build.VERSION.SDK_INT) {
            29 -> Resources.getSystem().run {
                getBoolean(getIdentifier(CONFIG_P2P_MAC_RANDOMIZATION_SUPPORTED, "bool", "android"))
            }
            in 30..Int.MAX_VALUE -> @TargetApi(30) {
                val info = resolvedActivity
                val resources = app.packageManager.getResourcesForApplication(info.applicationInfo)
                resources.getBoolean(resources.findIdentifier(CONFIG_P2P_MAC_RANDOMIZATION_SUPPORTED, "bool",
                    RESOURCES_PACKAGE, info.packageName))
            }
            else -> false
        }
    } catch (e: RuntimeException) {
        Timber.w(e)
        false
    }

    @get:RequiresApi(30)
    private val apMacRandomizationSupported by lazy {
        WifiManager::class.java.getDeclaredMethod("isApMacRandomizationSupported")
    }
    @get:RequiresApi(30)
    val isApMacRandomizationSupported get() = apMacRandomizationSupported(Services.wifi) as Boolean

    /**
     * Broadcast intent action indicating that Wi-Fi AP has been enabled, disabled,
     * enabling, disabling, or failed.
     */
    const val WIFI_AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    /**
     * The lookup key for an int that indicates whether Wi-Fi AP is enabled,
     * disabled, enabling, disabling, or failed.  Retrieve it with [Intent.getIntExtra].
     *
     * @see WIFI_AP_STATE_DISABLED
     * @see WIFI_AP_STATE_DISABLING
     * @see WIFI_AP_STATE_ENABLED
     * @see WIFI_AP_STATE_ENABLING
     * @see WIFI_AP_STATE_FAILED
     */
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    /**
     * An extra containing the int error code for Soft AP start failure.
     * Can be obtained from the [WIFI_AP_STATE_CHANGED_ACTION] using [Intent.getIntExtra].
     * This extra will only be attached if [EXTRA_WIFI_AP_STATE] is
     * attached and is equal to [WIFI_AP_STATE_FAILED].
     *
     * The error code will be one of:
     * {@link #SAP_START_FAILURE_GENERAL},
     * {@link #SAP_START_FAILURE_NO_CHANNEL},
     * {@link #SAP_START_FAILURE_UNSUPPORTED_CONFIGURATION}
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/android-6.0.0_r1/wifi/java/android/net/wifi/WifiManager.java#210
     */
    val EXTRA_WIFI_AP_FAILURE_REASON get() =
        if (Build.VERSION.SDK_INT >= 30) "android.net.wifi.extra.WIFI_AP_FAILURE_REASON" else "wifi_ap_error_code"
    /**
     * The lookup key for a String extra that stores the interface name used for the Soft AP.
     * This extra is included in the broadcast [WIFI_AP_STATE_CHANGED_ACTION].
     * Retrieve its value with [Intent.getStringExtra].
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/wifi/java/android/net/wifi/WifiManager.java#413
     */
    val EXTRA_WIFI_AP_INTERFACE_NAME get() =
        if (Build.VERSION.SDK_INT >= 30) "android.net.wifi.extra.WIFI_AP_INTERFACE_NAME" else "wifi_ap_interface_name"

    fun checkWifiApState(state: Int) = if (state < WIFI_AP_STATE_DISABLING || state > WIFI_AP_STATE_FAILED) {
        Timber.w(Exception("Unknown state $state"))
        false
    } else true
    val Intent.wifiApState get() =
        getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED).also { checkWifiApState(it) }
    /**
     * Wi-Fi AP is currently being disabled. The state will change to
     * [WIFI_AP_STATE_DISABLED] if it finishes successfully.
     *
     * @see WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     */
    const val WIFI_AP_STATE_DISABLING = 10
    /**
     * Wi-Fi AP is disabled.
     *
     * @see WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    const val WIFI_AP_STATE_DISABLED = 11
    /**
     * Wi-Fi AP is currently being enabled. The state will change to
     * {@link #WIFI_AP_STATE_ENABLED} if it finishes successfully.
     *
     * @see WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     */
    const val WIFI_AP_STATE_ENABLING = 12
    /**
     * Wi-Fi AP is enabled.
     *
     * @see WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     */
    const val WIFI_AP_STATE_ENABLED = 13
    /**
     * Wi-Fi AP is in a failed state. This state will occur when an error occurs during
     * enabling or disabling
     *
     * @see WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     */
    const val WIFI_AP_STATE_FAILED = 14

    private val getWifiApConfiguration by lazy { WifiManager::class.java.getDeclaredMethod("getWifiApConfiguration") }
    @Suppress("DEPRECATION")
    private val setWifiApConfiguration by lazy {
        WifiManager::class.java.getDeclaredMethod("setWifiApConfiguration",
                android.net.wifi.WifiConfiguration::class.java)
    }
    @get:RequiresApi(30)
    private val getSoftApConfiguration by lazy { WifiManager::class.java.getDeclaredMethod("getSoftApConfiguration") }
    @get:RequiresApi(30)
    private val setSoftApConfiguration by lazy @TargetApi(30) {
        WifiManager::class.java.getDeclaredMethod("setSoftApConfiguration", SoftApConfiguration::class.java)
    }

    /**
     * Requires NETWORK_SETTINGS permission (or root) on API 30+, and OVERRIDE_WIFI_CONFIG on API 29-.
     */
    @Deprecated("Use configuration instead", ReplaceWith("configuration"))
    @Suppress("DEPRECATION")
    val configurationLegacy get() = getWifiApConfiguration(Services.wifi) as android.net.wifi.WifiConfiguration?
    /**
     * Requires NETWORK_SETTINGS permission (or root).
     */
    @get:RequiresApi(30)
    val configuration get() = getSoftApConfiguration(Services.wifi) as SoftApConfiguration
    @Deprecated("Use SoftApConfiguration instead")
    @Suppress("DEPRECATION")
    fun setConfiguration(value: android.net.wifi.WifiConfiguration?) =
        setWifiApConfiguration(Services.wifi, value) as Boolean
    fun setConfiguration(value: SoftApConfiguration) = setSoftApConfiguration(Services.wifi, value) as Boolean

    interface SoftApCallbackCompat {
        /**
         * Called when soft AP state changes.
         *
         * @param state         the new AP state. One of [WIFI_AP_STATE_DISABLED], [WIFI_AP_STATE_DISABLING],
         *   [WIFI_AP_STATE_ENABLED], [WIFI_AP_STATE_ENABLING], [WIFI_AP_STATE_FAILED]
         * @param failureReason reason when in failed state. One of
         *                      {@link #SAP_START_FAILURE_GENERAL},
         *                      {@link #SAP_START_FAILURE_NO_CHANNEL},
         *                      {@link #SAP_START_FAILURE_UNSUPPORTED_CONFIGURATION}
         */
        fun onStateChanged(state: Int, failureReason: Int) { }

        /**
         * Called when number of connected clients to soft AP changes.
         *
         * It is not recommended to use this legacy method on API 30+.
         *
         * @param numClients number of connected clients
         */
        fun onNumClientsChanged(numClients: Int) { }

        /**
         * Called when the connected clients to soft AP changes.
         *
         * @param clients the currently connected clients
         */
        @RequiresApi(30)
        fun onConnectedClientsChanged(clients: List<Parcelable>) = onNumClientsChanged(clients.size)

        /**
         * Called when information of softap changes.
         *
         * @param info is the softap information. [SoftApInfo]
         *             At most one will be returned on API 30.
         */
        @RequiresApi(30)
        fun onInfoChanged(info: List<Parcelable>) { }

        /**
         * Called when capability of softap changes.
         *
         * @param capability is the softap capability. [SoftApCapability]
         */
        @RequiresApi(30)
        fun onCapabilityChanged(capability: Parcelable) { }

        /**
         * Called when client trying to connect but device blocked the client with specific reason.
         *
         * Can be used to ask user to update client to allowed list or blocked list
         * when reason is {@link SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER}, or
         * indicate the block due to maximum supported client number limitation when reason is
         * {@link SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS}.
         *
         * @param client the currently blocked client.
         * @param blockedReason one of blocked reason from [SapClientBlockedReason]
         */
        @RequiresApi(30)
        fun onBlockedClientConnecting(client: Parcelable, blockedReason: Int) { }

        /**
         * Called when clients disconnect from a soft AP instance.
         *
         * @param info The [SoftApInfo] of the AP.
         * @param clients The clients that have disconnected from the AP instance specified by
         * `info`.
         */
        @RequiresApi(30)
        fun onClientsDisconnected(info: Parcelable, clients: List<Parcelable>) { }
    }
    val failureReasonLookup = ConstantLookup<WifiManager>("SAP_START_FAILURE_", "GENERAL", "NO_CHANNEL")
    @get:RequiresApi(30)
    val clientBlockLookup by lazy { ConstantLookup<WifiManager>("SAP_CLIENT_") }
    @get:RequiresApi(30)
    val deauthenticationReasonLookup by lazy {
        ConstantLookup("REASON_") { Class.forName("android.net.wifi.DeauthenticationReasonCode") }
    }

    private val interfaceSoftApCallback by lazy { Class.forName("android.net.wifi.WifiManager\$SoftApCallback") }
    private val registerSoftApCallback by lazy {
        val parameters = if (Build.VERSION.SDK_INT >= 30) {
            arrayOf(Executor::class.java, interfaceSoftApCallback)
        } else arrayOf(interfaceSoftApCallback, Handler::class.java)
        WifiManager::class.java.getDeclaredMethod("registerSoftApCallback", *parameters)
    }
    private val unregisterSoftApCallback by lazy {
        WifiManager::class.java.getDeclaredMethod("unregisterSoftApCallback", interfaceSoftApCallback)
    }

    fun registerSoftApCallback(callback: SoftApCallbackCompat, executor: Executor): Any {
        val proxy = Proxy.newProxyInstance(interfaceSoftApCallback.classLoader,
                arrayOf(interfaceSoftApCallback), object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?) =
                    if (Build.VERSION.SDK_INT < 30 && interfaceSoftApCallback === method.declaringClass) {
                        executor.execute { invokeActual(proxy, method, args) }
                        null    // no return value as of API 30
                    } else invokeActual(proxy, method, args)

            private fun invokeActual(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
                return when {
                    method.matches("onStateChanged", Integer.TYPE, Integer.TYPE) -> {
                        callback.onStateChanged(args!![0] as Int, args[1] as Int)
                    }
                    method.matches("onNumClientsChanged", Integer.TYPE) -> {
                        if (Build.VERSION.SDK_INT >= 30) Timber.w(Exception("Unexpected onNumClientsChanged"))
                        callback.onNumClientsChanged(args!![0] as Int)
                    }
                    method.matches1<java.util.List<*>>("onConnectedClientsChanged") -> @TargetApi(30) {
                        if (Build.VERSION.SDK_INT < 30) Timber.w(Exception("Unexpected onConnectedClientsChanged"))
                        @Suppress("UNCHECKED_CAST")
                        callback.onConnectedClientsChanged(args!![0] as List<Parcelable>)
                    }
                    method.matches1<java.util.List<*>>("onInfoChanged") -> @TargetApi(30) {
                        if (Build.VERSION.SDK_INT < 30) Timber.w(Exception("Unexpected onInfoChanged"))
                        @Suppress("UNCHECKED_CAST")
                        val list = args!![0] as List<Parcelable>
                        if (Build.VERSION.SDK_INT >= 35) for (info in list) (SoftApInfo.getVendorData(info) as List<*>?)
                            .let { if (!it.isNullOrEmpty()) Timber.w(Exception(it.toString())) }
                        callback.onInfoChanged(list)
                    }
                    Build.VERSION.SDK_INT >= 30 && method.matches("onInfoChanged", SoftApInfo.clazz) -> {
                        if (Build.VERSION.SDK_INT >= 31) return null    // ignore old version calls
                        val arg = args!![0]
                        val info = SoftApInfo(arg as Parcelable)
                        callback.onInfoChanged(if (info.frequency == 0 && info.bandwidth ==
                            SoftApConfigurationCompat.CHANNEL_WIDTH_INVALID) emptyList() else listOf(arg))
                    }
                    Build.VERSION.SDK_INT >= 30 && method.matches("onCapabilityChanged", SoftApCapability.clazz) -> {
                        callback.onCapabilityChanged(args!![0] as Parcelable)
                    }
                    Build.VERSION.SDK_INT >= 30 && method.matches("onBlockedClientConnecting", WifiClient.clazz,
                        Int::class.java) -> {
                        callback.onBlockedClientConnecting(args!![0] as Parcelable, args[1] as Int)
                    }
                    Build.VERSION.SDK_INT >= 30 && method.matches("onClientsDisconnected", SoftApInfo.clazz,
                        List::class.java) -> {
                        @Suppress("UNCHECKED_CAST")
                        callback.onClientsDisconnected(args!![0] as Parcelable, args[1] as List<Parcelable>)
                    }
                    else -> callSuper(interfaceSoftApCallback, proxy, method, args)
                }
            }
        })
        if (Build.VERSION.SDK_INT >= 30) {
            registerSoftApCallback(Services.wifi, executor, proxy)
        } else registerSoftApCallback(Services.wifi, proxy, null)
        return proxy
    }
    fun unregisterSoftApCallback(key: Any) = unregisterSoftApCallback(Services.wifi, key)

    @get:RequiresApi(30)
    private val startLocalOnlyHotspot by lazy @TargetApi(30) {
        WifiManager::class.java.getDeclaredMethod("startLocalOnlyHotspot", SoftApConfiguration::class.java,
            Executor::class.java, WifiManager.LocalOnlyHotspotCallback::class.java)
    }
    @RequiresApi(30)
    fun startLocalOnlyHotspot(config: SoftApConfiguration, callback: WifiManager.LocalOnlyHotspotCallback?,
                              executor: Executor? = null) =
        startLocalOnlyHotspot(Services.wifi, config, executor, callback)

    private val cancelLocalOnlyHotspotRequest by lazy {
        WifiManager::class.java.getDeclaredMethod("cancelLocalOnlyHotspotRequest")
    }
    /**
     * This is the only way to unregister requests besides app exiting.
     * Therefore, we are happy with crashing the app if reflection fails.
     */
    fun cancelLocalOnlyHotspotRequest() = cancelLocalOnlyHotspotRequest(Services.wifi)
}
