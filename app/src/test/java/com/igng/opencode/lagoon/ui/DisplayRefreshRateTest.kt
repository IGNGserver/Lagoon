package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayRefreshRateTest {
  @Test
  fun selects144HzModeMatchingCurrentPhysicalResolution() {
    val modes = listOf(
      DisplayModeSpec(modeId = 1, physicalWidth = 1440, physicalHeight = 3200, refreshRate = 60f),
      DisplayModeSpec(modeId = 2, physicalWidth = 1440, physicalHeight = 3200, refreshRate = 120f),
      DisplayModeSpec(modeId = 3, physicalWidth = 1440, physicalHeight = 3200, refreshRate = 144f),
      // Lower-resolution high-rate mode must not force a resolution switch when QHD+ is active.
      DisplayModeSpec(modeId = 4, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 165f)
    )
    val chosen = pickPreferredDisplayMode(1440, 3200, modes, HighRefreshPolicy.MAX)
    assertEquals(DisplayModeSpec(3, 1440, 3200, 144f), chosen)
  }

  @Test
  fun fallsBackToHighestOverallWhenActiveDimensionsAreUnlisted() {
    val modes = listOf(
      DisplayModeSpec(modeId = 10, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f),
      DisplayModeSpec(modeId = 11, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 144f)
    )
    val chosen = pickPreferredDisplayMode(0, 0, modes, HighRefreshPolicy.MAX)
    assertEquals(11, chosen?.modeId)
  }

  @Test
  fun systemPolicyLeavesDisplayModeUnconstrained() {
    val modes = listOf(
      DisplayModeSpec(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 144f)
    )
    assertNull(pickPreferredDisplayMode(1080, 2400, modes, HighRefreshPolicy.SYSTEM))
    assertNull(pickPreferredDisplayMode(1080, 2400, emptyList(), HighRefreshPolicy.MAX))
  }
}
