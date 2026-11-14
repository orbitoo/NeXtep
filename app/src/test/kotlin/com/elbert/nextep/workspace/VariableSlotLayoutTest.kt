package com.elbert.nextep.workspace

import com.elbert.nextep.display.VirtualDisplaySlot
import com.elbert.nextep.display.WorkspaceSlotCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableSlotLayoutTest {
    @Test
    fun supportedSlotCountsCreateValidPortraitAndLandscapeLayouts() {
        val insets = WorkspaceNavigationInsets(bottom = 120)
        for (slotCount in WorkspaceSlotCount.SUPPORTED) {
            val portrait = WorkspaceLayoutProfile.create(1440, 3200, 560, insets, displayRotation = 0, slotCount = slotCount)
            assertEquals(slotCount, portrait.geometry.slotCount)
            assertEquals(slotCount, portrait.slots.size)
            portrait.slots.forEach { slot ->
                assertTrue("width must be positive for slots=$slotCount", slot.width > 0)
                assertTrue("height must be positive for slots=$slotCount", slot.height > 0)
                assertEquals(portrait.geometry.sidebarWidth - portrait.gapPixels, slot.width)
            }
            for (i in 1 until portrait.slots.size) {
                val prev = portrait.slots[i - 1]
                val curr = portrait.slots[i]
                assertEquals(prev.top + prev.height + portrait.gapPixels, curr.top)
            }
            val last = portrait.slots.last()
            assertEquals(portrait.geometry.sidebarLogicalHeight, last.top + last.height)

            val rotated = portrait.rotatedTo(1)
            assertEquals(slotCount, rotated.geometry.slotCount)
            assertEquals(slotCount, rotated.slots.size)
        }
    }

    @Test
    fun profileEncodeDecodeRoundTripsAcrossAllSupportedSlotCounts() {
        val insets = WorkspaceNavigationInsets(bottom = 96)
        for (slotCount in WorkspaceSlotCount.SUPPORTED) {
            val created = WorkspaceLayoutProfile.create(1080, 2400, 420, insets, displayRotation = 2, slotCount = slotCount)
            val encoded = created.encode()
            val expectedVersion = if (slotCount == WorkspaceSlotCount.DEFAULT) "5" else "6"
            assertTrue(encoded.startsWith("$expectedVersion,"))
            val decoded = WorkspaceLayoutProfile.decode(encoded)
            assertNotNull("decode failed for slotCount=$slotCount", decoded)
            assertEquals(created, decoded)
        }
        assertNull(WorkspaceLayoutProfile.decode("6,1080,2400"))
    }

    @Test
    fun precreatedSurfaceTextureIsReplacedBeforeDisplayCreationOnActivate() {
        assertTrue(
            VirtualDisplaySlot.shouldReplaceTextureOnActivate(
                freshSurfaceRequired = false,
                hasVirtualDisplay = false,
                textureAvailable = true,
            ),
        )
        assertTrue(
            VirtualDisplaySlot.shouldReplaceTextureOnActivate(
                freshSurfaceRequired = true,
                hasVirtualDisplay = false,
                textureAvailable = false,
            ),
        )
        assertFalse(
            VirtualDisplaySlot.shouldReplaceTextureOnActivate(
                freshSurfaceRequired = false,
                hasVirtualDisplay = false,
                textureAvailable = false,
            ),
        )
        assertFalse(
            VirtualDisplaySlot.shouldReplaceTextureOnActivate(
                freshSurfaceRequired = false,
                hasVirtualDisplay = true,
                textureAvailable = true,
            ),
        )
    }

    @Test
    fun previewSurfaceRestorationRunsWheneverSizeAndLayoutAreValid() {
        assertTrue(VirtualDisplaySlot.shouldRestorePreviewSurfaceOnSizeChanged(287, 637, hasLayoutProfile = true))
        assertFalse(VirtualDisplaySlot.shouldRestorePreviewSurfaceOnSizeChanged(0, 637, hasLayoutProfile = true))
        assertFalse(VirtualDisplaySlot.shouldRestorePreviewSurfaceOnSizeChanged(287, 0, hasLayoutProfile = true))
        assertFalse(VirtualDisplaySlot.shouldRestorePreviewSurfaceOnSizeChanged(287, 637, hasLayoutProfile = false))
    }
}
