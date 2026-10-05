package com.opencode.android.util

import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.ProjectInfo

/**
 * 阶段 1/P2: 项目管理纯变换（从 onProjectsDataReceived + toggleFavoriteProject 抽出）。
 *
 * 收藏落盘（prefsManager.saveFavoriteProjects）留在 ViewModel。
 */
object ProjectsReducer {

    /** 收藏切换：已收藏则取消，否则加入（返回新集合，不修改入参） */
    fun toggleFavorite(current: Set<String>, projectId: String): Set<String> =
        if (current.contains(projectId)) current - projectId else current + projectId

    /** onProjectsDataReceived 的纯状态部分（favorites 由调用方从 prefs 取） */
    fun applyProjectsData(
        state: OpenCodeUiState,
        projects: List<ProjectInfo>,
        projectsError: String?,
        favorites: Set<String>
    ): OpenCodeUiState = state.copy(
        projects = projects,
        projectsError = projectsError,
        favoriteProjectIds = favorites
    )
}
