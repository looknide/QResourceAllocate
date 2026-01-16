package quantum.rl

import quantum.topo.Node
import quantum.algorithm.AlgorithmNotCleanSD.Quadruple

/**
 * 从 Qc 中选 Qp，按 Qc 原始顺序（FIFO）选择，
 * 不再按 Pd 排序，但仍然保留“冲突避免”（同一节点不能出现在两个请求里）。
 *
 * 用法：
 *   val Qp = FifoRequestSelector.selectProcessableRequests(Qc)
 */
object FifoRequestSelector {

    fun selectProcessableRequests(
        Qc: List<Quadruple<Node,Node,Int,Int,Int,Int,Int>>
    ): List<Quadruple<Node,Node,Int,Int,Int,Int,Int>> {

        if (Qc.isEmpty()) return emptyList()

        // 和原来一样：Qc 小于等于 10 时最多 8 个，否则最多 10 个
        val n = if (Qc.size <= 10) 8 else 10

        val selected = mutableListOf<Quadruple<Node,Node,Int,Int,Int,Int,Int>>()
        val usedNodes = mutableSetOf<Node>()

        // **关键变化：不再排序，直接按 Qc 的顺序遍历 = FIFO**
        for (req in Qc) {
            if (selected.size >= n) break

            val src = req.src
            val dst = req.dst

            // 仍然保留“节点不能冲突”的规则
            if (src !in usedNodes && dst !in usedNodes) {
                selected.add(req)
                usedNodes.add(src)
                usedNodes.add(dst)
            }
        }

        return selected
    }
}
