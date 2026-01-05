package com.example.movenet

import android.util.Log
import kotlin.math.*

// 标准动作定义
enum class StandardAction {
    STANDING,
    SQUATTING,
    JUMPING_JACK,
    HORSE_STANCE,
    ARMS_RAISED,
    ARMS_EXTENDED,
    HANDS_ON_HIPS,
    ARMS_CROSSED,
    UNKNOWN
}

data class ActionResult(
    val action: StandardAction,
    val confidence: Float,
    val corrections: List<String> = emptyList()
)

class ActionDetector {

    // 简单的“站立基准”缓存，用于按个人比例自适应阈值
    private var baselineShoulderWidth: Float = 0f
    private var baselineHeight: Float = 0f

    fun detectAction(person: Person): ActionResult {
        if (person.keyPoints.size < 17) {
            return ActionResult(StandardAction.UNKNOWN, 0f)
        }

        // 提取关键点
        val keyPointMap = person.keyPoints.associateBy { it.bodyPart }
        val validScore = 0.3f // 提高置信度阈值，更严格

        // 检查是否有足够的有效关键点（需要更多关键点来保证准确性）
        val validKeyPoints = person.keyPoints.filter { it.score > validScore }
        if (validKeyPoints.size < 12) {
            return ActionResult(StandardAction.UNKNOWN, 0f)
        }

        // 获取关键点坐标
        val leftShoulderY = keyPointMap[BodyPart.LEFT_SHOULDER]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightShoulderY = keyPointMap[BodyPart.RIGHT_SHOULDER]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val leftShoulderX = keyPointMap[BodyPart.LEFT_SHOULDER]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightShoulderX = keyPointMap[BodyPart.RIGHT_SHOULDER]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val noseY = keyPointMap[BodyPart.NOSE]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        
        val leftWristY = keyPointMap[BodyPart.LEFT_WRIST]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightWristY = keyPointMap[BodyPart.RIGHT_WRIST]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val leftWristX = keyPointMap[BodyPart.LEFT_WRIST]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightWristX = keyPointMap[BodyPart.RIGHT_WRIST]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        
        val leftElbowY = keyPointMap[BodyPart.LEFT_ELBOW]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightElbowY = keyPointMap[BodyPart.RIGHT_ELBOW]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        
        val leftHipY = keyPointMap[BodyPart.LEFT_HIP]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightHipY = keyPointMap[BodyPart.RIGHT_HIP]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val leftHipX = keyPointMap[BodyPart.LEFT_HIP]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightHipX = keyPointMap[BodyPart.RIGHT_HIP]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val leftKneeY = keyPointMap[BodyPart.LEFT_KNEE]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightKneeY = keyPointMap[BodyPart.RIGHT_KNEE]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val leftKneeX = keyPointMap[BodyPart.LEFT_KNEE]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightKneeX = keyPointMap[BodyPart.RIGHT_KNEE]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val leftAnkleY = keyPointMap[BodyPart.LEFT_ANKLE]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightAnkleY = keyPointMap[BodyPart.RIGHT_ANKLE]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val leftAnkleX = keyPointMap[BodyPart.LEFT_ANKLE]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightAnkleX = keyPointMap[BodyPart.RIGHT_ANKLE]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)

        // 脚部关键点置信度过低时不进行识别，避免下肢缺失造成误判
        val footScoreThreshold = 0.35f
        val leftAnkleScore = keyPointMap[BodyPart.LEFT_ANKLE]?.score ?: 0f
        val rightAnkleScore = keyPointMap[BodyPart.RIGHT_ANKLE]?.score ?: 0f
        val leftKneeScore = keyPointMap[BodyPart.LEFT_KNEE]?.score ?: 0f
        val rightKneeScore = keyPointMap[BodyPart.RIGHT_KNEE]?.score ?: 0f
        if (leftAnkleScore < footScoreThreshold || rightAnkleScore < footScoreThreshold ||
            leftKneeScore < 0.25f || rightKneeScore < 0.25f) {
            return ActionResult(StandardAction.UNKNOWN, 0f, listOf("下肢关键点置信度过低"))
        }

        val shoulderY = (leftShoulderY + rightShoulderY) / 2
        val wristY = (leftWristY + rightWristY) / 2
        val hipY = (leftHipY + rightHipY) / 2
        val kneeY = (leftKneeY + rightKneeY) / 2
        val ankleY = (leftAnkleY + rightAnkleY) / 2

        // 计算各部分距离与比例（使用个人基准）
        val shoulderWidth = abs(rightShoulderX - leftShoulderX)
        val hipWidth = abs(rightHipX - leftHipX)
        val ankleWidth = abs(rightAnkleX - leftAnkleX)
        val kneeWidth = abs(rightKneeX - leftKneeX)
        val torsoLength = hipY - shoulderY
        val totalHeight = (listOf(
            keyPointMap[BodyPart.NOSE],
            keyPointMap[BodyPart.LEFT_ANKLE],
            keyPointMap[BodyPart.RIGHT_ANKLE]
        ).mapNotNull { it?.coordinate?.second }.maxOrNull() ?: ankleY) -
            (listOf(
                keyPointMap[BodyPart.NOSE],
                keyPointMap[BodyPart.LEFT_SHOULDER],
                keyPointMap[BodyPart.RIGHT_SHOULDER]
            ).mapNotNull { it?.coordinate?.second }.minOrNull() ?: shoulderY)

        // 更新个人基准（站立且手臂未水平时）
        val isLikelyStanding = getAngle(keyPointMap[BodyPart.LEFT_HIP], keyPointMap[BodyPart.LEFT_KNEE], keyPointMap[BodyPart.LEFT_ANKLE]) > 165 &&
                getAngle(keyPointMap[BodyPart.RIGHT_HIP], keyPointMap[BodyPart.RIGHT_KNEE], keyPointMap[BodyPart.RIGHT_ANKLE]) > 165 &&
                abs(leftWristY - leftShoulderY) > 40 && abs(rightWristY - rightShoulderY) > 40
        if (isLikelyStanding) {
            baselineShoulderWidth = if (baselineShoulderWidth == 0f) shoulderWidth else (baselineShoulderWidth * 0.7f + shoulderWidth * 0.3f)
            baselineHeight = if (baselineHeight == 0f) totalHeight else (baselineHeight * 0.7f + totalHeight * 0.3f)
        }

        val refShoulder = if (baselineShoulderWidth > 0f) baselineShoulderWidth else shoulderWidth.coerceAtLeast(1f)
        val refHeight = if (baselineHeight > 0f) baselineHeight else totalHeight.coerceAtLeast(1f)
        
        // 手臂检测
        val leftArmHorizontal = abs(leftWristY - leftShoulderY) < 40 // 左手腕与肩膀Y坐标接近（水平）
        val rightArmHorizontal = abs(rightWristY - rightShoulderY) < 40 // 右手腕与肩膀Y坐标接近（水平）
        val leftArmExtended = abs(leftWristX - leftShoulderX) > shoulderWidth * 0.8 // 左臂向外伸展
        val rightArmExtended = abs(rightWristX - rightShoulderX) > shoulderWidth * 0.8 // 右臂向外伸展
        val wristsAboveShoulder = leftWristY < shoulderY - refHeight * 0.05f && rightWristY < shoulderY - refHeight * 0.05f
        
        // 调试日志
        Log.d("ActionDetector", "肩宽=$shoulderWidth, 基准肩宽=$baselineShoulderWidth, 身高=$totalHeight, 基准身高=$baselineHeight")

        // 计算膝盖角度
        val leftKneeAngle = getAngle(keyPointMap[BodyPart.LEFT_HIP], keyPointMap[BodyPart.LEFT_KNEE], keyPointMap[BodyPart.LEFT_ANKLE])
        val rightKneeAngle = getAngle(keyPointMap[BodyPart.RIGHT_HIP], keyPointMap[BodyPart.RIGHT_KNEE], keyPointMap[BodyPart.RIGHT_ANKLE])
        
        // 计算髋部角度 (用于判断身体是否挺直)
        val leftHipAngle = getAngle(keyPointMap[BodyPart.LEFT_SHOULDER], keyPointMap[BodyPart.LEFT_HIP], keyPointMap[BodyPart.LEFT_KNEE])
        val rightHipAngle = getAngle(keyPointMap[BodyPart.RIGHT_SHOULDER], keyPointMap[BodyPart.RIGHT_HIP], keyPointMap[BodyPart.RIGHT_KNEE])

        // 站立检测优化：使用角度判断更准确
        // 1. 膝盖角度大 (腿直) > 160度
        // 2. 髋部角度大 (腰直) > 160度
        // 3. 手臂没有水平举起
        val isStanding = leftKneeAngle > 160 && rightKneeAngle > 160 &&
                         leftHipAngle > 160 && rightHipAngle > 160 &&
                         !leftArmHorizontal && !rightArmHorizontal

        // 水平举臂检测：双臂水平向两侧伸展
        val isArmsExtended = leftArmHorizontal && rightArmHorizontal && // 双手与肩膀同高
                             leftArmExtended && rightArmExtended && // 双臂向外伸展
                             abs(leftElbowY - leftShoulderY) < 50 && // 肘部也接近水平
                             abs(rightElbowY - rightShoulderY) < 50

        Log.d("ActionDetector", "站立=$isStanding, 水平举臂=$isArmsExtended")

        // 深蹲检测
        // 深蹲：膝角小、髋角也明显折叠，且双脚不要过宽（避免与扎马步混淆）
        val squatLegsNotTooWide = ankleWidth < shoulderWidth * 1.25f && ankleWidth < hipWidth * 1.25f
        val squatKneesBent = leftKneeAngle < 135 && rightKneeAngle < 135 && leftKneeAngle > 40 && rightKneeAngle > 40
        val squatHipsFold = leftHipAngle < 150 && rightHipAngle < 150
        val isSquatting = squatLegsNotTooWide && squatKneesBent && squatHipsFold

        // 扎马步：双脚分开、膝弯约90度、上身直立，允许轻微更宽（基于个人肩宽/身高）
        val horseLegsWide = ankleWidth > refShoulder * 1.20f || kneeWidth > refShoulder * 1.15f || ankleWidth > hipWidth * 1.15f
        val horseKneesBent = leftKneeAngle in 85f..135f && rightKneeAngle in 85f..135f
        val horseTorsoUpright = leftHipAngle > 145 && rightHipAngle > 145
        val horseKneesAligned = abs(leftKneeY - rightKneeY) < refHeight * 0.10f
        val isHorseStance = horseLegsWide && horseKneesBent && horseTorsoUpright && horseKneesAligned

        // 开合跳评分制：更宽容的标准动作
        val armsOverHeadScore = scoreOverHead(wristY = wristY, shoulderY = shoulderY, noseY = noseY, refHeight = refHeight)
        val legSpreadRatio = ankleWidth / refShoulder
        val legSpreadScore = when {
            legSpreadRatio >= 1.22f -> 1f
            legSpreadRatio >= 1.12f -> 0.6f
            else -> 0f
        }
        val legStraightScore = ((minOf(leftKneeAngle, rightKneeAngle) - 130f) / 40f).coerceIn(0f, 1f)
        val jjScore = armsOverHeadScore + legSpreadScore + legStraightScore
        val isJumpingJack = armsOverHeadScore >= 0.65f && wristsAboveShoulder && legSpreadRatio >= 1.12f && jjScore >= 2.1f

        // 深蹲评分制（避免误判扎马步）：脚不过宽 + 膝弯明显 + 髋折叠
        val isSquatNarrow = legSpreadRatio < 1.12f // 窄站距优先归为深蹲
        val squatWidthScore = scoreRange(ankleWidth / refShoulder, 1.0f, 1.18f, 0.08f, invert = true)
        val squatKneeScore = scoreRange(minOf(leftKneeAngle, rightKneeAngle), 55f, 130f, 16f, invert = true)
        val squatHipScore = scoreRange(minOf(leftHipAngle, rightHipAngle), 115f, 150f, 10f, invert = true)
        val squatScore = squatWidthScore + squatKneeScore + squatHipScore
        val isSquatScore = isSquatNarrow && squatScore >= 1.75f

        // 扎马步评分制（依赖前面的深蹲评分以避免误判）
        val horseSpreadRatio = ankleWidth / refShoulder
        val horseSpreadScore = when {
            horseSpreadRatio >= 1.24f -> 1f
            horseSpreadRatio >= 1.14f -> 0.6f
            else -> 0f
        }
        val horseKneeScore = scoreRange(minOf(leftKneeAngle, rightKneeAngle), 88f, 120f, 6f)
        val horseHipScore = scoreRange(minOf(leftHipAngle, rightHipAngle), 158f, 178f, 6f)
        val horseAlignScore = scoreRange((abs(leftKneeY - rightKneeY)), 0f, refHeight * 0.10f, refHeight * 0.05f, invert = true)
        val horseDepthScore = scoreRange((kneeY - ankleY) / refHeight, 0.18f, 0.30f, 0.04f)
        val horseScore = horseSpreadScore + horseKneeScore + horseHipScore + horseAlignScore + horseDepthScore
        val squatGateStrong = squatWidthScore > 0.65f && squatKneeScore > 0.65f && squatHipScore > 0.65f
        val isSquatLikely = squatScore >= 1.6f || squatGateStrong
        val armsDown = !wristsAboveShoulder && armsOverHeadScore < 0.4f
        val horseHardGate = armsDown && horseSpreadRatio >= 1.16f && horseKneeScore > 0.40f && horseHipScore > 0.40f && horseDepthScore > 0.40f
        val isHorseStanceScore = (horseHardGate && horseScore >= 2.3f && !isSquatLikely)

        Log.d("ActionDetector", "JJ score=$jjScore, Horse score=$horseScore, Squat score=$squatScore")

        // 根据条件判断动作
        return when {
            isJumpingJack -> {
                val corrections = mutableListOf<String>()
                if (!wristsAboveShoulder) corrections.add("双手举过肩再继续动作")
                else if (armsOverHeadScore < 1f) corrections.add("双手再抬高，接近头顶上方")
                if (legSpreadScore < 1f) corrections.add("双脚再分开一些")
                if (legStraightScore < 0.8f) corrections.add("双腿尽量伸直")
                ActionResult(StandardAction.JUMPING_JACK, (0.6f + jjScore * 0.15f).coerceAtMost(0.95f), corrections)
            }
            isHorseStanceScore -> {
                val corrections = mutableListOf<String>()
                if (horseSpreadScore < 1f) corrections.add("双脚再分开一些")
                if (horseKneeScore < 1f) corrections.add("膝盖弯曲到接近直角")
                if (horseHipScore < 1f) corrections.add("上身保持直立")
                if (horseAlignScore < 0.8f) corrections.add("左右膝高度保持一致")
                ActionResult(StandardAction.HORSE_STANCE, (0.6f + horseScore * 0.15f).coerceAtMost(0.95f), corrections)
            }
            isSquatScore -> {
                val corrections = mutableListOf<String>()
                if (abs(leftKneeAngle - rightKneeAngle) > 20) corrections.add("保持双腿弯曲程度一致")
                if (squatWidthScore < 0.8f) corrections.add("双脚稍收拢，避免过宽")
                if (squatHipScore < 0.8f) corrections.add("臀部下沉，髋部再折叠")
                ActionResult(StandardAction.SQUATTING, (0.6f + squatScore * 0.15f).coerceAtMost(0.9f), corrections)
            }
            isArmsExtended -> {
                val corrections = mutableListOf<String>()
                if (abs(leftWristY - rightWristY) > 30) corrections.add("两手保持同一高度")
                if (abs(leftShoulderY - rightShoulderY) > 20) corrections.add("保持肩膀水平")
                if (abs(leftWristY - leftShoulderY) > 50) corrections.add("手臂再平一些")
                if (abs(rightWristY - rightShoulderY) > 50) corrections.add("手臂再平一些")
                ActionResult(StandardAction.ARMS_EXTENDED, 0.90f, corrections)
            }

            isStanding -> {
                val corrections = mutableListOf<String>()
                if (abs(leftKneeY - rightKneeY) > 30) corrections.add("两腿保持同一高度")
                if (abs(leftHipY - rightHipY) > 20) corrections.add("保持臀部水平")
                if (torsoLength < 50) corrections.add("挺直身体")
                ActionResult(StandardAction.STANDING, 0.90f, corrections)
            }

            else -> ActionResult(StandardAction.UNKNOWN, 0f, listOf("姿势不标准，请调整"))
        }
    }

    private fun scoreRange(value: Float, low: Float, high: Float, soft: Float, invert: Boolean = false): Float {
        // invert=false: 低于low或高于high得0，进入区间缓升，完全在区间中得1；soft为过渡带
        // invert=true: 反向得分，越小越好
        return if (!invert) {
            when {
                value < low - soft || value > high + soft -> 0f
                value in low..high -> 1f
                value < low -> 1f - ((low - value) / soft).coerceIn(0f, 1f)
                else -> 1f - ((value - high) / soft).coerceIn(0f, 1f)
            }
        } else {
            when {
                value <= low -> 1f
                value >= high + soft -> 0f
                value <= high -> 1f - ((value - low) / (high - low)).coerceIn(0f, 1f)
                else -> 1f - ((value - high) / soft).coerceIn(0f, 1f)
            }
        }
    }

    private fun scoreOverHead(wristY: Float, shoulderY: Float, noseY: Float, refHeight: Float): Float {
        // 手比肩至少高 refHeight*0.05，接近或高过头顶得满分
        val shoulderGap = shoulderY - wristY
        val headGap = noseY - wristY
        val need = refHeight * 0.05f
        val shoulderScore = ((shoulderGap - need) / (refHeight * 0.05f)).coerceIn(0f, 1f)
        val headScore = (headGap / (refHeight * 0.05f)).coerceIn(0f, 1f)
        return (shoulderScore * 0.6f + headScore * 0.4f).coerceIn(0f, 1f)
    }

    private fun getDistance(p1: KeyPoint?, p2: KeyPoint?): Float {
        if (p1 == null || p2 == null || p1.score < 0.2f || p2.score < 0.2f) {
            return 0f
        }
        val dx = p1.coordinate.first - p2.coordinate.first
        val dy = p1.coordinate.second - p2.coordinate.second
        return sqrt(dx * dx + dy * dy)
    }

    private fun getAngle(p1: KeyPoint?, center: KeyPoint?, p2: KeyPoint?): Float {
        if (p1 == null || center == null || p2 == null) return 0f
        if (p1.score < 0.2f || center.score < 0.2f || p2.score < 0.2f) return 0f

        val v1x = p1.coordinate.first - center.coordinate.first
        val v1y = p1.coordinate.second - center.coordinate.second
        val v2x = p2.coordinate.first - center.coordinate.first
        val v2y = p2.coordinate.second - center.coordinate.second

        val dot = v1x * v2x + v1y * v2y
        val cross = v1x * v2y - v1y * v2x
        val angle = atan2(cross.toDouble(), dot.toDouble())

        return abs(Math.toDegrees(angle)).toFloat()
    }

    private fun isBodyAligned(keyPointMap: Map<BodyPart, KeyPoint>): Boolean {
        val leftShoulderX = keyPointMap[BodyPart.LEFT_SHOULDER]?.coordinate?.first ?: return false
        val rightShoulderX = keyPointMap[BodyPart.RIGHT_SHOULDER]?.coordinate?.first ?: return false
        val leftHipX = keyPointMap[BodyPart.LEFT_HIP]?.coordinate?.first ?: return false
        val rightHipX = keyPointMap[BodyPart.RIGHT_HIP]?.coordinate?.first ?: return false

        val shoulderAngle = atan2(
            (rightShoulderX - leftShoulderX).toDouble(),
            100.0
        )
        val hipAngle = atan2(
            (rightHipX - leftHipX).toDouble(),
            100.0
        )

        return abs(shoulderAngle - hipAngle) < 0.3 // 肩膀和臀部对齐
    }

    // 获取标准动作的关键点（用于显示标准姿态）
    fun getStandardPose(action: StandardAction, width: Float, height: Float): List<KeyPoint> {
        return when (action) {
            StandardAction.SQUATTING -> getStandardSquatPose(width, height)
            StandardAction.JUMPING_JACK -> getStandardJumpingJackPose(width, height)
            StandardAction.HORSE_STANCE -> getStandardHorseStancePose(width, height)
            StandardAction.ARMS_EXTENDED -> getStandardArmsExtendedPose(width, height)
            StandardAction.ARMS_RAISED -> getStandardArmsRaisedPose(width, height)
            StandardAction.HANDS_ON_HIPS -> getStandardHandsOnHipsPose(width, height)
            StandardAction.ARMS_CROSSED -> getStandardArmsCrossedPose(width, height)
            else -> getStandardStandingPose(width, height)
        }
    }

    private fun getStandardStandingPose(width: Float, height: Float): List<KeyPoint> {
        val centerX = width / 2
        return listOf(
            KeyPoint(BodyPart.NOSE, Pair(centerX, height * 0.15f), 1f),
            KeyPoint(BodyPart.LEFT_EYE, Pair(centerX - 20, height * 0.12f), 1f),
            KeyPoint(BodyPart.RIGHT_EYE, Pair(centerX + 20, height * 0.12f), 1f),
            KeyPoint(BodyPart.LEFT_EAR, Pair(centerX - 40, height * 0.14f), 1f),
            KeyPoint(BodyPart.RIGHT_EAR, Pair(centerX + 40, height * 0.14f), 1f),
            KeyPoint(BodyPart.LEFT_SHOULDER, Pair(centerX - 60, height * 0.25f), 1f),
            KeyPoint(BodyPart.RIGHT_SHOULDER, Pair(centerX + 60, height * 0.25f), 1f),
            KeyPoint(BodyPart.LEFT_ELBOW, Pair(centerX - 90, height * 0.40f), 1f),
            KeyPoint(BodyPart.RIGHT_ELBOW, Pair(centerX + 90, height * 0.40f), 1f),
            KeyPoint(BodyPart.LEFT_WRIST, Pair(centerX - 90, height * 0.55f), 1f),
            KeyPoint(BodyPart.RIGHT_WRIST, Pair(centerX + 90, height * 0.55f), 1f),
            KeyPoint(BodyPart.LEFT_HIP, Pair(centerX - 50, height * 0.50f), 1f),
            KeyPoint(BodyPart.RIGHT_HIP, Pair(centerX + 50, height * 0.50f), 1f),
            KeyPoint(BodyPart.LEFT_KNEE, Pair(centerX - 50, height * 0.70f), 1f),
            KeyPoint(BodyPart.RIGHT_KNEE, Pair(centerX + 50, height * 0.70f), 1f),
            KeyPoint(BodyPart.LEFT_ANKLE, Pair(centerX - 50, height * 0.90f), 1f),
            KeyPoint(BodyPart.RIGHT_ANKLE, Pair(centerX + 50, height * 0.90f), 1f)
        )
    }

    private fun getStandardSquatPose(width: Float, height: Float): List<KeyPoint> {
        val centerX = width / 2
        return listOf(
            KeyPoint(BodyPart.NOSE, Pair(centerX, height * 0.35f), 1f),
            KeyPoint(BodyPart.LEFT_SHOULDER, Pair(centerX - 60, height * 0.40f), 1f),
            KeyPoint(BodyPart.RIGHT_SHOULDER, Pair(centerX + 60, height * 0.40f), 1f),
            KeyPoint(BodyPart.LEFT_ELBOW, Pair(centerX - 70, height * 0.50f), 1f),
            KeyPoint(BodyPart.RIGHT_ELBOW, Pair(centerX + 70, height * 0.50f), 1f),
            KeyPoint(BodyPart.LEFT_WRIST, Pair(centerX - 70, height * 0.60f), 1f),
            KeyPoint(BodyPart.RIGHT_WRIST, Pair(centerX + 70, height * 0.60f), 1f),
            KeyPoint(BodyPart.LEFT_HIP, Pair(centerX - 50, height * 0.55f), 1f),
            KeyPoint(BodyPart.RIGHT_HIP, Pair(centerX + 50, height * 0.55f), 1f),
            KeyPoint(BodyPart.LEFT_KNEE, Pair(centerX - 50, height * 0.65f), 1f),
            KeyPoint(BodyPart.RIGHT_KNEE, Pair(centerX + 50, height * 0.65f), 1f),
            KeyPoint(BodyPart.LEFT_ANKLE, Pair(centerX - 50, height * 0.75f), 1f),
            KeyPoint(BodyPart.RIGHT_ANKLE, Pair(centerX + 50, height * 0.75f), 1f)
        )
    }

    private fun getStandardJumpingJackPose(width: Float, height: Float): List<KeyPoint> {
        val centerX = width / 2
        val spread = width * 0.22f
        return listOf(
            KeyPoint(BodyPart.NOSE, Pair(centerX, height * 0.15f), 1f),
            KeyPoint(BodyPart.LEFT_SHOULDER, Pair(centerX - 70, height * 0.25f), 1f),
            KeyPoint(BodyPart.RIGHT_SHOULDER, Pair(centerX + 70, height * 0.25f), 1f),
            KeyPoint(BodyPart.LEFT_ELBOW, Pair(centerX - 70, height * 0.10f), 1f),
            KeyPoint(BodyPart.RIGHT_ELBOW, Pair(centerX + 70, height * 0.10f), 1f),
            KeyPoint(BodyPart.LEFT_WRIST, Pair(centerX - 70, height * 0.02f), 1f),
            KeyPoint(BodyPart.RIGHT_WRIST, Pair(centerX + 70, height * 0.02f), 1f),
            KeyPoint(BodyPart.LEFT_HIP, Pair(centerX - 60, height * 0.50f), 1f),
            KeyPoint(BodyPart.RIGHT_HIP, Pair(centerX + 60, height * 0.50f), 1f),
            KeyPoint(BodyPart.LEFT_KNEE, Pair(centerX - spread, height * 0.70f), 1f),
            KeyPoint(BodyPart.RIGHT_KNEE, Pair(centerX + spread, height * 0.70f), 1f),
            KeyPoint(BodyPart.LEFT_ANKLE, Pair(centerX - spread, height * 0.90f), 1f),
            KeyPoint(BodyPart.RIGHT_ANKLE, Pair(centerX + spread, height * 0.90f), 1f)
        )
    }

    private fun getStandardHorseStancePose(width: Float, height: Float): List<KeyPoint> {
        val centerX = width / 2
        val spread = width * 0.20f
        return listOf(
            KeyPoint(BodyPart.NOSE, Pair(centerX, height * 0.18f), 1f),
            KeyPoint(BodyPart.LEFT_SHOULDER, Pair(centerX - 60, height * 0.28f), 1f),
            KeyPoint(BodyPart.RIGHT_SHOULDER, Pair(centerX + 60, height * 0.28f), 1f),
            KeyPoint(BodyPart.LEFT_ELBOW, Pair(centerX - 80, height * 0.40f), 1f),
            KeyPoint(BodyPart.RIGHT_ELBOW, Pair(centerX + 80, height * 0.40f), 1f),
            KeyPoint(BodyPart.LEFT_WRIST, Pair(centerX - 70, height * 0.50f), 1f),
            KeyPoint(BodyPart.RIGHT_WRIST, Pair(centerX + 70, height * 0.50f), 1f),
            KeyPoint(BodyPart.LEFT_HIP, Pair(centerX - 70, height * 0.55f), 1f),
            KeyPoint(BodyPart.RIGHT_HIP, Pair(centerX + 70, height * 0.55f), 1f),
            KeyPoint(BodyPart.LEFT_KNEE, Pair(centerX - spread, height * 0.70f), 1f),
            KeyPoint(BodyPart.RIGHT_KNEE, Pair(centerX + spread, height * 0.70f), 1f),
            KeyPoint(BodyPart.LEFT_ANKLE, Pair(centerX - spread, height * 0.90f), 1f),
            KeyPoint(BodyPart.RIGHT_ANKLE, Pair(centerX + spread, height * 0.90f), 1f)
        )
    }

    private fun getStandardArmsExtendedPose(width: Float, height: Float): List<KeyPoint> {
        val centerX = width / 2
        return listOf(
            KeyPoint(BodyPart.LEFT_SHOULDER, Pair(centerX - 60, height * 0.25f), 1f),
            KeyPoint(BodyPart.RIGHT_SHOULDER, Pair(centerX + 60, height * 0.25f), 1f),
            KeyPoint(BodyPart.LEFT_ELBOW, Pair(centerX - 150, height * 0.25f), 1f),
            KeyPoint(BodyPart.RIGHT_ELBOW, Pair(centerX + 150, height * 0.25f), 1f),
            KeyPoint(BodyPart.LEFT_WRIST, Pair(centerX - 250, height * 0.25f), 1f),
            KeyPoint(BodyPart.RIGHT_WRIST, Pair(centerX + 250, height * 0.25f), 1f),
            KeyPoint(BodyPart.LEFT_HIP, Pair(centerX - 50, height * 0.50f), 1f),
            KeyPoint(BodyPart.RIGHT_HIP, Pair(centerX + 50, height * 0.50f), 1f),
            KeyPoint(BodyPart.LEFT_KNEE, Pair(centerX - 50, height * 0.70f), 1f),
            KeyPoint(BodyPart.RIGHT_KNEE, Pair(centerX + 50, height * 0.70f), 1f),
            KeyPoint(BodyPart.LEFT_ANKLE, Pair(centerX - 50, height * 0.90f), 1f),
            KeyPoint(BodyPart.RIGHT_ANKLE, Pair(centerX + 50, height * 0.90f), 1f)
        )
    }

    private fun getStandardArmsRaisedPose(width: Float, height: Float): List<KeyPoint> {
        val centerX = width / 2
        return listOf(
            KeyPoint(BodyPart.LEFT_SHOULDER, Pair(centerX - 60, height * 0.25f), 1f),
            KeyPoint(BodyPart.RIGHT_SHOULDER, Pair(centerX + 60, height * 0.25f), 1f),
            KeyPoint(BodyPart.LEFT_ELBOW, Pair(centerX - 60, height * 0.10f), 1f),
            KeyPoint(BodyPart.RIGHT_ELBOW, Pair(centerX + 60, height * 0.10f), 1f),
            KeyPoint(BodyPart.LEFT_WRIST, Pair(centerX - 60, height * 0.02f), 1f),
            KeyPoint(BodyPart.RIGHT_WRIST, Pair(centerX + 60, height * 0.02f), 1f),
            KeyPoint(BodyPart.LEFT_HIP, Pair(centerX - 50, height * 0.50f), 1f),
            KeyPoint(BodyPart.RIGHT_HIP, Pair(centerX + 50, height * 0.50f), 1f)
        )
    }

    private fun getStandardHandsOnHipsPose(width: Float, height: Float): List<KeyPoint> {
        val centerX = width / 2
        return listOf(
            KeyPoint(BodyPart.LEFT_SHOULDER, Pair(centerX - 60, height * 0.25f), 1f),
            KeyPoint(BodyPart.RIGHT_SHOULDER, Pair(centerX + 60, height * 0.25f), 1f),
            KeyPoint(BodyPart.LEFT_ELBOW, Pair(centerX - 80, height * 0.40f), 1f),
            KeyPoint(BodyPart.RIGHT_ELBOW, Pair(centerX + 80, height * 0.40f), 1f),
            KeyPoint(BodyPart.LEFT_WRIST, Pair(centerX - 50, height * 0.50f), 1f),
            KeyPoint(BodyPart.RIGHT_WRIST, Pair(centerX + 50, height * 0.50f), 1f),
            KeyPoint(BodyPart.LEFT_HIP, Pair(centerX - 50, height * 0.50f), 1f),
            KeyPoint(BodyPart.RIGHT_HIP, Pair(centerX + 50, height * 0.50f), 1f)
        )
    }

    private fun getStandardArmsCrossedPose(width: Float, height: Float): List<KeyPoint> {
        val centerX = width / 2
        return listOf(
            KeyPoint(BodyPart.LEFT_SHOULDER, Pair(centerX - 60, height * 0.25f), 1f),
            KeyPoint(BodyPart.RIGHT_SHOULDER, Pair(centerX + 60, height * 0.25f), 1f),
            KeyPoint(BodyPart.LEFT_ELBOW, Pair(centerX, height * 0.35f), 1f),
            KeyPoint(BodyPart.RIGHT_ELBOW, Pair(centerX, height * 0.40f), 1f),
            KeyPoint(BodyPart.LEFT_WRIST, Pair(centerX + 40, height * 0.40f), 1f),
            KeyPoint(BodyPart.RIGHT_WRIST, Pair(centerX - 40, height * 0.45f), 1f),
            KeyPoint(BodyPart.LEFT_HIP, Pair(centerX - 50, height * 0.50f), 1f),
            KeyPoint(BodyPart.RIGHT_HIP, Pair(centerX + 50, height * 0.50f), 1f)
        )
    }
}
