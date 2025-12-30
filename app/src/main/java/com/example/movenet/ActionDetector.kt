package com.example.movenet

import android.util.Log
import kotlin.math.*

// 标准动作定义
enum class StandardAction {
    STANDING, SQUATTING, ARMS_RAISED, ARMS_EXTENDED, HANDS_ON_HIPS, ARMS_CROSSED, UNKNOWN
}

data class ActionResult(
    val action: StandardAction,
    val confidence: Float,
    val corrections: List<String> = emptyList()
)

class ActionDetector {

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
        
        val leftWristY = keyPointMap[BodyPart.LEFT_WRIST]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightWristY = keyPointMap[BodyPart.RIGHT_WRIST]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val leftWristX = keyPointMap[BodyPart.LEFT_WRIST]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightWristX = keyPointMap[BodyPart.RIGHT_WRIST]?.coordinate?.first ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        
        val leftElbowY = keyPointMap[BodyPart.LEFT_ELBOW]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightElbowY = keyPointMap[BodyPart.RIGHT_ELBOW]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        
        val leftHipY = keyPointMap[BodyPart.LEFT_HIP]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightHipY = keyPointMap[BodyPart.RIGHT_HIP]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val leftKneeY = keyPointMap[BodyPart.LEFT_KNEE]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightKneeY = keyPointMap[BodyPart.RIGHT_KNEE]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val leftAnkleY = keyPointMap[BodyPart.LEFT_ANKLE]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)
        val rightAnkleY = keyPointMap[BodyPart.RIGHT_ANKLE]?.coordinate?.second ?: return ActionResult(StandardAction.UNKNOWN, 0f)

        val shoulderY = (leftShoulderY + rightShoulderY) / 2
        val wristY = (leftWristY + rightWristY) / 2
        val hipY = (leftHipY + rightHipY) / 2
        val kneeY = (leftKneeY + rightKneeY) / 2
        val ankleY = (leftAnkleY + rightAnkleY) / 2

        // 计算各部分距离
        val torsoLength = hipY - shoulderY
        val thighLength = kneeY - hipY
        val calfLength = ankleY - kneeY
        val totalLegLength = ankleY - hipY
        val shoulderWidth = abs(rightShoulderX - leftShoulderX)
        
        val thighToTorsoRatio = thighLength / torsoLength
        val legToTorsoRatio = totalLegLength / torsoLength
        
        // 手臂检测
        val leftArmHorizontal = abs(leftWristY - leftShoulderY) < 40 // 左手腕与肩膀Y坐标接近（水平）
        val rightArmHorizontal = abs(rightWristY - rightShoulderY) < 40 // 右手腕与肩膀Y坐标接近（水平）
        val leftArmExtended = abs(leftWristX - leftShoulderX) > shoulderWidth * 0.8 // 左臂向外伸展
        val rightArmExtended = abs(rightWristX - rightShoulderX) > shoulderWidth * 0.8 // 右臂向外伸展
        
        // 调试日志
        Log.d("ActionDetector", "肩Y=$shoulderY, 腕Y=$wristY, 臀Y=$hipY")
        Log.d("ActionDetector", "左臂水平=$leftArmHorizontal, 右臂水平=$rightArmHorizontal")
        Log.d("ActionDetector", "左臂伸展=$leftArmExtended, 右臂伸展=$rightArmExtended")
        Log.d("ActionDetector", "腿长比=$legToTorsoRatio")

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
        val isSquatting = leftKneeAngle < 140 && rightKneeAngle < 140 && leftKneeAngle > 30 && rightKneeAngle > 30

        Log.d("ActionDetector", "站立=$isStanding, 水平举臂=$isArmsExtended, 深蹲=$isSquatting")

        // 根据条件判断动作
        return when {
            isSquatting -> {
                val corrections = mutableListOf<String>()
                if (abs(leftKneeAngle - rightKneeAngle) > 20) corrections.add("保持双腿弯曲程度一致")
                ActionResult(StandardAction.SQUATTING, 0.90f, corrections)
            }
            isArmsExtended -> {
                val corrections = mutableListOf<String>()
                // 检查举臂姿势的标准性
                if (abs(leftWristY - rightWristY) > 30) corrections.add("两手保持同一高度")
                if (abs(leftShoulderY - rightShoulderY) > 20) corrections.add("保持肩膀水平")
                if (abs(leftWristY - leftShoulderY) > 50) corrections.add("手臂再平一些")
                if (abs(rightWristY - rightShoulderY) > 50) corrections.add("手臂再平一些")
                ActionResult(StandardAction.ARMS_EXTENDED, 0.90f, corrections)
            }
            
            isStanding -> {
                val corrections = mutableListOf<String>()
                // 检查站姿的标准性
                if (abs(leftKneeY - rightKneeY) > 30) corrections.add("两腿保持同一高度")
                if (abs(leftHipY - rightHipY) > 20) corrections.add("保持臀部水平")
                if (torsoLength < 50) corrections.add("挺直身体")
                ActionResult(StandardAction.STANDING, 0.90f, corrections)
            }

            else -> {
                ActionResult(StandardAction.UNKNOWN, 0f, listOf("姿势不标准，请站立或水平举臂"))
            }
        }
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
