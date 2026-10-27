package com.onlasdan.studio.ui.framework

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * State and physics-based nested scroll controller for floating docks and toolbars.
 *
 * Implements smooth continuous dragging, inertia, velocity-based flings, and
 * spring settling inspired by AndroidX FloatingToolbar exitAlwaysScrollBehavior.
 *
 * @param maxOffsetPx Maximum distance in pixels the dock can translate downward (offscreen).
 * @param coroutineScope Scope for launching spring snapping and settling animations.
 * @param snapAnimationSpec Spring spec used when settling to fully visible (0f) or hidden (maxOffsetPx).
 */
@Stable
class PhysicsDockScrollBehavior(
    var maxOffsetPx: Float,
    private val coroutineScope: CoroutineScope,
    private val snapAnimationSpec: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
) {
    private val animatableOffset = Animatable(0f)

    /**
     * Current translation Y in pixels (0f = fully visible, maxOffsetPx = hidden below viewport).
     */
    val offsetPx: Float
        get() = animatableOffset.value

    /**
     * Fraction of collapse from 0f (fully visible) to 1f (fully hidden).
     */
    val collapseFraction: Float
        get() = if (maxOffsetPx > 0f) (animatableOffset.value / maxOffsetPx).coerceIn(0f, 1f) else 0f

    /**
     * NestedScrollConnection that intercepts scroll and fling gestures from scrollable containers.
     */
    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (maxOffsetPx <= 0f) return Offset.Zero

            // When scrolling down (content moves up, available.y < 0):
            // toolbar translates downward toward maxOffsetPx (exits).
            if (available.y < 0f) {
                val prevValue = animatableOffset.value
                val newValue = (prevValue - available.y).coerceIn(0f, maxOffsetPx)
                val consumedY = prevValue - newValue // negative or zero
                coroutineScope.launch {
                    animatableOffset.snapTo(newValue)
                }
                return Offset(0f, consumedY)
            }

            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            if (maxOffsetPx <= 0f) return Offset.Zero

            // When scrolling up / pulling down (content moves down, available.y > 0):
            // toolbar reverses movement and re-enters immediately.
            if (available.y > 0f) {
                val prevValue = animatableOffset.value
                val newValue = (prevValue - available.y).coerceIn(0f, maxOffsetPx)
                val consumedY = prevValue - newValue // positive or zero
                coroutineScope.launch {
                    animatableOffset.snapTo(newValue)
                }
                return Offset(0f, consumedY)
            }

            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (maxOffsetPx <= 0f) return Velocity.Zero

            val velocityY = available.y
            // Settle according to fling velocity or current offset midpoint
            val targetOffset = when {
                velocityY < -350f -> maxOffsetPx // fast scroll down -> hide dock smoothly
                velocityY > 350f -> 0f            // fast scroll up -> reveal dock smoothly
                animatableOffset.value > (maxOffsetPx * 0.45f) -> maxOffsetPx
                else -> 0f
            }

            animatableOffset.animateTo(
                targetValue = targetOffset,
                animationSpec = snapAnimationSpec,
                initialVelocity = -velocityY
            )

            return Velocity.Zero
        }
    }

    /**
     * Instantly or smoothly resets the dock to fully visible.
     */
    fun show(animate: Boolean = true) {
        coroutineScope.launch {
            if (animate) {
                animatableOffset.animateTo(0f, snapAnimationSpec)
            } else {
                animatableOffset.snapTo(0f)
            }
        }
    }

    /**
     * Updates maxOffsetPx dynamically when density or container insets change.
     */
    fun updateMaxOffset(newMaxOffsetPx: Float) {
        if (maxOffsetPx != newMaxOffsetPx) {
            maxOffsetPx = newMaxOffsetPx
            if (animatableOffset.value > newMaxOffsetPx && newMaxOffsetPx > 0f) {
                coroutineScope.launch {
                    animatableOffset.snapTo(newMaxOffsetPx)
                }
            }
        }
    }
}

/**
 * Creates and remembers a [PhysicsDockScrollBehavior] with natural inertia and spring settling.
 *
 * @param maxOffsetPx Maximum translation distance in pixels.
 */
@Composable
fun rememberPhysicsDockScrollBehavior(
    maxOffsetPx: Float = 300f
): PhysicsDockScrollBehavior {
    val coroutineScope = rememberCoroutineScope()
    val behavior = remember {
        PhysicsDockScrollBehavior(
            maxOffsetPx = maxOffsetPx,
            coroutineScope = coroutineScope
        )
    }
    behavior.updateMaxOffset(maxOffsetPx)
    return behavior
}
