package com.magfold.cast.show

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 服务 → 界面的单向指令通道。
 *
 * 存在的理由很具体：通知栏那个「停止展出」不该把界面拉到前台再演一遍收起动画 ——
 * 它要的效果是「外屏暗下去，我继续干我的事」。而那个动作由服务收到，
 * 界面侧的状态却在 [ShowState] 里。两者同进程，用一条 SharedFlow 打通最省事。
 *
 * 界面不在（Activity 已销毁）时发送就是空转，本来就没什么可停的，无害。
 */
object CastControl {

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    fun send(command: Command) {
        _commands.tryEmit(command)
    }

    enum class Command {
        /** 收起外屏、停止展出。 */
        STOP_SHOWCASE,
    }
}
