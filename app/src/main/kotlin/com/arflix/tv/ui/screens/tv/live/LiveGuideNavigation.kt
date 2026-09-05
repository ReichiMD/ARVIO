package com.arflix.tv.ui.screens.tv.live

/**
 * D-pad decisions for the Live TV guide, kept out of the composable so they can
 * be unit tested. [EpgGrid] is one very large @Composable, so anything expressed
 * inline there can only be checked on a device — and these are exactly the rules
 * users reported as broken (the selector dying mid-list, the guide jumping a
 * page instead of scrolling a row).
 */
object LiveGuideNavigation {

    /** What a single up/down press on the channel column should do. */
    sealed interface ChannelStep {
        /** Move the selector to this row of the currently loaded window. */
        data class Focus(val index: Int) : ChannelStep

        /** The window has to grow towards the start of the list first. */
        data object LoadPrevious : ChannelStep

        /** The window has to grow towards the end of the list first. */
        data object LoadNext : ChannelStep

        /** Nothing to move to — there are no rows at all. */
        data object Ignore : ChannelStep
    }

    /**
     * Resolves one up/down step.
     *
     * [anchorIndex] is null when the row the selector sat on is not in the
     * currently loaded window. That is not an edge case: the guide loads a
     * sliding window and drops rows off the front while the user pages down, so
     * the anchor routinely falls out during ordinary navigation. Treating it as
     * "do nothing" is what left the guide frozen with Back as the only working
     * button — land on the edge the user is moving towards instead.
     */
    fun channelStep(anchorIndex: Int?, delta: Int, rowCount: Int): ChannelStep = when {
        rowCount <= 0 -> ChannelStep.Ignore
        anchorIndex == null -> ChannelStep.Focus(if (delta >= 0) 0 else rowCount - 1)
        anchorIndex + delta < 0 -> ChannelStep.LoadPrevious
        anchorIndex + delta >= rowCount -> ChannelStep.LoadNext
        else -> ChannelStep.Focus(anchorIndex + delta)
    }

    /**
     * Pixels the channel list must scroll so a row spanning [rowTop]..[rowBottom]
     * is fully inside the viewport — 0 when it already is.
     *
     * The guide used to call `scrollToItem(row)` on every step, which parks the
     * focused row at the very top of the viewport. Stepping down one row past the
     * bottom edge therefore threw the whole page upwards, which is the "it moves
     * in pages and drops to the next one instead of scrolling" report. Scrolling
     * by the overlap moves exactly one row.
     */
    fun scrollDeltaToRevealRow(
        rowTop: Int,
        rowBottom: Int,
        viewportStart: Int,
        viewportEnd: Int,
    ): Int = when {
        rowTop < viewportStart -> rowTop - viewportStart
        rowBottom > viewportEnd -> rowBottom - viewportEnd
        else -> 0
    }

    /**
     * Index to scroll to when the target row is not composed at all (a jump from
     * search, or a window that moved far). Rows above the viewport go to the top;
     * rows below it are brought to the bottom edge, so the surrounding rows the
     * user was just looking at stay on screen.
     */
    fun offscreenScrollIndex(target: Int, firstVisible: Int, fullyVisibleRows: Int): Int =
        if (target <= firstVisible) {
            target.coerceAtLeast(0)
        } else {
            (target - fullyVisibleRows.coerceAtLeast(1) + 1).coerceAtLeast(0)
        }
}
