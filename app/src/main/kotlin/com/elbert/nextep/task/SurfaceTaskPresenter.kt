package com.elbert.nextep.task

import com.elbert.nextep.framework.TaskSurfaceCompat
import com.elbert.nextep.workspace.WorkspaceGeometry

class SurfaceTaskPresenter : MainTaskPresenter {
    fun presentFromFullscreen(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        TaskSurfaceCompat.present(taskId, geometry, animateFromFullscreen = true)

    fun restoreToFullscreen(taskId: Int): Result<Unit> =
        TaskSurfaceCompat.restore(taskId, animateToFullscreen = true)

    override fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        TaskSurfaceCompat.present(taskId, geometry)

    override fun restore(taskId: Int): Result<Unit> = TaskSurfaceCompat.restore(taskId)

    override fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        TaskSurfaceCompat.reapply(taskId, geometry)
}
