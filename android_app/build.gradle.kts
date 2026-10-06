plugins {
    // v5.0.2: 依赖升级（版本号取自 Maven metadata 的最大稳定版，非凭记忆）
    // AGP 8.5.2 → 8.13.2：新的 AndroidX 库（camera 1.6 / core-ktx 1.19 等）在
    // AAR metadata 里明确要求 AGP ≥ 8.9.1，8.5.2 会直接构建失败（46 条 AAR 检查）。
    // 仍留在 AGP 8.x —— AGP 9.x 需要 Gradle 9 + 更大范围的 DSL 迁移，单独一轮做。
    id("com.android.application") version "8.13.2" apply false
    // Kotlin 2.0.21 → 2.4.20：Compose BOM 2026.09 需要更新的 Kotlin 元数据
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
