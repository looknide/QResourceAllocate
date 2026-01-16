package quantum.rl

import quantum.topo.Topo
import quantum.topo.Node
import quantum.algorithm.AlgorithmNotCleanSD
import kotlin.random.Random
import kotlin.math.ln

/**
 * 生成请求的模块（对应论文 P1 Request Generation）
 *
 * R = { (src, dst, Dd, AT, Pi, WT=0, a_demand=Dd) }
 *
 * 所有参数分布均符合论文：
 *  - 请求数量：Poisson(lambda)
 *  - Dd ∈ [1,5]
 *  - AT ∈ [1,5]
 *  - Pi ∈ [1,5]（1最高优先级）
 */
object RequestGenerator {

    // ===========================
    // 参数配置
    // ===========================
    private const val DEMAND_MIN = 1
    private const val DEMAND_MAX = 5

    private const val AT_MIN = 1
    private const val AT_MAX = 5

    private const val PRIORITY_MIN = 1
    private const val PRIORITY_MAX = 5

    /**
     * 主入口：
     *   生成 Poisson 数量的请求
     */
    fun generateRequests(topo: Topo, lambda: Int =20)/*5*/
            : List<AlgorithmNotCleanSD.Quadruple<Node, Node, Int, Int, Int, Int, Int>> {

        val reqCount = poisson(lambda)
        if (reqCount <= 0) return emptyList()

        val nodes = topo.nodes
        val N = nodes.size
        val list = mutableListOf<AlgorithmNotCleanSD.Quadruple<Node, Node, Int, Int, Int, Int, Int>>()

        repeat(reqCount) {
            // 生成随机 src, dst
            var src = nodes.random()
            var dst = nodes.random()

            // 按论文 Uniform 范围生成 Dd, AT, Pi
            val demand = randInt(DEMAND_MIN, DEMAND_MAX)
            val acceptTime = randInt(AT_MIN, AT_MAX)
            val priority = randInt(PRIORITY_MIN, PRIORITY_MAX)

            // WAIT TIME 初始为 0
            val waitTime = 0

            // a_demand（原始需求） = demand
            val aDemand = demand

            val q = AlgorithmNotCleanSD.Quadruple(
                src,
                dst,
                demand,
                acceptTime,
                priority,
                waitTime,
                aDemand
            )
            list.add(q)
        }

        return list
    }


    // ===========================
    // Poisson 分布生成
    // ===========================
    private fun poisson(lambda: Int): Int {
        val L = kotlin.math.exp(-lambda.toDouble())
        var k = 0
        var p = 1.0

        do {
            k++
            p *= Random.nextDouble()
        } while (p > L)

        return k - 1
    }

    // ===========================
    // Uniform 随机整数
    // ===========================
    private fun randInt(min: Int, max: Int): Int {
        return Random.nextInt(min, max + 1)
    }
}
