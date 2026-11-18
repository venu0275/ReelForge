package com.reelforge.editor.model

/**
 * Speed ramps, beat sync and templates. Plain Kotlin on the project data, so all of it can be
 * tested without a phone.
 */
object CreativeOps {

    /** Clips shorter than this cannot be ramped. */
    const val MIN_RAMP_SOURCE_MS = 1_000L

    /** How many steps a ramp is made of. More steps change speed more smoothly. */
    const val RAMP_STEPS = 8

    /**
     * Replaces a video clip with [steps] back-to-back pieces whose speeds follow [ramp], so the
     * speed changes inside what looks like one clip. Fades stay at the outer ends. The first piece
     * keeps the clip's id, so it stays selected.
     */
    fun speedRamp(project: Project, clipId: String, ramp: SpeedRamp, steps: Int = RAMP_STEPS): Project {
        val index = project.clips.indexOfFirst { it.id == clipId }
        if (index < 0) return project
        val clip = project.clips[index]
        val source = clip.trimEndMs - clip.trimStartMs
        if (clip.type != ClipType.VIDEO || source < MIN_RAMP_SOURCE_MS) return project
        val n = steps.coerceIn(2, 16)
        val pieces = (0 until n).map { i ->
            val from = clip.trimStartMs + source * i / n
            val to = if (i == n - 1) clip.trimEndMs else clip.trimStartMs + source * (i + 1) / n
            val speed = (clip.speed * ramp.curve((i + 0.5f) / n)).coerceIn(0.1f, 16f)
            clip.copy(
                id = if (i == 0) clip.id else newId(),
                trimStartMs = from,
                trimEndMs = to,
                speed = speed,
                fadeInMs = if (i == 0) clip.fadeInMs else 0,
                fadeOutMs = if (i == n - 1) clip.fadeOutMs else 0,
            )
        }
        // Fades longer than the outer pieces are shortened to fit.
        val fitted = pieces.mapIndexed { i, c ->
            when (i) {
                0 -> c.copy(fadeInMs = minOf(c.fadeInMs, c.durationMs / 2))
                n - 1 -> c.copy(fadeOutMs = minOf(c.fadeOutMs, c.durationMs / 2))
                else -> c
            }
        }
        val list = project.clips.toMutableList()
        list.removeAt(index)
        list.addAll(index, fitted)
        return project.copy(clips = list)
    }

    /**
     * Where the music's beats fall on the timeline: the beats found in the file, placed where the
     * file plays (repeating if the music loops), inside the part of the timeline the music covers.
     */
    fun beatTimes(project: Project): List<Long> {
        val music = project.music ?: return emptyList()
        if (music.beats.isEmpty()) return emptyList()
        val end = music.windowEnd(project.durationMs)
        val length = music.durationMs
        val looping = music.loop && length > 0
        // The timeline time at which the file's own 0:00 would play on the first pass.
        var shift = music.startMs - if (looping) music.offsetMs % length else music.offsetMs
        val out = mutableListOf<Long>()
        while (shift <= end) {
            for (b in music.beats) {
                val t = shift + b
                if (t > end) break
                if (t >= music.startMs) out += t
            }
            if (!looping) break
            shift += length
        }
        return out.distinct().sorted()
    }

    /**
     * Lengthens or shortens clips so each cut lands on a beat. [every] picks every 1st, 2nd or
     * 4th beat. Photos can be any length. A video can only be shortened, or lengthened up to the
     * end of its file; if no beat fits, it is left as it is. Clips never get shorter than
     * [minMs].
     */
    fun cutToBeats(project: Project, beats: List<Long>, every: Int = 1, minMs: Long = 400): Project {
        val grid = beats.sorted().filterIndexed { i, _ -> i % every.coerceAtLeast(1) == 0 }
        if (grid.isEmpty() || project.clips.isEmpty()) return project
        var start = 0L
        val clips = project.clips.map { clip ->
            val current = clip.durationMs
            val maxMs = if (clip.type == ClipType.IMAGE) Long.MAX_VALUE
            else ((clip.sourceDurationMs - clip.trimStartMs) / clip.effectiveSpeed).toLong()
            val candidates = grid.filter { it - start >= minMs && it - start <= maxMs }
            val target = candidates.minByOrNull { kotlin.math.abs((it - start) - current) }
            val result = if (target == null) clip else {
                val length = target - start
                val sourceLength = (length * clip.effectiveSpeed).toLong()
                val trimmed = if (clip.type == ClipType.IMAGE) {
                    clip.copy(sourceDurationMs = maxOf(clip.sourceDurationMs, clip.trimStartMs + sourceLength),
                        trimEndMs = clip.trimStartMs + sourceLength)
                } else {
                    clip.copy(trimEndMs = (clip.trimStartMs + sourceLength).coerceAtMost(clip.sourceDurationMs))
                }
                ProjectOps.clampFades(trimmed)
            }
            start += result.durationMs
            result
        }
        return project.copy(clips = clips)
    }

    /** Beats per minute from beat times (averaged over the whole song), or 0 if too few. */
    fun bpm(beats: List<Long>): Int {
        if (beats.size < 4) return 0
        val sorted = beats.sorted()
        val span = sorted.last() - sorted.first()
        if (span <= 0) return 0
        return Math.round(60_000.0 * (sorted.size - 1) / span).toInt()
    }
}
