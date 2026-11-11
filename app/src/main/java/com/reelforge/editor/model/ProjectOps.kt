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

    /** Shortest clip an edge drag can leave, in source time. */
    const val MIN_CLIP_MS = 300L

    /** Longest time a photo can be stretched to by dragging its edge. */
    const val MAX_PHOTO_MS = 60_000L

    private fun replaceClip(project: Project, updated: Clip): Project =
        project.copy(clips = project.clips.map { if (it.id == updated.id) clampFades(updated) else it })

    /**
     * Drags a clip's start edge by [deltaMs] of timeline time. Positive values cut footage off the
     * start; negative values bring earlier footage back.
     */
    fun trimStart(project: Project, id: String, deltaMs: Long): Project {
        val clip = project.clips.firstOrNull { it.id == id } ?: return project
        if (clip.type == ClipType.IMAGE) return resizePhoto(project, clip, -deltaMs)
        val highest = clip.trimEndMs - MIN_CLIP_MS
        if (highest < 0) return project
        val moved = clip.trimStartMs + (deltaMs * clip.speed).toLong()
        return replaceClip(project, clip.copy(trimStartMs = moved.coerceIn(0, highest)))
    }

    /**
     * Drags a clip's end edge by [deltaMs] of timeline time. Positive values bring back later
     * footage (or lengthen a photo); negative values shorten the clip.
     */
    fun trimEnd(project: Project, id: String, deltaMs: Long): Project {
        val clip = project.clips.firstOrNull { it.id == id } ?: return project
        if (clip.type == ClipType.IMAGE) return resizePhoto(project, clip, deltaMs)
        val lowest = clip.trimStartMs + MIN_CLIP_MS
        if (lowest > clip.sourceDurationMs) return project
        val moved = clip.trimEndMs + (deltaMs * clip.speed).toLong()
        return replaceClip(project, clip.copy(trimEndMs = moved.coerceIn(lowest, clip.sourceDurationMs)))
    }

    private fun resizePhoto(project: Project, clip: Clip, growMs: Long): Project {
        val length = (clip.trimEndMs - clip.trimStartMs + growMs).coerceIn(MIN_CLIP_MS, MAX_PHOTO_MS)
        return replaceClip(
            project,
            clip.copy(trimStartMs = 0, trimEndMs = length, sourceDurationMs = length),
        )
    }

    /**
     * Swaps a clip's media but keeps its edits (filter, speed, fades and so on). The new clip
     * starts from the beginning of the new media and keeps the old length where it fits.
     */
    fun replaceMedia(
        project: Project,
        id: String,
        uri: String,
        type: ClipType,
        mediaDurationMs: Long,
        hasAudio: Boolean,
    ): Project {
        val clip = project.clips.firstOrNull { it.id == id } ?: return project
        val oldLength = clip.trimEndMs - clip.trimStartMs
        val length = if (type == ClipType.IMAGE) {
            // Keep the same time on screen.
            clip.durationMs.coerceIn(MIN_CLIP_MS, MAX_PHOTO_MS)
        } else {
            oldLength.coerceAtMost(mediaDurationMs).coerceAtLeast(1)
        }
        return replaceClip(
            project,
            clip.copy(
                uri = uri,
                type = type,
                sourceDurationMs = if (type == ClipType.IMAGE) length else mediaDurationMs,
                trimStartMs = 0,
                trimEndMs = length,
                hasAudio = hasAudio,
            ),
        )
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
