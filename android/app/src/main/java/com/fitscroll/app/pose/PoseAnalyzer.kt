package com.fitscroll.app.pose

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.max

/** One tracked joint, in the coordinate space of the rotated analysis image. */
data class Joint(val x: Float, val y: Float, val confidence: Float)

/**
 * A skeleton ready to draw, plus the image dimensions needed to map it onto the
 * preview. Kept separate from [PoseMetrics] because the overlay wants every
 * joint while the counter only wants three angles.
 */
data class SkeletonFrame(
    val joints: Map<Int, Joint>,
    val imageWidth: Int,
    val imageHeight: Int,
)

/** What one analysed camera frame yielded. */
data class PoseResult(
    val skeleton: SkeletonFrame?,
    val metrics: PoseMetrics?,
)

/**
 * Runs ML Kit pose detection over the CameraX analysis stream.
 *
 * Everything is on-device — the model ships inside the APK and no frame ever
 * leaves the phone.
 */
class PoseAnalyzer(private val onResult: (PoseResult) -> Unit) : ImageAnalysis.Analyzer {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            // STREAM_MODE tracks between frames, which is both faster and far
            // steadier than re-detecting from scratch on every frame.
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build(),
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        // ML Kit reports landmarks in the *rotated* frame, so the dimensions we
        // hand to the overlay have to be swapped for portrait rotations or the
        // skeleton lands sideways on the preview.
        val quarterTurned = rotation == 90 || rotation == 270
        val width = if (quarterTurned) imageProxy.height else imageProxy.width
        val height = if (quarterTurned) imageProxy.width else imageProxy.height

        detector.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener { pose -> onResult(interpret(pose, width, height)) }
            .addOnFailureListener { onResult(PoseResult(skeleton = null, metrics = null)) }
            .addOnCompleteListener { imageProxy.close() }
    }

    fun release() = detector.close()

    private fun interpret(pose: Pose, width: Int, height: Int): PoseResult {
        val joints = DRAWN_LANDMARKS.mapNotNull { type ->
            pose.getPoseLandmark(type)?.let { landmark ->
                type to Joint(
                    x = landmark.position.x,
                    y = landmark.position.y,
                    confidence = landmark.inFrameLikelihood,
                )
            }
        }.toMap()

        if (joints.isEmpty()) return PoseResult(null, null)

        val skeleton = SkeletonFrame(joints, width, height)
        return PoseResult(skeleton, metricsFrom(pose))
    }

    /**
     * Reduces a skeleton to the elbow and body-line angles.
     *
     * Both sides are measured and blended by confidence rather than picking one
     * arm: in a side-on push-up the far arm is partly occluded, and trusting it
     * alone produces angles that swing wildly as the model guesses.
     */
    private fun metricsFrom(pose: Pose): PoseMetrics? {
        val left = sideMetrics(
            pose,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.LEFT_KNEE,
        )
        val right = sideMetrics(
            pose,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.RIGHT_KNEE,
        )

        return when {
            left == null -> right
            right == null -> left
            else -> {
                val weight = left.confidence + right.confidence
                val bodyWeight = left.bodyConfidence + right.bodyConfidence
                if (weight <= 0f) {
                    left
                } else {
                    PoseMetrics(
                        elbowAngle = (left.elbowAngle * left.confidence +
                            right.elbowAngle * right.confidence) / weight,
                        // The body line is blended by torso confidence rather
                        // than arm confidence, so a clearly visible arm cannot
                        // lend authority to a guessed knee on the same side.
                        bodyLineAngle = if (bodyWeight <= 0f) {
                            left.bodyLineAngle
                        } else {
                            (left.bodyLineAngle * left.bodyConfidence +
                                right.bodyLineAngle * right.bodyConfidence) / bodyWeight
                        },
                        // The clearer side vouches for the blend; averaging the
                        // confidences would let an occluded limb suppress a
                        // perfectly good reading.
                        confidence = max(left.confidence, right.confidence),
                        bodyConfidence = max(left.bodyConfidence, right.bodyConfidence),
                    )
                }
            }
        }
    }

    private fun sideMetrics(
        pose: Pose,
        shoulderType: Int,
        elbowType: Int,
        wristType: Int,
        hipType: Int,
        kneeType: Int,
    ): PoseMetrics? {
        val shoulder = pose.getPoseLandmark(shoulderType) ?: return null
        val elbow = pose.getPoseLandmark(elbowType) ?: return null
        val wrist = pose.getPoseLandmark(wristType) ?: return null
        val hip = pose.getPoseLandmark(hipType) ?: return null
        val knee = pose.getPoseLandmark(kneeType) ?: return null

        return PoseMetrics(
            elbowAngle = Geometry.angle(
                shoulder.position.x, shoulder.position.y,
                elbow.position.x, elbow.position.y,
                wrist.position.x, wrist.position.y,
            ),
            bodyLineAngle = Geometry.angle(
                shoulder.position.x, shoulder.position.y,
                hip.position.x, hip.position.y,
                knee.position.x, knee.position.y,
            ),
            // Counting only needs the arm chain. Folding the knee into this
            // would block rep counting entirely for anyone whose legs sit
            // outside the frame, which is a very ordinary way to prop a phone.
            confidence = minOf(
                shoulder.inFrameLikelihood,
                elbow.inFrameLikelihood,
                wrist.inFrameLikelihood,
            ),
            bodyConfidence = minOf(
                shoulder.inFrameLikelihood,
                hip.inFrameLikelihood,
                knee.inFrameLikelihood,
            ),
        )
    }

    companion object {
        /** Landmarks the overlay draws. Face points beyond the nose add clutter. */
        val DRAWN_LANDMARKS = listOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
        )

        /** Bones drawn between joints, grouped so the overlay can colour them. */
        val ARM_BONES = listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
        )

        /** The plank line — what turns red when the hips sag. */
        val BODY_BONES = listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
        )

        val FRAME_BONES = listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
        )
    }
}
