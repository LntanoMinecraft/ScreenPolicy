package com.example.hook

import android.content.pm.ApplicationInfo
import android.os.Binder
import com.example.data.PolicyConstants
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 17 (API 37) system_server hook for internal audio playback capture (allowAudioCapture).
 *
 * Completely independent of allowScreenshot / allowScreenRecord so enabling allowAudioCapture
 * alone works across both Game Dashboard and native status-bar screen recording
 * (ScreenInternalAudioRecorder).
 *
 * Uses libxposed API 102 interceptors + ART deoptimization across 3 targeted layers plus
 * proactive audioserver cache flushing:
 * 1. Layer 1 (PackageInfoUtils.generateApplicationInfo): sets PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE
 *    on generated ApplicationInfo for allowlisted packages.
 * 2. Layer 2 (AudioSystem.setAllowedCapturePolicy(int uid, int flags)): intercepts the final native
 *    gate to audioserver and forces flags = 0 (clearing AUDIO_FLAG_NO_MEDIA_PROJECTION and
 *    AUDIO_FLAG_NO_SYSTEM_CAPTURE) for allowlisted UIDs.
 * 3. Layer 3 (AudioService & PlaybackActivityMonitor): deoptimizes and intercepts
 *    setAllowedCapturePolicy / getAllowedCapturePolicy to enforce ALLOW_CAPTURE_BY_ALL (1).
 * 4. Active cache flush: on boot completion and on every Remote Preferences change, resolves real
 *    UIDs for allowAudioCapture packages and proactively invokes AudioSystem.setAllowedCapturePolicy(uid, 0)
 *    and PlaybackActivityMonitor.setAllowedCapturePolicy(uid, 1) to clear stale audioserver flags.
 *
 * Safety boundary: never touches Bluetooth A2DP or voice-call audio routing; all hooks and refreshes
 * run off the main thread inside isolated try-catch (Throwable) blocks.
 */
object AudioCaptureHook {
    private const val TAG = "AudioCapture"
    private const val PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE = 1 shl 27 // 0x08000000
    internal const val AUDIO_SYSTEM_CAPTURE_ALLOW_ALL = 0 // 0x0: clears NO_MEDIA_PROJECTION & NO_SYSTEM_CAPTURE
    internal const val ALLOW_CAPTURE_BY_ALL = 1 // AudioAttributes.ALLOW_CAPTURE_BY_ALL

    private const val CLASS_PACKAGE_INFO_UTILS = "com.android.server.pm.parsing.PackageInfoUtils"
    private const val CLASS_AUDIO_SYSTEM = "android.media.AudioSystem"
    private const val CLASS_AUDIO_SERVICE = "com.android.server.audio.AudioService"
    private const val CLASS_PLAYBACK_MONITOR = "com.android.server.audio.PlaybackActivityMonitor"
    private const val CLASS_SERVICE_MANAGER = "android.os.ServiceManager"
    private const val CLASS_LOCAL_SERVICES = "com.android.server.LocalServices"
    private const val CLASS_PACKAGE_MANAGER_INTERNAL = "android.content.pm.PackageManagerInternal"

    @Volatile
    private var capturedPlaybackMonitorRef: WeakReference<Any>? = null

    @Volatile
    private var capturedSystemClassLoader: ClassLoader? = null

    private val changeListenerRegistered = AtomicBoolean(false)
    private val prefsFlushPending = AtomicBoolean(false)

    private val flushScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ScreenPolicy-AudioFlush").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val privateFlagsField: Field? by lazy {
        try {
            ApplicationInfo::class.java.getDeclaredField("privateFlags").apply {
                isAccessible = true
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun installPackageInfoLayer(module: XposedModule, classLoader: ClassLoader): Pair<Int, String?> {
        capturedSystemClassLoader = classLoader
        return try {
            val pkgInfoUtils = HookReflect.findClassOrNull(CLASS_PACKAGE_INFO_UTILS, classLoader)
                ?: return 0 to "PackageInfoUtils:ClassNotFound"

            val targets = selectGenerateAppInfoMethods(pkgInfoUtils)
            var count = 0
            for (method in targets) {
                runCatching { module.deoptimize(method) }
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val info = result as? ApplicationInfo
                        if (info != null && PolicyCache.isAudioCaptureAllowed(info.packageName)) {
                            val field = privateFlagsField
                            if (field != null) {
                                val current = field.getInt(info)
                                if ((current and PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE) == 0) {
                                    field.setInt(info, current or PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE)
                                    DualTrackLogger.logHit(
                                        scope = PolicyConstants.SCOPE_SYSTEM,
                                        tag = TAG,
                                        packageName = info.packageName,
                                        action = "privateFlags+=ALLOW_AUDIO_PLAYBACK_CAPTURE",
                                    )
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        DualTrackLogger.logError(
                            PolicyConstants.SCOPE_SYSTEM,
                            TAG,
                            "generateAppInfo_cb",
                            t,
                        )
                    }
                    result
                }
                count++
            }
            count to null
        } catch (t: Throwable) {
            DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "install_pkg_layer", t)
            0 to "PackageInfoUtils:${t.javaClass.simpleName}"
        }
    }

    fun installAudioServiceLayer(module: XposedModule, classLoader: ClassLoader): Pair<Int, String?> {
        capturedSystemClassLoader = classLoader
        return try {
            var audioHookCount = 0
            val errors = mutableListOf<String>()

            val audioSystemCount = try {
                hookAudioSystemGate(module, classLoader)
            } catch (t: Throwable) {
                DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "install_audiosystem", t)
                errors.add("AudioSystem:${t.javaClass.simpleName}")
                0
            }
            audioHookCount += audioSystemCount

            val audioServiceCount = try {
                hookCapturePolicyClass(module, classLoader, CLASS_AUDIO_SERVICE, captureMonitor = false)
            } catch (t: Throwable) {
                DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "install_audioservice", t)
                errors.add("AudioService:${t.javaClass.simpleName}")
                0
            }
            audioHookCount += audioServiceCount

            val playbackMonitorCount = try {
                hookCapturePolicyClass(module, classLoader, CLASS_PLAYBACK_MONITOR, captureMonitor = true)
            } catch (t: Throwable) {
                DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "install_playback_monitor", t)
                errors.add("PlaybackMonitor:${t.javaClass.simpleName}")
                0
            }
            audioHookCount += playbackMonitorCount

            registerDynamicRefreshListener(classLoader)

            val summaryError = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
            audioHookCount to summaryError
        } catch (t: Throwable) {
            DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "install_audio_layer", t)
            0 to "AudioService:${t.javaClass.simpleName}"
        }
    }

    fun combineResult(
        pkgResult: Pair<Int, String?>,
        audioResult: Pair<Int, String?>,
    ): Pair<Boolean, String> {
        val pkgHookCount = pkgResult.first
        val audioHookCount = audioResult.first
        val errors = listOfNotNull(pkgResult.second, audioResult.second)
        val ok = pkgHookCount > 0 || audioHookCount > 0
        val detail = buildString {
            append("PackageInfoUtils=").append(pkgHookCount)
            append(", AudioGate=").append(audioHookCount)
            if (errors.isNotEmpty()) {
                append(" (").append(errors.joinToString("; ")).append(")")
            }
        }
        return ok to detail
    }

    /**
     * Registers a one-time PolicyCache listener so any Remote Preferences update immediately
     * flushes audioserver & PlaybackActivityMonitor capture policies off the callback thread.
     */
    private fun registerDynamicRefreshListener(classLoader: ClassLoader) {
        if (!changeListenerRegistered.compareAndSet(false, true)) return
        PolicyCache.addPolicyChangeListener {
            if (prefsFlushPending.compareAndSet(false, true)) {
                scheduleActiveCacheRefresh(
                    classLoader = capturedSystemClassLoader ?: classLoader,
                    delayMs = 150L,
                    reason = "prefs_changed",
                )
            }
        }
    }

    /**
     * Schedules an asynchronous MIN_PRIORITY background flush of audioserver and
     * PlaybackActivityMonitor policies for all allowAudioCapture packages.
     */
    fun scheduleActiveCacheRefresh(
        classLoader: ClassLoader,
        delayMs: Long = 0L,
        reason: String = "init",
    ) {
        try {
            flushScheduler.schedule(
                {
                    if (reason == "prefs_changed") {
                        prefsFlushPending.set(false)
                    }
                    try {
                        flushAllowedCapturePoliciesForAllowlist(classLoader, reason)
                    } catch (t: Throwable) {
                        DualTrackLogger.logError(
                            PolicyConstants.SCOPE_SYSTEM,
                            TAG,
                            "cache_flush_$reason",
                            t,
                        )
                    }
                },
                delayMs.coerceAtLeast(0L),
                TimeUnit.MILLISECONDS,
            )
        } catch (t: Throwable) {
            if (reason == "prefs_changed") {
                prefsFlushPending.set(false)
            }
            DualTrackLogger.logError(PolicyConstants.SCOPE_SYSTEM, TAG, "schedule_flush_$reason", t)
        }
    }

    /**
     * Proactively clears stale audioserver (flags=0) and PlaybackActivityMonitor (policy=1)
     * capture restrictions for all packages currently enabled for allowAudioCapture.
     */
    fun flushAllowedCapturePoliciesForAllowlist(
        classLoader: ClassLoader,
        reason: String = "manual",
    ): Int {
        val allowedPackages = PolicyCache.getAudioCaptureAllowedPackages()
        if (allowedPackages.isEmpty()) return 0

        val audioSystemClass = HookReflect.findClassOrNull(CLASS_AUDIO_SYSTEM, classLoader)
            ?: HookReflect.findClassOrNull(CLASS_AUDIO_SYSTEM, null)
        val playbackMonitor = resolvePlaybackActivityMonitorInstance(classLoader)

        var flushedCount = 0
        for (pkg in allowedPackages) {
            try {
                val uid = resolveUidForPackage(pkg, classLoader)
                if (uid <= 0) continue

                PolicyCache.registerResolvedUid(uid, pkg)
                val (audioSysOk, monitorOk) = flushPolicyForTarget(
                    uid = uid,
                    audioSystemClass = audioSystemClass,
                    playbackMonitorInstance = playbackMonitor,
                )
                if (audioSysOk || monitorOk) {
                    flushedCount++
                    DualTrackLogger.logHit(
                        scope = PolicyConstants.SCOPE_SYSTEM,
                        tag = TAG,
                        packageName = pkg,
                        action = "flush($reason) uid=$uid audioSystem=$audioSysOk playbackMonitor=$monitorOk",
                    )
                }
            } catch (t: Throwable) {
                DualTrackLogger.logError(
                    PolicyConstants.SCOPE_SYSTEM,
                    TAG,
                    "flush_pkg_$pkg",
                    t,
                )
            }
        }
        return flushedCount
    }

    /**
     * Layer 2: Hooks android.media.AudioSystem.setAllowedCapturePolicy(int uid, int flags) —
     * the final JNI gate into native audioserver. Forces flags = 0 (clearing
     * AUDIO_FLAG_NO_MEDIA_PROJECTION and AUDIO_FLAG_NO_SYSTEM_CAPTURE) for allowlisted UIDs.
     */
    private fun hookAudioSystemGate(module: XposedModule, classLoader: ClassLoader): Int {
        val audioSystemClass = HookReflect.findClassOrNull(CLASS_AUDIO_SYSTEM, classLoader)
            ?: HookReflect.findClassOrNull(CLASS_AUDIO_SYSTEM, null)
            ?: return 0

        val targetMethod = selectAudioSystemSetPolicyMethod(audioSystemClass) ?: return 0
        runCatching { module.deoptimize(targetMethod) }

        module.hook(targetMethod).intercept { chain ->
            try {
                val args = chain.args
                if (args.size >= 2 && args[0] is Int && args[1] is Int) {
                    val uid = args[0] as Int
                    val flags = args[1] as Int
                    val matchedPkg = PolicyCache.findAllowlistedPackageForUid(uid) {
                        it.allowAudioCapture
                    }
                    val effectiveFlags = computeEffectiveAudioSystemFlags(
                        currentFlags = flags,
                        isAllowlisted = matchedPkg != null,
                    )
                    if (matchedPkg != null && effectiveFlags != flags) {
                        val updatedArgs = args.toTypedArray()
                        updatedArgs[1] = effectiveFlags
                        DualTrackLogger.logHit(
                            scope = PolicyConstants.SCOPE_SYSTEM,
                            tag = TAG,
                            packageName = matchedPkg,
                            action = "AudioSystem.setAllowedCapturePolicy(uid=$uid, flags=0x${flags.toString(16)}->0)",
                        )
                        return@intercept chain.proceed(updatedArgs)
                    }
                }
            } catch (t: Throwable) {
                DualTrackLogger.logError(
                    PolicyConstants.SCOPE_SYSTEM,
                    TAG,
                    "audioSystem_setAllowedCapturePolicy_cb",
                    t,
                )
            }
            chain.proceed()
        }
        return 1
    }

    private fun selectGenerateAppInfoMethods(pkgInfoUtils: Class<*>): List<Method> {
        val candidates = pkgInfoUtils.declaredMethods.filter { method ->
            method.name == "generateApplicationInfo" &&
                method.returnType == ApplicationInfo::class.java &&
                method.parameterCount >= 3
        }
        return candidates.sortedByDescending { it.parameterCount }.take(2)
    }

    internal fun selectAudioSystemSetPolicyMethod(audioSystemClass: Class<*>): Method? {
        return audioSystemClass.declaredMethods.firstOrNull { method ->
            method.name == "setAllowedCapturePolicy" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true }
    }

    internal fun computeEffectiveAudioSystemFlags(currentFlags: Int, isAllowlisted: Boolean): Int {
        return if (isAllowlisted) AUDIO_SYSTEM_CAPTURE_ALLOW_ALL else currentFlags
    }

    internal fun computeEffectiveServiceCapturePolicy(currentPolicy: Int, isAllowlisted: Boolean): Int {
        return if (isAllowlisted) ALLOW_CAPTURE_BY_ALL else currentPolicy
    }

    internal fun flushPolicyForTarget(
        uid: Int,
        audioSystemClass: Class<*>?,
        playbackMonitorInstance: Any?,
    ): Pair<Boolean, Boolean> {
        val intType = Int::class.javaPrimitiveType!!
        val audioSysOk = if (audioSystemClass != null) {
            try {
                val method = selectAudioSystemSetPolicyMethod(audioSystemClass)
                if (method != null) {
                    method.invoke(null, uid, AUDIO_SYSTEM_CAPTURE_ALLOW_ALL)
                    true
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }

        val monitorOk = if (playbackMonitorInstance != null) {
            try {
                val method = HookReflect.findMethodExactOrNull(
                    playbackMonitorInstance.javaClass,
                    "setAllowedCapturePolicy",
                    intType,
                    intType,
                )
                if (method != null) {
                    method.invoke(playbackMonitorInstance, uid, ALLOW_CAPTURE_BY_ALL)
                    true
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }

        return audioSysOk to monitorOk
    }

    private fun resolvePlaybackActivityMonitorInstance(classLoader: ClassLoader): Any? {
        capturedPlaybackMonitorRef?.get()?.let { return it }

        return try {
            val serviceManager = HookReflect.findClassOrNull(CLASS_SERVICE_MANAGER, classLoader)
                ?: HookReflect.findClassOrNull(CLASS_SERVICE_MANAGER, null)
                ?: return null
            val audioService = HookReflect.callStaticMethodExactOrNull(
                serviceManager,
                "getService",
                arrayOf(String::class.java),
                "audio",
            ) ?: return null

            val playbackMonitorClass = HookReflect.findClassOrNull(CLASS_PLAYBACK_MONITOR, classLoader)

            val byName = HookReflect.getObjectFieldOrNull(audioService, "mPlaybackMonitor")
            if (byName != null && (playbackMonitorClass == null || playbackMonitorClass.isInstance(byName))) {
                capturedPlaybackMonitorRef = WeakReference(byName)
                return byName
            }

            if (playbackMonitorClass != null) {
                var current: Class<*>? = audioService.javaClass
                while (current != null && current != Any::class.java) {
                    for (field in current.declaredFields) {
                        if (playbackMonitorClass.isAssignableFrom(field.type)) {
                            field.isAccessible = true
                            val value = field.get(audioService)
                            if (value != null) {
                                capturedPlaybackMonitorRef = WeakReference(value)
                                return value
                            }
                        }
                    }
                    current = current.superclass
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveUidForPackage(packageName: String, classLoader: ClassLoader): Int {
        // 1. PackageManagerInternal via LocalServices (fastest, authoritative inside system_server)
        try {
            val localServices = HookReflect.findClassOrNull(CLASS_LOCAL_SERVICES, classLoader)
                ?: HookReflect.findClassOrNull(CLASS_LOCAL_SERVICES, null)
            val pmInternalClass = HookReflect.findClassOrNull(CLASS_PACKAGE_MANAGER_INTERNAL, classLoader)
                ?: HookReflect.findClassOrNull(CLASS_PACKAGE_MANAGER_INTERNAL, null)
            if (localServices != null && pmInternalClass != null) {
                val pmInternal = HookReflect.callStaticMethodExactOrNull(
                    localServices,
                    "getService",
                    arrayOf(Class::class.java),
                    pmInternalClass,
                )
                if (pmInternal != null) {
                    val longFlagsUid = HookReflect.callMethodExactOrNull(
                        pmInternal,
                        "getPackageUid",
                        arrayOf(
                            String::class.java,
                            Long::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!,
                        ),
                        packageName,
                        0L,
                        0,
                    ) as? Int
                    if (longFlagsUid != null && longFlagsUid > 0) {
                        return longFlagsUid
                    }
                    val intFlagsUid = HookReflect.callMethodExactOrNull(
                        pmInternal,
                        "getPackageUid",
                        arrayOf(
                            String::class.java,
                            Int::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!,
                        ),
                        packageName,
                        0,
                        0,
                    ) as? Int
                    if (intFlagsUid != null && intFlagsUid > 0) {
                        return intFlagsUid
                    }
                }
            }
        } catch (_: Throwable) {
            // Fall through to PackageManager
        }

        // 2. Fallback to system context PackageManager
        try {
            val pm = PolicyCache.resolveSystemPackageManager()
            if (pm != null) {
                val uid = pm.getPackageUid(packageName, 0)
                if (uid > 0) return uid
            }
        } catch (_: Throwable) {
            // Package may not be installed or PM not ready yet
        }

        return -1
    }

    private fun hookCapturePolicyClass(
        module: XposedModule,
        classLoader: ClassLoader,
        className: String,
        captureMonitor: Boolean,
    ): Int {
        val targetClass = HookReflect.findClassOrNull(className, classLoader) ?: return 0
        var count = 0

        val setTargets = targetClass.declaredMethods.filter { method ->
            method.name == "setAllowedCapturePolicy" &&
                method.parameterCount in 1..2 &&
                method.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }.take(2)

        for (method in setTargets) {
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                try {
                    if (captureMonitor) {
                        chain.thisObject?.let { capturedPlaybackMonitorRef = WeakReference(it) }
                    }
                    val args = chain.args
                    if (args.isNotEmpty()) {
                        val (uid, policyIndex) = when {
                            args.size >= 2 && args[0] is Int && args[1] is Int ->
                                (args[0] as Int) to 1
                            args[0] is Int ->
                                Binder.getCallingUid() to 0
                            else -> null
                        } ?: return@intercept chain.proceed()

                        val matchedPkg = PolicyCache.findAllowlistedPackageForUid(uid) {
                            it.allowAudioCapture
                        }
                        val currentPolicy = args[policyIndex] as Int
                        val effectivePolicy = computeEffectiveServiceCapturePolicy(
                            currentPolicy = currentPolicy,
                            isAllowlisted = matchedPkg != null,
                        )
                        if (matchedPkg != null && effectivePolicy != currentPolicy) {
                            val updatedArgs = args.toTypedArray()
                            updatedArgs[policyIndex] = effectivePolicy
                            DualTrackLogger.logHit(
                                scope = PolicyConstants.SCOPE_SYSTEM,
                                tag = TAG,
                                packageName = matchedPkg,
                                action = "${targetClass.simpleName}.setAllowedCapturePolicy(uid=$uid, $currentPolicy->ALLOW_CAPTURE_BY_ALL)",
                            )
                            return@intercept chain.proceed(updatedArgs)
                        }
                    }
                } catch (t: Throwable) {
                    DualTrackLogger.logError(
                        PolicyConstants.SCOPE_SYSTEM,
                        TAG,
                        "setAllowedCapturePolicy_cb",
                        t,
                    )
                }
                chain.proceed()
            }
            count++
        }

        val getTargets = targetClass.declaredMethods.filter { method ->
            method.name == "getAllowedCapturePolicy" &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.parameterCount in 0..1 &&
                method.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }.take(2)

        for (method in getTargets) {
            runCatching { module.deoptimize(method) }
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (captureMonitor) {
                        chain.thisObject?.let { capturedPlaybackMonitorRef = WeakReference(it) }
                    }
                    val current = result as? Int
                    if (current != null && current != ALLOW_CAPTURE_BY_ALL) {
                        val args = chain.args
                        val uid = if (args.isNotEmpty() && args[0] is Int) {
                            args[0] as Int
                        } else {
                            Binder.getCallingUid()
                        }
                        val matchedPkg = PolicyCache.findAllowlistedPackageForUid(uid) {
                            it.allowAudioCapture
                        }
                        if (matchedPkg != null) {
                            DualTrackLogger.logHit(
                                scope = PolicyConstants.SCOPE_SYSTEM,
                                tag = TAG,
                                packageName = matchedPkg,
                                action = "${targetClass.simpleName}.getAllowedCapturePolicy(uid=$uid)->ALLOW_CAPTURE_BY_ALL",
                            )
                            return@intercept ALLOW_CAPTURE_BY_ALL
                        }
                    }
                } catch (t: Throwable) {
                    DualTrackLogger.logError(
                        PolicyConstants.SCOPE_SYSTEM,
                        TAG,
                        "getAllowedCapturePolicy_cb",
                        t,
                    )
                }
                result
            }
            count++
        }

        return count
    }
}
