package com.fitscroll.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.fitscroll.app.pose.Joint
import com.fitscroll.app.pose.PoseAnalyzer
import com.fitscroll.app.pose.SkeletonFrame
import com.fitscroll.app.ui.theme.Crimson
import com.fitscroll.app.ui.theme.Lime
import com.fitscroll.app.ui.theme.TextMuted
import kotlin.math.max

/**
 * Draws the tracked skeleton over the camera preview.
 *
 * This is the app's honesty mechanism. Without it a rejected rep is
 * indistinguishable from a broken app, so the overlay shows exactly what the
 * detector sees and colours the two things it judges:
 *
 *  - the arms brighten as you approach the depth your strictness level demands
 *  - the plank line turns red the moment your hips fall outside tolerance
 */
@Composable
fun SkeletonOverlay(
    frame: SkeletonFrame?,
    formOk: Boolean,
    depth: Float,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val skeleton = frame ?: return@Canvas
        if (skeleton.imageWidth <= 0 || skeleton.imageHeight <= 0) return@Canvas

        val project = projector(skeleton, size, mirrored)

        val armColour = lerp(TextMuted.copy(alpha = 0.85f), Lime, depth)
        val bodyColour = if (formOk) Lime.copy(alpha = 0.9f) else Crimson

        drawBones(skeleton, PoseAnalyzer.FRAME_BONES, TextMuted.copy(alpha = 0.5f), FRAME_WIDTH, project)
        drawBones(skeleton, PoseAnalyzer.BODY_BONES, bodyColour, BODY_WIDTH, project)
        drawBones(skeleton, PoseAnalyzer.ARM_BONES, armColour, ARM_WIDTH, project)

        skeleton.joints.values
            .filter { it.confidence >= MIN_CONFIDENCE }
            .forEach { joint ->
                val at = project(joint)
                drawCircle(color = Color.White.copy(alpha = 0.85f), radius = JOINT_RADIUS, center = at)
                drawCircle(color = bodyColour, radius = JOINT_RADIUS * 0.55f, center = at)
            }
    }
}

/**
 * Maps landmark coordinates onto the preview.
 *
 * PreviewView fills its bounds and centre-crops, so the same
 * scale-by-the-larger-ratio-then-centre transform has to be applied here or the
 * skeleton drifts away from the body near the frame edges. The front camera
 * preview is mirrored by CameraX while ML Kit's coordinates are not, hence the
 * explicit horizontal flip.
 */
private fun projector(
    frame: SkeletonFrame,
    canvas: Size,
    mirrored: Boolean,
): (Joint) -> Offset {
    val scale = max(canvas.width / frame.imageWidth, canvas.height / frame.imageHeight)
    val offsetX = (canvas.width - frame.imageWidth * scale) / 2f
    val offsetY = (canvas.height - frame.imageHeight * scale) / 2f

    return { joint ->
        val x = joint.x * scale + offsetX
        Offset(
            x = if (mirrored) canvas.width - x else x,
            y = joint.y * scale + offsetY,
        )
    }
}

private fun DrawScope.drawBones(
    frame: SkeletonFrame,
    bones: List<Pair<Int, Int>>,
    colour: Color,
    width: Float,
    project: (Joint) -> Offset,
) {
    bones.forEach { (startType, endType) ->
        val start = frame.joints[startType] ?: return@forEach
        val end = frame.joints[endType] ?: return@forEach
        // A limb the model is guessing at should not be drawn as though it were
        // measured; a confident-looking wrong skeleton is worse than a gap.
        if (start.confidence < MIN_CONFIDENCE || end.confidence < MIN_CONFIDENCE) return@forEach

        drawLine(
            color = colour,
            start = project(start),
            end = project(end),
            strokeWidth = width,
            cap = Stroke.DefaultCap,
        )
    }
}

private const val MIN_CONFIDENCE = 0.35f
private const val JOINT_RADIUS = 7f
private const val ARM_WIDTH = 12f
private const val BODY_WIDTH = 10f
private const val FRAME_WIDTH = 6f
