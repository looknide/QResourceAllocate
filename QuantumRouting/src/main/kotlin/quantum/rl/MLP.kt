package quantum.rl

import kotlin.math.*
import kotlin.random.Random

class MLP(
    inputDim: Int,
    hiddenDim: Int,
    val outputDim: Int,
    var lr: Double = 1e-3,  // 默认学习率调整为1e-3
    val useAdam: Boolean = true,  // 新增：是否使用Adam
    val weightDecay: Double = 3e-4  // 新增：L2正则化系数，参考代码中是1e-3
) {

    // ----------- 权重参数 -----------
    private val W1 = Array(hiddenDim) { DoubleArray(inputDim) { initWeight(inputDim) } }
    private val b1 = DoubleArray(hiddenDim)
    private val W2 = Array(outputDim) { DoubleArray(hiddenDim) { initWeight(hiddenDim) } }
    private val b2 = DoubleArray(outputDim)

    // ----------- Adam优化器状态（如使用） -----------
    private var adamStep = 0
    private val mW1 = Array(hiddenDim) { DoubleArray(inputDim) }
    private val vW1 = Array(hiddenDim) { DoubleArray(inputDim) }
    private val mb1 = DoubleArray(hiddenDim)
    private val vb1 = DoubleArray(hiddenDim)
    private val mW2 = Array(outputDim) { DoubleArray(hiddenDim) }
    private val vW2 = Array(outputDim) { DoubleArray(hiddenDim) }
    private val mb2 = DoubleArray(outputDim)
    private val vb2 = DoubleArray(outputDim)

    // ----------- 前向缓存 -----------
    private lateinit var xCache: DoubleArray
    private lateinit var hCache: DoubleArray

    // He初始化（参考代码中使用的方法）
    private fun initWeight(fanIn: Int): Double {
        val limit = sqrt(2.0 / fanIn)  // He初始化
        return Random.nextDouble(-limit, limit)
    }

    fun setLearningRate(newLr: Double) {
        lr = newLr
    }

    // ----------- 前向传播 -----------
    fun forward(state: FloatArray): DoubleArray {
        val x = DoubleArray(state.size) { state[it].toDouble() }
        xCache = x

        // Hidden layer with tanh（保持与原代码一致）
        val h = DoubleArray(W1.size)
        for (i in W1.indices) {
            var sum = b1[i]
            for (j in W1[i].indices)
                sum += W1[i][j] * x[j]
            h[i] = tanh(sum)
        }
        hCache = h

        // Output layer (linear, no activation for logits)
        val out = DoubleArray(outputDim)
        for (i in W2.indices) {
            var sum = b2[i]
            for (j in W2[i].indices)
                sum += W2[i][j] * h[j]
            out[i] = sum  // 注意：输出层没有激活函数，这是logits
        }
        return out
    }

    // ----------- 反向传播（支持Adam和SGD） -----------
    fun backward(dOut: DoubleArray) {
        val dH = DoubleArray(hCache.size)

        // 计算隐藏层梯度 dL/dH = W2^T * dOut
        for (i in 0 until hCache.size) {
            var sum = 0.0
            for (k in 0 until outputDim)
                sum += W2[k][i] * dOut[k]
            dH[i] = sum * (1 - hCache[i] * hCache[i]) // tanh导数
        }

        if (useAdam) {
            adamStep++
            adamUpdate(dOut, dH)
        } else {
            sgdUpdate(dOut, dH)
        }
    }

    // 传统的SGD更新（与原代码保持一致）
    private fun sgdUpdate(dOut: DoubleArray, dH: DoubleArray) {
        // 更新 W2, b2
        for (i in 0 until outputDim) {
            for (j in 0 until hCache.size) {
                W2[i][j] -= lr * (dOut[i] * hCache[j] + weightDecay * W2[i][j])  // 添加L2
            }
            b2[i] -= lr * dOut[i]
        }

        // 更新 W1, b1
        for (i in 0 until W1.size) {
            for (j in 0 until xCache.size) {
                W1[i][j] -= lr * (dH[i] * xCache[j] + weightDecay * W1[i][j])  // 添加L2
            }
            b1[i] -= lr * dH[i]
        }
    }

    // Adam优化器更新（参考代码中的实现）
    private fun adamUpdate(dOut: DoubleArray, dH: DoubleArray) {
        val beta1 = 0.9
        val beta2 = 0.999
        val epsilon = 1e-8

        // 学习率调整（参考Adam公式）
        val lr_t = lr * sqrt(1.0 - beta2.pow(adamStep)) / (1.0 - beta1.pow(adamStep))

        // 更新输出层 (W2, b2)
        for (i in 0 until outputDim) {
            for (j in 0 until hCache.size) {
                val grad = dOut[i] * hCache[j] + weightDecay * W2[i][j]  // 带L2的梯度

                // 更新一阶矩和二阶矩
                mW2[i][j] = beta1 * mW2[i][j] + (1 - beta1) * grad
                vW2[i][j] = beta2 * vW2[i][j] + (1 - beta2) * grad * grad

                // 偏差修正
                val m_hat = mW2[i][j] / (1 - beta1.pow(adamStep))
                val v_hat = vW2[i][j] / (1 - beta2.pow(adamStep))

                // 更新权重
                W2[i][j] -= lr_t * m_hat / (sqrt(v_hat) + epsilon)
            }

            // 更新偏置 b2
            val b_grad = dOut[i]
            mb2[i] = beta1 * mb2[i] + (1 - beta1) * b_grad
            vb2[i] = beta2 * vb2[i] + (1 - beta2) * b_grad * b_grad

            val mb_hat = mb2[i] / (1 - beta1.pow(adamStep))
            val vb_hat = vb2[i] / (1 - beta2.pow(adamStep))
            b2[i] -= lr_t * mb_hat / (sqrt(vb_hat) + epsilon)
        }

        // 更新隐藏层 (W1, b1)
        for (i in 0 until W1.size) {
            for (j in 0 until xCache.size) {
                val grad = dH[i] * xCache[j] + weightDecay * W1[i][j]  // 带L2的梯度

                mW1[i][j] = beta1 * mW1[i][j] + (1 - beta1) * grad
                vW1[i][j] = beta2 * vW1[i][j] + (1 - beta2) * grad * grad

                val m_hat = mW1[i][j] / (1 - beta1.pow(adamStep))
                val v_hat = vW1[i][j] / (1 - beta2.pow(adamStep))

                W1[i][j] -= lr_t * m_hat / (sqrt(v_hat) + epsilon)
            }

            val b_grad = dH[i]
            mb1[i] = beta1 * mb1[i] + (1 - beta1) * b_grad
            vb1[i] = beta2 * vb1[i] + (1 - beta2) * b_grad * b_grad

            val mb_hat = mb1[i] / (1 - beta1.pow(adamStep))
            val vb_hat = vb1[i] / (1 - beta2.pow(adamStep))
            b1[i] -= lr_t * mb_hat / (sqrt(vb_hat) + epsilon)
        }
    }

    // 梯度裁剪（防止梯度爆炸）
    private fun clipGradients(grads: DoubleArray, maxNorm: Double = 5.0): DoubleArray {
        val norm = sqrt(grads.sumOf { it * it })
        return if (norm > maxNorm) {
            grads.map { it * maxNorm / norm }.toDoubleArray()
        } else {
            grads
        }
    }
}