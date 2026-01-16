package quantum.rl

import java.io.File

class PPOCritic(
    stateDim: Int,
    hiddenDim: Int
) {
    private val net = MLP(stateDim, hiddenDim, 1)
    private var learningRate = 3e-4

    fun setLearningRate(lr: Double) {
        learningRate = lr
        net.lr = lr
    }

    fun value(state: FloatArray): Double {
        return net.forward(state)[0]
    }

    fun backward(loss: Double) {
        net.backward(doubleArrayOf(loss))
    }

    fun save(path: String) {
        File("$path.txt").writeText("PPOCritic model - LR: $learningRate")
    }

    fun load(path: String) {
        println("Loading critic from $path")
    }
}