package com.opencode.android.coordinator

import com.opencode.android.data.model.OpenCodeUiState

/**
 * 阶段 2: 把 ViewModel 的状态调度能力抽象成接口。
 *
 * Coordinator 只依赖这个接口做三件事：
 * - [updateState]：原 `_uiState.update { }`
 * - [launch]：原 `viewModelScope.launch { }`
 * - [getString]：原 `getApplication().getString(...)`
 *
 * 生产实现由 ViewModel 提供；单测用假实现，在纯 JVM 环境下
 * 验证 Coordinator 的调用时序（含并发与失败路径），不需要 Android 运行环境。
 */
interface StateDispatcher {
    /** 当前 UI 状态（原 `_uiState.value`），只读。 */
    val currentState: OpenCodeUiState

    /** 原 `_uiState.update(transform)`。实现必须保证 transform 的原子应用语义。 */
    fun updateState(transform: (OpenCodeUiState) -> OpenCodeUiState)

    /** 原 `viewModelScope.launch { block() }`。假实现可捕获 block 以确定性执行。 */
    fun launch(block: suspend () -> Unit)

    /** 原 `getApplication().getString(resId, args)`。 */
    fun getString(resId: Int, vararg args: Any): String
}
