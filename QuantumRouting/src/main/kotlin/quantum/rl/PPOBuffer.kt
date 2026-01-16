package quantum.rl

class PPOBuffer {
    val states = mutableListOf<FloatArray>()
//    val actions = mutableListOf<Int>()
    val actions = mutableListOf<Double>()
    val rewards = mutableListOf<Double>()
    val dones = mutableListOf<Boolean>()
    val logProbs = mutableListOf<Double>()
    val values = mutableListOf<Double>()

    fun clear() {
        states.clear()
        actions.clear()
        rewards.clear()
        dones.clear()
        logProbs.clear()
        values.clear()
    }
}
