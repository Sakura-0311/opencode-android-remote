package com.opencode.android.util

import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.ProjectInfo
import org.junit.Assert.*
import org.junit.Test

/**
 * 阶段 1/P2: ProjectsReducer 单测（先补测试再拆）。
 */
class ProjectsReducerTest {

    private val base = OpenCodeUiState()

    @Test fun toggleFavorite_addsWhenAbsent() {
        assertEquals(setOf("p1"), ProjectsReducer.toggleFavorite(emptySet(), "p1"))
    }

    @Test fun toggleFavorite_removesWhenPresent() {
        assertEquals(setOf("p2"), ProjectsReducer.toggleFavorite(setOf("p1", "p2"), "p1"))
    }

    @Test fun toggleFavorite_doesNotMutateInput() {
        val input = setOf("p1")
        ProjectsReducer.toggleFavorite(input, "p2")
        assertEquals(setOf("p1"), input)
    }

    @Test fun toggleFavorite_emptyProjectId() {
        // 边界：空 id 也按普通集合语义处理，不抛异常
        assertEquals(setOf(""), ProjectsReducer.toggleFavorite(emptySet(), ""))
    }

    @Test fun applyProjectsData_fullState() {
        val projects = listOf(ProjectInfo(id = "p1", name = "n1"))
        val out = ProjectsReducer.applyProjectsData(base, projects, null, setOf("p1"))
        assertEquals(projects, out.projects)
        assertNull(out.projectsError)
        assertEquals(setOf("p1"), out.favoriteProjectIds)
        assertTrue(base.projects.isEmpty()) // 不变性
    }

    @Test fun applyProjectsData_withError() {
        val out = ProjectsReducer.applyProjectsData(base, emptyList(), "ERR", emptySet())
        assertEquals("ERR", out.projectsError)
        assertTrue(out.projects.isEmpty())
    }
}
