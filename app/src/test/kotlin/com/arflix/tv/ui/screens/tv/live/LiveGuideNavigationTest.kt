package com.arflix.tv.ui.screens.tv.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the guide navigation users reported as broken on TV: the selector dying
 * when the loaded window slid out from under it ("it resets, goes back to the top
 * and then you can only press back"), and the guide jumping a whole page instead
 * of scrolling one row.
 */
class LiveGuideNavigationTest {

    // ── Stepping through the channel column ───────────────────────────────

    @Test
    fun stepsToTheNextRow() {
        val step = LiveGuideNavigation.channelStep(anchorIndex = 4, delta = +1, rowCount = 18)

        assertThat(step).isEqualTo(LiveGuideNavigation.ChannelStep.Focus(5))
    }

    @Test
    fun stepsToThePreviousRow() {
        val step = LiveGuideNavigation.channelStep(anchorIndex = 4, delta = -1, rowCount = 18)

        assertThat(step).isEqualTo(LiveGuideNavigation.ChannelStep.Focus(3))
    }

    @Test
    fun asksForMoreRowsPastTheEndOfTheWindow() {
        val step = LiveGuideNavigation.channelStep(anchorIndex = 17, delta = +1, rowCount = 18)

        assertThat(step).isEqualTo(LiveGuideNavigation.ChannelStep.LoadNext)
    }

    @Test
    fun asksForMoreRowsBeforeTheStartOfTheWindow() {
        val step = LiveGuideNavigation.channelStep(anchorIndex = 0, delta = -1, rowCount = 18)

        assertThat(step).isEqualTo(LiveGuideNavigation.ChannelStep.LoadPrevious)
    }

    @Test
    fun landsOnTheFirstRowWhenTheAnchorFellOutOfTheWindowGoingDown() {
        // The window drops rows off the front while paging, so the row the selector
        // sat on is regularly gone. This used to swallow the key press and leave
        // the guide dead until the user pressed back.
        val step = LiveGuideNavigation.channelStep(anchorIndex = null, delta = +1, rowCount = 18)

        assertThat(step).isEqualTo(LiveGuideNavigation.ChannelStep.Focus(0))
    }

    @Test
    fun landsOnTheLastRowWhenTheAnchorFellOutOfTheWindowGoingUp() {
        val step = LiveGuideNavigation.channelStep(anchorIndex = null, delta = -1, rowCount = 18)

        assertThat(step).isEqualTo(LiveGuideNavigation.ChannelStep.Focus(17))
    }

    @Test
    fun ignoresStepsWhenThereAreNoRows() {
        assertThat(LiveGuideNavigation.channelStep(anchorIndex = null, delta = +1, rowCount = 0))
            .isEqualTo(LiveGuideNavigation.ChannelStep.Ignore)
    }

    // ── Scrolling one row instead of a page ───────────────────────────────

    @Test
    fun doesNotScrollARowThatIsAlreadyVisible() {
        val delta = LiveGuideNavigation.scrollDeltaToRevealRow(
            rowTop = 120,
            rowBottom = 204,
            viewportStart = 0,
            viewportEnd = 800,
        )

        assertThat(delta).isEqualTo(0)
    }

    @Test
    fun scrollsExactlyTheOverlapWhenTheRowPokesOutAtTheBottom() {
        val delta = LiveGuideNavigation.scrollDeltaToRevealRow(
            rowTop = 760,
            rowBottom = 844,
            viewportStart = 0,
            viewportEnd = 800,
        )

        assertThat(delta).isEqualTo(44)
    }

    @Test
    fun scrollsBackTheOverlapWhenTheRowPokesOutAtTheTop() {
        val delta = LiveGuideNavigation.scrollDeltaToRevealRow(
            rowTop = -30,
            rowBottom = 54,
            viewportStart = 0,
            viewportEnd = 800,
        )

        assertThat(delta).isEqualTo(-30)
    }

    @Test
    fun bringsAnOffscreenRowBelowToTheBottomEdgeNotTheTop() {
        val index = LiveGuideNavigation.offscreenScrollIndex(
            target = 30,
            firstVisible = 10,
            fullyVisibleRows = 9,
        )

        assertThat(index).isEqualTo(22)
    }

    @Test
    fun bringsAnOffscreenRowAboveStraightIntoView() {
        val index = LiveGuideNavigation.offscreenScrollIndex(
            target = 3,
            firstVisible = 10,
            fullyVisibleRows = 9,
        )

        assertThat(index).isEqualTo(3)
    }

    @Test
    fun neverScrollsToANegativeIndex() {
        val index = LiveGuideNavigation.offscreenScrollIndex(
            target = 2,
            firstVisible = 1,
            fullyVisibleRows = 9,
        )

        assertThat(index).isEqualTo(0)
    }
}
