package com.nuvio.tv.ui.screens

import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.sports.SportsRepository
import com.nuvio.tv.livetv.sports.SportsSettings
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceModeSelectionViewModelTest {

    private val experience = mockk<ExperienceModeDataStore>(relaxed = true)
    private val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
    private val liveTv = mockk<LiveTvPreferences>(relaxed = true)
    private val sports = mockk<SportsRepository>(relaxed = true)
    private val liveTransform = slot<(LiveTvSettings) -> LiveTvSettings>()
    private val sportsTransform = slot<(SportsSettings) -> SportsSettings>()

    private fun viewModel(): ExperienceModeSelectionViewModel {
        coEvery { liveTv.updateSettings(capture(liveTransform)) } just Runs
        every { sports.updateSettings(capture(sportsTransform)) } just Runs
        return ExperienceModeSelectionViewModel(experience, layout, liveTv, sports)
    }

    @Test
    fun `a new Essential setup starts with Live TV and Sports off`() = runTest {
        viewModel().choose(ExperienceMode.ESSENTIAL)

        assertTrue(LiveTvSettings().showInSidebar)
        assertFalse(liveTransform.captured(LiveTvSettings()).showInSidebar)
        assertTrue(SportsSettings().showInSidebar)
        assertFalse(sportsTransform.captured(SportsSettings()).showInSidebar)
    }

    @Test
    fun `Advanced leaves Live TV and Sports as they are`() = runTest {
        viewModel().choose(ExperienceMode.ADVANCED)

        coVerify(exactly = 0) { liveTv.updateSettings(any()) }
        verify(exactly = 0) { sports.updateSettings(any()) }
    }
}
