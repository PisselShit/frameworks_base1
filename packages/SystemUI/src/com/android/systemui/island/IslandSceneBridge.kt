/*
 * SPDX-FileCopyrightText: 2025 DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.island

import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import javax.inject.Inject

/**
 * Bridges IslandView with the Scene Container path when [SceneContainer][com.android.systemui.scene.shared.flag.SceneContainerFlag] is enabled.
 *
 * When SceneContainer is on, [ShadeSurfaceImpl] is used as [ShadeViewController] instead of
 * [NotificationPanelViewController]. The empty impl no-ops [showIsland][com.android.systemui.shade.ShadeViewController.showIsland],
 * so the island never appears. This bridge wires [IslandView] from the shade window and forwards
 * showIsland / updateIslandVisibility so the island works with the Compose/scene pipeline.
 */
@SysUISingleton
class IslandSceneBridge @Inject constructor() {

    private var islandView: IslandView? = null
    private var wired = false

    /**
     * Wire the island from the shade window. Call when the layout is ready (e.g. from
     * [NotificationShadeWindowViewController.setupExpandedStatusBar]).
     * No-op if already wired or if island/NSSL not found.
     */
    fun wire(
        windowRoot: View,
        nssl: NotificationStackScrollLayout,
        headsUpManager: HeadsUpManager,
    ) {
        if (wired) return
        val island = windowRoot.findViewById<View>(R.id.notification_island) as? IslandView
            ?: return
        island.setScroller(nssl)
        island.setHeadsupManager(headsUpManager)
        islandView = island
        wired = true
    }

    fun showIsland(show: Boolean, expandedFraction: Float) {
        islandView?.showIsland(show, expandedFraction)
    }

    fun updateIslandVisibility(expandedFraction: Float) {
        islandView?.updateIslandVisibility(expandedFraction)
    }

    fun isWired(): Boolean = wired
}
