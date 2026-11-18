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

    /**
     * Times worth snapping to: the edges of every clip and of every text, layer and sound, except
     * the item [excludeId] (the one being moved).
     */
    fun snapPoints(project: Project, excludeId: String? = null): List<Long> {
        val points = mutableListOf(0L)
        var edge = 0L
        project.clips.forEach {
            edge += it.durationMs
            points += edge
        }
        project.texts.filter { it.id != excludeId }.forEach { points += it.startMs; points += it.endMs }
        project.layers.filter { it.id != excludeId }.forEach { points += it.startMs; points += it.endMs }
        project.audio.filter { it.id != excludeId }.forEach {
            points += it.startMs
            points += it.startMs + it.durationMs
        }
        return points.distinct().sorted()
    }

    /** Returns the point nearest to [ms] if it is within [toleranceMs], otherwise [ms] itself. */
    /**
     * New position (-1..1) of an item dragged by [deltaPx] along one axis. [framePx] is the frame
     * size and [itemPx] the item size on that axis. Lands exactly on the centre when it is close.
     */
    fun movedBias(bias: Float, deltaPx: Float, framePx: Float, itemPx: Float): Float {
        val free = maxOf(framePx - itemPx, framePx * 0.1f, 1f) / 2f
        val moved = (bias + deltaPx / free).coerceIn(-1f, 1f)
        return if (kotlin.math.abs(moved) < 0.04f) 0f else moved
    }

    /**
     * True when the preview can play [a] straight into [b] as one piece of video: both are cut
     * from the same file with nothing removed between them (a split), at the same speed and pitch.
     */
    fun joinsForPlayback(a: Clip, b: Clip): Boolean =
        a.type == ClipType.VIDEO && b.type == ClipType.VIDEO && a.uri == b.uri &&
            a.trimEndMs == b.trimStartMs && a.effectiveSpeed == b.effectiveSpeed && a.pitch == b.pitch

    /**
     * The clips grouped into the pieces the preview player is given. Clips that were only split
     * apart share one piece, so playback runs through the cut without stopping to reload.
     */
    fun playGroups(clips: List<Clip>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in 1..clips.size) {
            if (i == clips.size || !joinsForPlayback(clips[i - 1], clips[i])) {
                out += start until i
                start = i
            }
        }
        return out
    }

    /**
     * Where the player must go to show clip [index] at [sourceMs] into that clip (in the source
     * file's own time): the piece number and the position inside the piece.
     */
    fun toPlayPosition(clips: List<Clip>, groups: List<IntRange>, index: Int, sourceMs: Long): Pair<Int, Long> {
        val g = groups.indexOfFirst { index in it }.coerceAtLeast(0)
        val first = groups.getOrNull(g)?.first ?: return 0 to sourceMs
        val offset = if (first == index) 0L else clips[index].trimStartMs - clips[first].trimStartMs
        return g to offset + sourceMs
    }

    /** The reverse of [toPlayPosition]: which clip is showing, and how far into it (source time). */
    fun fromPlayPosition(clips: List<Clip>, groups: List<IntRange>, group: Int, positionMs: Long): Pair<Int, Long>? {
        val range = groups.getOrNull(group) ?: return null
        if (range.first == range.last) return range.first to positionMs
        val base = clips[range.first].trimStartMs
        for (i in range) {
            val clip = clips[i]
            if (positionMs < clip.trimEndMs - base || i == range.last) {
                return i to (positionMs - (clip.trimStartMs - base)).coerceAtLeast(0L)
            }
        }
        return null
    }

    /**
     * Removes the stretch [fromMs]..[toMs] from every voice-over in [audio] (other sounds are kept
     * as they are). A voice-over across the stretch is split into the part before and after it.
     */
    fun cutOutVoice(audio: List<AudioClip>, fromMs: Long, toMs: Long): List<AudioClip> {
        if (toMs <= fromMs) return audio
        return audio.flatMap { a ->
            val end = a.startMs + a.durationMs
            if (!a.voice || end <= fromMs || a.startMs >= toMs) return@flatMap listOf(a)
            val parts = mutableListOf<AudioClip>()
            if (fromMs - a.startMs >= MIN_BAR_MS) parts += a.copy(durationMs = fromMs - a.startMs)
            if (end - toMs >= MIN_BAR_MS) {
                parts += a.copy(
                    id = if (parts.isEmpty()) a.id else newId(),
                    startMs = toMs,
                    offsetMs = a.offsetMs + (toMs - a.startMs),
                    durationMs = end - toMs,
                )
            }
            parts
        }
    }

    /** The shortest a text, sticker, layer or sound can be made by dragging its ends. */
    const val MIN_BAR_MS = 300L

    /**
     * New start and end of a bar after one of its ends is dragged by [deltaMs]. The moved end sticks
     * to the nearest of [points] within [toleranceMs], never starts before [minStart] or ends after
     * [maxEnd], and the bar never gets shorter than [MIN_BAR_MS].
     */
    fun resizeEdge(
        startMs: Long,
        endMs: Long,
        atStart: Boolean,
        deltaMs: Long,
        points: List<Long>,
        toleranceMs: Long,
        minStart: Long = 0L,
        maxEnd: Long = Long.MAX_VALUE,
    ): Pair<Long, Long> {
        return if (atStart) {
            val upper = (endMs - MIN_BAR_MS).coerceAtLeast(minStart)
            snap(startMs + deltaMs, points, toleranceMs).coerceIn(minStart, upper) to endMs
        } else {
            val lower = startMs + MIN_BAR_MS
            startMs to snap(endMs + deltaMs, points, toleranceMs).coerceIn(lower, maxOf(lower, maxEnd))
        }
    }

    /** Where the clip playing at [timeMs] ends on the timeline, or the video's end. */
    fun clipEndAt(project: Project, timeMs: Long): Long {
        val (index, _) = project.locate(timeMs) ?: return project.durationMs
        return project.clipStartMs(index) + project.clips[index].durationMs
    }

    fun snap(ms: Long, points: List<Long>, toleranceMs: Long): Long {
        val nearest = points.minByOrNull { kotlin.math.abs(it - ms) } ?: return ms
        return if (kotlin.math.abs(nearest - ms) <= toleranceMs) nearest else ms
    }

    /**
     * Where an item of [lengthMs] should start when dropped at [startMs]: its start snaps to a
     * nearby point, or failing that its end does. Never before 0.
     */
    fun snapStart(startMs: Long, lengthMs: Long, points: List<Long>, toleranceMs: Long): Long {
        val byStart = snap(startMs, points, toleranceMs)
        if (byStart != startMs) return byStart.coerceAtLeast(0)
        val byEnd = snap(startMs + lengthMs, points, toleranceMs)
        return (byEnd - lengthMs).coerceAtLeast(0)
    }

    /**
     * Where [playheadMs] falls inside clip [id], as time from the clip's start, or null when the
     * playhead is outside the clip or too close to an edge for a cut to leave a usable piece.
     */
    fun cutPoint(project: Project, id: String, playheadMs: Long): Long? {
        val index = project.clips.indexOfFirst { it.id == id }
        if (index < 0) return null
        val clip = project.clips[index]
        val local = playheadMs - project.clipStartMs(index)
        // Both sides of the cut must keep at least MIN_PIECE_MS of source footage.
        val before = (local * clip.effectiveSpeed).toLong()
        val after = (clip.trimEndMs - clip.trimStartMs) - before
        return if (before >= MIN_PIECE_MS && after >= MIN_PIECE_MS) local else null
    }

    /** Removes the part of clip [id] before the playhead. Null when the playhead cannot cut it. */
    fun trimLeftOfPlayhead(project: Project, id: String, playheadMs: Long): Project? {
        val local = cutPoint(project, id, playheadMs) ?: return null
        return trimStart(project, id, local)
    }

    /** Removes the part of clip [id] after the playhead. Null when the playhead cannot cut it. */
    fun trimRightOfPlayhead(project: Project, id: String, playheadMs: Long): Project? {
        val local = cutPoint(project, id, playheadMs) ?: return null
        val clip = project.clips.first { it.id == id }
        return trimEnd(project, id, local - clip.durationMs)
    }

    // ------------------------------------------------------------------ keyframes

    /** Keyframes closer together than this count as the same moment. */
    const val KEYFRAME_MERGE_MS = 80L

    /**
     * The pose at [tMs]: [base] when there are no keyframes, the first / last keyframe outside
     * their range, and an even glide between the two neighbours otherwise.
     */
    fun poseAt(keyframes: List<Keyframe>, tMs: Long, base: Keyframe): Keyframe {
        if (keyframes.isEmpty()) return base.copy(tMs = tMs)
        val first = keyframes.first()
        val last = keyframes.last()
        if (tMs <= first.tMs) return first.copy(tMs = tMs)
        if (tMs >= last.tMs) return last.copy(tMs = tMs)
        val after = keyframes.indexOfFirst { it.tMs >= tMs }
        val a = keyframes[after - 1]
        val b = keyframes[after]
        val p = (tMs - a.tMs).toFloat() / (b.tMs - a.tMs).coerceAtLeast(1)
        fun mix(from: Float, to: Float) = from + (to - from) * p
        return Keyframe(
            tMs = tMs,
            x = mix(a.x, b.x),
            y = mix(a.y, b.y),
            scale = mix(a.scale, b.scale),
            rotation = mix(a.rotation, b.rotation),
            opacity = mix(a.opacity, b.opacity),
        )
    }

    /** Adds [key], replacing a keyframe at (nearly) the same moment. The list stays in time order. */
    fun upsertKeyframe(keyframes: List<Keyframe>, key: Keyframe): List<Keyframe> {
        val near = keyframes.firstOrNull { kotlin.math.abs(it.tMs - key.tMs) <= KEYFRAME_MERGE_MS }
        val kept = keyframes.filterNot { it === near }
        return (kept + key.copy(tMs = near?.tMs ?: key.tMs)).sortedBy { it.tMs }
    }

    /** Moves every keyframe [byMs] earlier, for the second half of a split or a left trim. */
    private fun shiftKeyframes(keyframes: List<Keyframe>, byMs: Long): List<Keyframe> =
        keyframes.map { it.copy(tMs = it.tMs - byMs) }

    // --------------------------------------------------------------- clip order, slip

    /** Moves clip [id] so that it sits at [index] among the clips (0 = first). */
    fun moveTo(project: Project, id: String, index: Int): Project {
        val from = project.clips.indexOfFirst { it.id == id }
        if (from < 0) return project
        val to = index.coerceIn(0, project.clips.lastIndex)
        if (to == from) return project
        return project.copy(clips = project.clips.toMutableList().apply { add(to, removeAt(from)) })
    }

    /**
     * Slip: plays a different part of the source video without changing the clip's length or
     * place. [newTrimStartMs] is clamped so the window stays inside the source.
     */
    fun slip(project: Project, id: String, newTrimStartMs: Long): Project {
        val clip = project.clips.firstOrNull { it.id == id } ?: return project
        if (clip.type != ClipType.VIDEO) return project
        val length = clip.trimEndMs - clip.trimStartMs
        val start = newTrimStartMs.coerceIn(0, (clip.sourceDurationMs - length).coerceAtLeast(0))
        if (start == clip.trimStartMs) return project
        return project.copy(clips = project.clips.map {
            if (it.id == id) it.copy(trimStartMs = start, trimEndMs = start + length) else it
        })
    }

    /**
     * Time from [startMs] at which the playhead cuts an item lasting until [endMs], or null when
     * the playhead is outside it or too close to an edge to leave a usable piece.
     */
    fun rangeCutPoint(startMs: Long, endMs: Long, playheadMs: Long): Long? {
        val local = playheadMs - startMs
        return if (local >= MIN_PIECE_MS && endMs - playheadMs >= MIN_PIECE_MS) local else null
    }

    /** Trims or splits a text / sticker at the playhead. Null when the playhead cannot cut it. */
    fun cutText(project: Project, id: String, playheadMs: Long, cut: Cut, newItemId: String = newId()): Project? {
        val item = project.texts.firstOrNull { it.id == id } ?: return null
        rangeCutPoint(item.startMs, item.endMs, playheadMs) ?: return null
        val shifted = shiftKeyframes(item.keyframes, playheadMs - item.startMs)
        val pieces = when (cut) {
            Cut.LEFT -> listOf(item.copy(startMs = playheadMs, keyframes = shifted))
            Cut.RIGHT -> listOf(item.copy(endMs = playheadMs))
            Cut.SPLIT -> listOf(
                item.copy(endMs = playheadMs, animOut = OverlayAnim.NONE),
                item.copy(id = newItemId, startMs = playheadMs, animIn = OverlayAnim.NONE, keyframes = shifted),
            )
        }
        return project.copy(texts = project.texts.flatMap { if (it.id == id) pieces else listOf(it) })
    }

    /** Trims or splits an image layer at the playhead. */
    fun cutLayer(project: Project, id: String, playheadMs: Long, cut: Cut, newItemId: String = newId()): Project? {
        val item = project.layers.firstOrNull { it.id == id } ?: return null
        rangeCutPoint(item.startMs, item.endMs, playheadMs) ?: return null
        val shifted = shiftKeyframes(item.keyframes, playheadMs - item.startMs)
        val pieces = when (cut) {
            Cut.LEFT -> listOf(item.copy(startMs = playheadMs, keyframes = shifted))
            Cut.RIGHT -> listOf(item.copy(endMs = playheadMs))
            Cut.SPLIT -> listOf(
                item.copy(endMs = playheadMs, animOut = OverlayAnim.NONE),
                item.copy(id = newItemId, startMs = playheadMs, animIn = OverlayAnim.NONE, keyframes = shifted),
            )
        }
        return project.copy(layers = project.layers.flatMap { if (it.id == id) pieces else listOf(it) })
    }

    /** Trims or splits a video layer at the playhead, keeping the picture in step with time. */
    fun cutVideoLayer(
        project: Project, id: String, playheadMs: Long, cut: Cut, newItemId: String = newId(),
    ): Project? {
        val item = project.videoLayers.firstOrNull { it.id == id } ?: return null
        val local = rangeCutPoint(item.startMs, item.startMs + item.durationMs, playheadMs) ?: return null
        val firstPart = item.copy(trimEndMs = item.trimStartMs + local)
        val secondPart = item.copy(
            trimStartMs = item.trimStartMs + local,
            startMs = playheadMs,
            keyframes = shiftKeyframes(item.keyframes, local),
        )
        val pieces = when (cut) {
            Cut.LEFT -> listOf(secondPart)
            Cut.RIGHT -> listOf(firstPart)
            Cut.SPLIT -> listOf(firstPart, secondPart.copy(id = newItemId))
        }
        return project.copy(videoLayers = project.videoLayers.flatMap { if (it.id == id) pieces else listOf(it) })
    }

    /** Trims or splits a voice-over / sound clip at the playhead. */
    fun cutAudio(project: Project, id: String, playheadMs: Long, cut: Cut, newItemId: String = newId()): Project? {
        val item = project.audio.firstOrNull { it.id == id } ?: return null
        val local = rangeCutPoint(item.startMs, item.startMs + item.durationMs, playheadMs) ?: return null
        val firstPart = item.copy(durationMs = local, fadeOutMs = 0)
        val secondPart = item.copy(
            offsetMs = item.offsetMs + local,
            startMs = playheadMs,
            durationMs = item.durationMs - local,
            fadeInMs = 0,
        )
        val pieces = when (cut) {
            // A plain trim keeps the fade the user set on the side that stays.
            Cut.LEFT -> listOf(secondPart.copy(fadeInMs = item.fadeInMs))
            Cut.RIGHT -> listOf(firstPart.copy(fadeOutMs = item.fadeOutMs))
            Cut.SPLIT -> listOf(firstPart, secondPart.copy(id = newItemId))
        }
        return project.copy(audio = project.audio.flatMap { if (it.id == id) pieces else listOf(it) })
    }

    /**
     * Trims the background music at the playhead: [Cut.LEFT] makes it start there (carrying on
     * from the same point in the song), [Cut.RIGHT] makes it stop there. Music is one track, so
     * it cannot be split.
     */
    fun cutMusic(project: Project, playheadMs: Long, cut: Cut): Project? {
        val music = project.music ?: return null
        if (cut == Cut.SPLIT) return null
        rangeCutPoint(music.startMs, music.windowEnd(project.durationMs), playheadMs) ?: return null
        val trimmed = if (cut == Cut.LEFT) {
            music.copy(startMs = playheadMs, offsetMs = music.filePosition(playheadMs), fadeInMs = music.fadeInMs)
        } else {
            music.copy(stopMs = playheadMs)
        }
        return project.copy(music = trimmed)
    }

    /** Slides the music along the timeline without changing which part of the song plays. */
    fun shiftMusic(project: Project, deltaMs: Long): Project {
        val music = project.music ?: return project
        val start = (music.startMs + deltaMs).coerceAtLeast(0)
        val moved = start - music.startMs
        return project.copy(
            music = music.copy(startMs = start, stopMs = if (music.stopMs > 0) music.stopMs + moved else 0)
        )
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

/** A cut at the playhead: keep the right side, split in two, or keep the left side. */
enum class Cut { LEFT, SPLIT, RIGHT }

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
