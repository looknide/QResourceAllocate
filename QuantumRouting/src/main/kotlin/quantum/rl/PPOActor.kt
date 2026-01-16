package quantum.rl

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

class PPOActor(
    stateDim: Int,
    hiddenDim: Int,
    val actionDim: Int
) {
    private val net = MLP(stateDim, hiddenDim, actionDim)

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

    fun backward(dLoss: DoubleArray) {
        net.backward(dLoss)
    }

    // =================== utils ===================

    private fun softmax(x: DoubleArray): DoubleArray {
        val max = x.maxOrNull() ?: 0.0
        val exps = x.map { exp(it - max) }
        val sum = exps.sum()
        return exps.map { it / sum }.toDoubleArray()
    }

    private fun categoricalSample(p: DoubleArray): Int {
        val r = Random.nextDouble()
        var acc = 0.0
        for (i in p.indices) {
            acc += p[i]
            if (r <= acc) return i
        }
        return p.lastIndex
    }
}