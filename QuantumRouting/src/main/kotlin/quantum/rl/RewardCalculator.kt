package quantum.rl

import quantum.algorithm.AlgorithmNotCleanSD.Quadruple
import quantum.topo.Node
import kotlin.math.sqrt
import kotlin.math.pow

object RewardCalculator {

    data class StepFeedback(
        val succPairsOnThisPath: Int,
        val waste: Int,            // w - succPairsOnThisPath (>=0)
        val finished: Boolean,     // 该 req 是否完成
        val failedOrTimeout: Boolean
    )

    fun calcStepReward(
        req: Quadruple<Node, Node, Int, Int, Int, Int, Int>,
        Wmax: Int,
        w: Int,
        ratio: Double,  // 保留ratio参数
        fb: StepFeedback
    ): Double {
        val (src, dst, demand, AT, priority, WT, aDemand) = req
        val fd = aDemand.toDouble().coerceAtLeast(1.0)
        val finishRatio = fb.succPairsOnThisPath.toDouble() / fd

        // === 保持你的原始奖励设计，只做权重微调 ===

        // 1) 完成奖励 - 这是最重要的，保持较高权重
        val eta1 = 1.2  // 从1.0略微提高到1.2
        val rFinishLocal = eta1 * finishRatio

        // 2) 浪费惩罚 - 稍微降低惩罚力度，避免过度保守
        val eta2 = 0.25  // 从0.3降低到0.25
        val wasteNorm = if (Wmax > 0) fb.waste.toDouble() / Wmax.toDouble() else 0.0
        val rWaste = -eta2 * wasteNorm

        // 3) 延迟惩罚 - 保持轻微
        val eta3 = 0.03  // 从0.05略微降低
        val delayNorm = WT.toDouble() / (WT + 10.0)
        val rDelay = -eta3 * delayNorm

        // 4) 优先级奖励 - 保持
        val eta4 = 0.3
        val pMax = 3.0
        val prNorm = (priority.coerceAtLeast(0)).toDouble() / pMax
        val rPrio = eta4 * finishRatio * prNorm

        // 5) ratio平滑性奖励 - 降低权重，避免过度影响
        val eta5 = 0.05  // 从0.1降低到0.05
        val rSmooth = -eta5 * (ratio - 0.5).pow(2.0)

        // 6) 利用率奖励 - 稍微提高权重
        val eta6 = 0.25  // 从0.2提高到0.25
        val utilization = if (Wmax > 0) w.toDouble() / Wmax.toDouble() else 0.0
        val rUtil = eta6 * utilization * finishRatio

        var rawReward = rFinishLocal + rWaste + rDelay + rPrio + rSmooth + rUtil

        // === 添加一些边界情况的处理 ===

        // 如果分配为0，给强负奖励
        if (w == 0) {
            rawReward = -0.8
        }

        // 如果完全没有成功，给额外惩罚
        if (fb.succPairsOnThisPath == 0 && w > 0) {
            rawReward -= 0.3
        }

        // 如果完全成功，给额外奖励
        if (fb.succPairsOnThisPath >= aDemand) {
            rawReward += 0.5
        }

        // 限制奖励范围
        rawReward = rawReward.coerceIn(-1.0, 2.0)

        return rawReward
    }

    /**
    终奖 - 保持原设计
     */
    fun calcTerminalReward(
        isSuccess: Boolean,
        satisValue: Double
    ): Double {
        return if (isSuccess) 3.0 * satisValue else -1.0 * satisValue
    }

    // === 新增：GAE优势计算函数 ===
    fun computeDiscountedReturns(
        rewards: List<Double>,
        dones: List<Boolean>,
        gamma: Double = 0.99
    ): List<Double> {
        val returns = MutableList(rewards.size) { 0.0 }
        var R = 0.0
        for (i in rewards.indices.reversed()) {
            R = rewards[i] + gamma * R * if (dones[i]) 0.0 else 1.0
            returns[i] = R
        }
        return returns
    }

    // GAE优势计算
    fun computeGAE(
        rewards: List<Double>,
        values: List<Double>,
        dones: List<Boolean>,
        gamma: Double = 0.99,
        lambda: Double = 0.95
    ): List<Double> {
        val advantages = MutableList(rewards.size) { 0.0 }
        var advantage = 0.0

        for (i in rewards.indices.reversed()) {
            val nextValue = if (i + 1 < values.size && !dones[i]) values[i + 1] else 0.0
            val delta = rewards[i] + gamma * nextValue - values[i]
            advantage = delta + gamma * lambda * advantage * if (dones[i]) 0.0 else 1.0
            advantages[i] = advantage
        }

        // 标准化优势
        if (advantages.isNotEmpty()) {
            val mean = advantages.average()
            val variance = advantages.map { (it - mean) * (it - mean) }.average()
            val std = sqrt(variance) + 1e-8
            return advantages.map { (it - mean) / std }
        }
        return advantages
    }
}