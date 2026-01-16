package quantum.rl

import kotlin.math.*
import kotlin.random.Random

object MathUtils {
    // 自定义高斯随机数生成
    fun nextGaussian(mean: Double = 0.0, std: Double = 1.0): Double {
        var u = 0.0
        var v = 0.0
        var s = 0.0

        do {
            u = Random.nextDouble(-1.0, 1.0)
            v = Random.nextDouble(-1.0, 1.0)
            s = u * u + v * v
        } while (s >= 1.0 || s == 0.0)

        val mul = sqrt(-2.0 * ln(s) / s)
        return mean + std * u * mul
    }

    // 幂函数扩展 - 修正：Float.pow()只能接受Float参数
    fun Double.pow(n: Int): Double = this.pow(n.toDouble())
    fun Float.pow(n: Float): Float = this.pow(n)

    // Huber损失
    fun huberLoss(x: Double, delta: Double = 1.0): Double {
        return if (abs(x) <= delta) {
            0.5 * x * x
        } else {
            delta * (abs(x) - 0.5 * delta)
        }
    }

    // 计算平方的扩展函数
    fun squared(x: Double): Double = x * x
    fun squared(x: Float): Float = x * x
}

// 扩展函数
fun Double.squared(): Double = this * this
fun Float.squared(): Float = this * this