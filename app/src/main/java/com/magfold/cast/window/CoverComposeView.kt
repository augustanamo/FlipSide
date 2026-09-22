package com.magfold.cast.window

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 外屏会话里的 Compose 容器。
 *
 * 必须先解决一件事：外屏的 View 挂在另一个窗口/容器上，**拿不到 Activity 的
 * ViewTreeLifecycleOwner**，直接 setContent 会抛
 * "Composed into the View which doesn't propagate ViewTreeLifecycleOwner!"。
 * 所以这里自己实现三个 Owner 并挂到视图树上。
 *
 * 挂载用的是 Kotlin 扩展函数 `setViewTreeLifecycleOwner(...)` 这类写法，
 * 而**不是** `ViewTreeLifecycleOwner.set(view, owner)`。
 * 后者那个类确实存在、也是 public，但那是 `@file:JvmName("ViewTreeLifecycleOwner")`
 * 生成的 **JVM 层门面类**，在 Kotlin 里根本不是个符号 —— 直接 import 会报
 * "Unresolved reference"，而报错信息完全不会提示你原因。踩过一次，记在这。
 *
 * ## detach 不能等于死亡（这条是"进后台外屏黑"的一半原因）
 *
 * 外屏容器会被系统**反复 detach / re-attach**（应用切到后台、折叠姿态变化、
 * 会话被系统重建）。默认行为在这种情况下是灾难，而且两个默认值都踩：
 *
 *  1. **`AbstractComposeView` 默认的组合策略是 `DisposeOnDetachedFromWindowOrReleasedFromPool`**
 *     （在 compose-ui 1.12.1 的字节码上核过：`ViewCompositionStrategy.Companion.getDefault()`
 *     返回的就是它）。也就是说 **View 一 detach，组合当场被销毁、内容被清空** ——
 *     再 attach 回来也只是一块空 View，外屏就是黑的。
 *     所以这里显式改成 `DisposeOnLifecycleDestroyed(this)`：只有我们自己的生命周期
 *     走到 DESTROYED 才销毁组合，detach 不算。
 *  2. 早先的实现还在 `onDetachedFromWindow` 里把自己的生命周期打成 DESTROYED，
 *     而 `onAttachedToWindow` 又"不敢"把 DESTROYED 拉回 RESUMED（防重复 attach 的
 *     死循环），于是这个 View 一旦 detach 过就**永久作废**。
 *     现在 detach 只退到 CREATED（组合还在、帧时钟暂停），re-attach 回到 RESUMED
 *     就能继续画 —— detach 变成了一次暂停，而不是一次死亡。
 *
 * 真正要销毁时由 [WindowAreaBridge] 调 [disposeNow]（它换掉某一份内容、或者彻底
 * 收起外屏时会这么干），别指望 detach 帮你收拾，否则暂停中的组合会一直挂着。
 */
class CoverComposeView(
    context: Context,
    private val content: @Composable () -> Unit,
    /** detach 时通知外面。桥用它记一笔日志（排查"外屏黑"时这是关键证据）。 */
    private val onDetached: (() -> Unit)? = null,
) : AbstractComposeView(context, null, 0),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        // 三个都要挂：缺 LifecycleOwner 会在附加到窗口时直接抛异常，
        // 缺另外两个则是在用到 rememberSaveable / viewModel() 时才炸。
        setViewTreeLifecycleOwner(this)
        setViewTreeViewModelStoreOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        // detach 不再销毁组合，见类注释
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this))
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 从"暂停"里回来：把帧时钟重新放开，组合继续画。
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            Log.d(TAG, "外屏 Compose 容器已 attach，恢复渲染")
        }
    }

    override fun onDetachedFromWindow() {
        // 只暂停，不销毁：detach 在后台/姿态变化时是常态，见类注释第 2 条。
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        Log.d(TAG, "外屏 Compose 容器已 detach（只暂停，不销毁）")
        onDetached?.invoke()
        super.onDetachedFromWindow()
    }

    /** 彻底销毁：换掉这份内容、或收起外屏时由桥调用。 */
    fun disposeNow() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    @Composable
    override fun Content() {
        content()
    }

    private companion object {
        private const val TAG = "CoverComposeView"
    }
}
