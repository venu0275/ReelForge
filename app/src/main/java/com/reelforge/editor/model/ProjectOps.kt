package com.reelforge.editor.model

/** Pure editing operations on a [Project]. Kept free of Android code so they can be unit tested. */
object ProjectOps {

    /** A split must leave at least this much source footage on each side. */
    const val MIN_PIECE_MS = 200L

    /** Splits the clip under [playheadMs] in two. Returns null when the cut is too near an edge. */
    fun split(project: Project, playheadMs: Long, newClipId: String = newId()): Project? {
        val (index, local) = project.locate(playheadMs) ?: return null
        val clip = project.clips[index]
        val sourceOffset = (local * clip.effectiveSpeed).toLong()
        val length = clip.trimEndMs - clip.trimStartMs
        if (sourceOffset < MIN_PIECE_MS || length - sourceOffset < MIN_PIECE_MS) return null
        val cut = clip.trimStartMs + sourceOffset
        val first = clip.copy(trimEndMs = cut, fadeOutMs = 0)
        val second = clip.copy(id = newClipId, trimStartMs = cut, fadeInMs = 0)
        return project.copy(clips = project.clips.toMutableList().apply {
            set(index, first)
            add(index + 1, second)
        })
    }

    fun duplicate(project: Project, id: String, newClipId: String = newId()): Project {
        val index = project.clips.indexOfFirst { it.id == id }
        if (index < 0) return project
        return project.copy(
            clips = project.clips.toMutableList().apply {
                add(index + 1, project.clips[index].copy(id = newClipId))
            }
        )
    }

    /** Moves a clip [delta] places along the track; out-of-range moves leave the project as is. */
    fun move(project: Project, id: String, delta: Int): Project {
        val from = project.clips.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in project.clips.indices) return project
        return project.copy(clips = project.clips.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun delete(project: Project, id: String): Project =
        project.copy(clips = project.clips.filterNot { it.id == id })

    /** Fades the clip out and the next one in. A duration of 0 removes the transition. */
    fun transitionAfter(project: Project, id: String, durationMs: Long): Project {
        val index = project.clips.indexOfFirst { it.id == id }
        if (index < 0 || index >= project.clips.lastIndex) return project
        val wanted = durationMs.coerceAtLeast(0)
        return project.copy(clips = project.clips.mapIndexed { i, c ->
            when (i) {
                index -> c.copy(fadeOutMs = wanted.coerceAtMost(c.durationMs / 2))
                index + 1 -> c.copy(fadeInMs = wanted.coerceAtMost(c.durationMs / 2))
                else -> c
            }
        })
    }

    /** Keeps each fade no longer than half the clip. */
    fun clampFades(clip: Clip): Clip {
        val half = (clip.durationMs / 2).coerceAtLeast(0)
        return clip.copy(
            fadeInMs = clip.fadeInMs.coerceIn(0, half),
            fadeOutMs = clip.fadeOutMs.coerceIn(0, half),
        )
    }
}

/**
 * Undo / redo history. Changes recorded within [coalesceMs] of each other (dragging a slider)
 * collapse into one undo step.
 */
class History<T>(private val limit: Int = 60, private val coalesceMs: Long = 700) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    private var lastRecordAt = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Call before applying a change; [before] is the state being replaced. */
    fun record(before: T, nowMs: Long) {
        if (nowMs - lastRecordAt > coalesceMs || undoStack.isEmpty()) {
            undoStack.addLast(before)
            if (undoStack.size > limit) undoStack.removeFirst()
        }
        lastRecordAt = nowMs
        redoStack.clear()
    }

    fun undo(current: T): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastRecordAt = 0
        return previous
    }

    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastRecordAt = 0
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastRecordAt = 0
    }
}
