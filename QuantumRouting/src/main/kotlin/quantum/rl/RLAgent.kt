package quantum.rl

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class RLAgent {

    private val stateDim = 69
    val actionDim = 20
    private val hiddenDim = 64

    // 使用改进的MLP
    private val actor = ImprovedPPOActor(stateDim, hiddenDim, actionDim)
    private val critic = PPOCritic(stateDim, hiddenDim)
    private val buffer = PPOBuffer()

    // PPO超参数
    private val gamma = 0.99
    private val clipEps = 0.2
    private val updateEpochs = 4
    private val batchSize = 32
    private val entropyBeta = 0.05

    // 学习率调度
    private var currentLr = 1e-3
    private val lrDecay = 0.995
    private var episodeCount = 0

    fun selectAction(state: FloatArray): Int {
        val (action, logProb, _) = actor.sampleAction(state)
        val value = critic.value(state)

        buffer.states.add(state)
        buffer.actions.add(action.toDouble())
        buffer.logProbs.add(logProb)
        buffer.values.add(value)

        return action
    }

    fun storeTransition(state: FloatArray, action: Int, reward: Double, done: Boolean) {
        buffer.rewards.add(reward)
        buffer.dones.add(done)
    }

    fun update() {
        if (buffer.states.size < batchSize) {
            println("PPO更新跳过: 缓冲区大小不足 (${buffer.states.size}/$batchSize)")
            return
        }

        // 使用RewardCalculator的GAE计算优势
        val returns = RewardCalculator.computeDiscountedReturns(
            buffer.rewards, buffer.dones, gamma
        )
        val advantages = RewardCalculator.computeGAE(
            buffer.rewards, buffer.values, buffer.dones, gamma
        )

        val indices = buffer.states.indices.toList()

        repeat(updateEpochs) { epoch ->
            indices.shuffled().chunked(batchSize).forEach { batch ->
                for (i in batch) {
                    val state = buffer.states[i]
                    val action = buffer.actions[i].toInt()
                    val oldLogProb = buffer.logProbs[i]
                    val adv = advantages[i]
                    val ret = returns[i]

                    // ---------- Actor ----------
                    val (newAction, newLogProb, probs) = actor.sampleAction(state)
                    val ratio = exp(newLogProb - oldLogProb)

                    // PPO-clip目标函数
                    val surr1 = ratio * adv
                    val surr2 = ratio.coerceIn(1.0 - clipEps, 1.0 + clipEps) * adv
                    val actorLoss = -minOf(surr1, surr2)

                    // 熵奖励
                    val entropy = -probs.map { p ->
                        if (p > 0) p * ln(p + 1e-8) else 0.0
                    }.sum()

                    val totalActorLoss = actorLoss - entropyBeta * entropy

                    actor.backward(totalActorLoss, action, probs)

                    // ---------- Critic ----------
                    val vNew = critic.value(state)
                    val criticLoss = 0.5 * (vNew - ret) * (vNew - ret)  // MSE损失，不使用pow
                    critic.backward(criticLoss)
                }
            }
        }

        // 学习率衰减
        decayLearningRate()

        // 清空缓冲区
        buffer.clear()

        episodeCount++
        if (episodeCount % 5 == 0) {
            println("PPO更新完成 (第${episodeCount}次), 学习率: %.6f".format(currentLr))
        }
    }

    private fun decayLearningRate() {
        currentLr *= lrDecay
        actor.setLearningRate(currentLr)
        critic.setLearningRate(currentLr)
    }
}

// 改进的PPOActor
class ImprovedPPOActor(
    stateDim: Int,
    hiddenDim: Int,
    val actionDim: Int
) {
    private val net = MLP(stateDim, hiddenDim, actionDim, useAdam = true, weightDecay = 1e-4)

    fun setLearningRate(lr: Double) {
        net.setLearningRate(lr)
    }

    fun forward(state: FloatArray): DoubleArray {
        return net.forward(state)
    }

    fun sampleAction(state: FloatArray): Triple<Int, Double, DoubleArray> {
        val logits = forward(state)
        val probs = softmax(logits)

        val action = categoricalSample(probs)
        val logProb = ln(probs[action] + 1e-8)

        return Triple(action, logProb, probs)
    }

    // 改进的反向传播
    fun backward(loss: Double, action: Int, probs: DoubleArray) {
        // 计算策略梯度
        val dLoss = DoubleArray(actionDim) { 0.0 }
        dLoss[action] = loss

        // 添加基线减少方差
        val baseline = probs[action] * loss
        dLoss[action] -= baseline

        net.backward(dLoss)
    }

    private fun softmax(x: DoubleArray): DoubleArray {
        val max = x.maxOrNull() ?: 0.0
        val exps = x.map { exp(it - max) }
        val sum = exps.sum()
        return exps.map { it / sum }.toDoubleArray()
    }

    private fun categoricalSample(p: DoubleArray): Int {
        val r = kotlin.random.Random.nextDouble()
        var acc = 0.0
        for (i in p.indices) {
            acc += p[i]
            if (r <= acc) return i
        }
        return p.lastIndex
    }
}
