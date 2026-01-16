package quantum.rl

import quantum.topo.Node
import quantum.algorithm.AlgorithmNotCleanSD.Quadruple

/**
 * RequestSelector 统一处理：
 * ① P1：Qc 入队（按 Pd 优先级筛选，保持 Qc<=15）
 * ② P2：从 Qc 选 Qp（按 Pd 排序 + 冲突避免）
 *
 * 完全对应论文 Algorithm 1。
 */

object RequestSelector {

    private const val MaxListLength = 15   // Qc 最大长度（论文固定）

    // =============================================
    // P1：更新 Qc（加入新请求 + 严格 Pd 优先级筛选）
    // =============================================
    fun updateQc(
        Qc: MutableList<Quadruple<Node,Node,Int,Int,Int,Int,Int>>,
        newReqs: List<Quadruple<Node,Node,Int,Int,Int,Int,Int>>,
        FailPairs: MutableList<Quadruple<Node,Node,Int,Int,Int,Int,Int>>
    ) {
        if (newReqs.isEmpty()) return

        // 合并旧队列与新请求
        val merged = Qc + newReqs

        // 计算 Pd 并排序
        val sorted = merged.sortedBy { reqPd(it) }   // Pd 越小优先级越高

        if (sorted.size <= MaxListLength) {
            // 不超长 → 全部加入
            Qc.clear()
            Qc.addAll(sorted)
            return
        }

        // 超长 → 取前 15 个，剩下加入 FailPairs
        val keep = sorted.take(MaxListLength)
        val drop = sorted.drop(MaxListLength)

        Qc.clear()
        Qc.addAll(keep)
        FailPairs.addAll(drop)
    }

    //=============
    // Pd 计算
    //=============
    private fun reqPd(
        req: Quadruple<Node,Node,Int,Int,Int,Int,Int>
    ): Float {
        return (req.priority + req.demand + req.accepttime) / 3f
    }

    // =============================================
    // P2：从 Qc 中选 Qp（最多 8~10 个，冲突避免）
    // =============================================
    fun selectProcessableRequests(
        Qc: List<Quadruple<Node,Node,Int,Int,Int,Int,Int>>
    ): List<Quadruple<Node,Node,Int,Int,Int,Int,Int>> {

        if (Qc.isEmpty()) return emptyList()

        // 按 Pd 排序
        val sorted = Qc.sortedBy { reqPd(it) }

        // 决定最大 Qp 长度 n
        val n = if (Qc.size <= 10) 8 else 10

        val selected = mutableListOf<Quadruple<Node,Node,Int,Int,Int,Int,Int>>()
        val usedNodes = mutableSetOf<Node>()

        // 遍历 sorted，按无冲突规则挑选
        for (req in sorted) {
            if (selected.size >= n) break

            val src = req.src
            val dst = req.dst

            if (src !in usedNodes && dst !in usedNodes) {
                selected.add(req)
                usedNodes.add(src)
                usedNodes.add(dst)
            }
        }

        return selected
    }
}
