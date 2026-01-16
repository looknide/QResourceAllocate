package quantum

import quantum.topo.Topo
import quantum.topo.Node
import quantum.algorithm.MyfirstTest
import quantum.algorithm.AlgorithmNotCleanSD
import quantum.algorithm.PickedPathNotClearSD
import quantum.rl.*
import java.util.*

fun main() {

    // ======================================================
    // 初始化拓扑
    // ======================================================
    val baseTopo = Topo.generate(
        n = 50,
        q = 0.9,
        k = 5,
        a = 0.01,
        degree = 6
    )

    val agent = RLAgent()

    val Qc = mutableListOf<
            AlgorithmNotCleanSD.Quadruple<Node,Node,Int,Int,Int,Int,Int>
            >()

    val MAX_EPISODE = 10
    val MAX_SLOT = 100

    for (episode in 0 until MAX_EPISODE) {
        println("========== EPISODE $episode ==========")

        // episode reset
        val topo = Topo(baseTopo)
//        topo.fullReset()

        val algo = MyfirstTest(topo)
        algo.prepare()

        Qc.clear()
        algo.countFailedPair = 0
        algo.countSuccessPair = 0

        // ======================================================
        // Time Slots
        // ======================================================
        for (slot in 0 until MAX_SLOT) {

            println("\n---- Time Slot $slot ----")
            require(topo.isClean())
            algo.prepare()

            // ======================================================
            // P1：生成新请求加入 Qc
            // ======================================================
            val newReqs = RequestGenerator.generateRequests(topo)
            RequestSelector.updateQc(Qc, newReqs, algo.FailPairs)

            println("新到达请求 = ${newReqs.size}, 当前 Qc = ${Qc.size}")

            // ======================================================
            // P2: 选出可处理队列 Qp
            // ======================================================
//            val Qp = RequestSelector.selectProcessableRequests(Qc)
            // 使用 FIFO 调度
            val Qp = FifoRequestSelector.selectProcessableRequests(Qc)
            println("Qp 数量 = ${Qp.size}")

            if (Qp.isEmpty()) continue

            // ======================================================
            // P3：全局路径优先调度（核心）
            // ======================================================
            algo.majorPaths.clear()
            algo.recoveryPaths.clear()

            val SlesrcDstPairs = Qp.map { Triple(it.src, it.dst, it.demand) }
            val visitedMap = mutableMapOf<Pair<Node, Node>, Boolean>()

            SlesrcDstPairs.forEach { triple ->
                val pair = triple.first to triple.second
                visitedMap[pair] = false
            }

            /*while (true) {

                val candidates = algo.calCandidates(SlesrcDstPairs)
                if (candidates.isEmpty()) break

                val sortedCandidates = candidates.sortedByDescending { it.first }

                var pick: PickedPathNotClearSD? = null
                var lastIndex = 0

                // 找一个未访问过的最大 EXT path
                while (lastIndex < sortedCandidates.size) {

                    val cand = sortedCandidates[lastIndex]
                    if (cand.first <= 0.0) break

                    val src = cand.third.first()
                    val dst = cand.third.last()
                    val pair = src to dst

                    if (!visitedMap.getOrDefault(pair, false)) {
                        pick = cand
                        break
                    }
                    lastIndex++
                }

                if (pick == null || pick.first <= 0.0) break

                // 找到 pick 对应的 req
                val req = Qp.first { it.src == pick.third.first() && it.dst == pick.third.last() }
                println(">>> 选中请求 $req, path=${pick.third.map{it.id}}, EXT=${pick.first}, Wmax=${pick.second}")

                // ------------- RL 决策 w ----------------
                val state = StateBuilder.build(topo, req, Qp, pick)

                // val w = agent.selectAction(state).coerceIn(0, pick.second)
                val w = minOf(pick.second, req.demand)
                println("RL分配 w=$w (Wmax=${pick.second}, demand=${req.demand})")
                // -----------------------------------------

                // ------------- 分配资源 ------------------
                algo.pickAndAssignPath_rl(
                    pick = pick,
                    w = w,
                    SlesrcDstPairs = SlesrcDstPairs
                )

                println(">>> 分配完毕：path=${pick.third.map{it.id}}")
                // ----------------------------------------

                // 标记 visited
                visitedMap[pick.third.first() to pick.third.last()] = true

                // 如果都访问过，重置 visitedMap
                if (visitedMap.values.all { it }) {
                    visitedMap.keys.forEach { visitedMap[it] = false }
                }
            }*/

            for (req in Qp) {
                val demand = req.demand
                val candidates = algo.calCandidates(
                    listOf(Triple(req.src, req.dst, demand))
                )

                if (candidates.isEmpty()) {
                    println("请求 $req 无有效路径，跳过")
                    continue
                }
                // 使用 EXT 最大的路径
                val pick = candidates.maxBy { it.first }!!
                println(">>> 选中请求 $req, path=${pick.third.map { it.id }}, EXT=${pick.first}, Wmax=${pick.second}")

                // ----------- RL 决定 w（关键部分） -----------
                val state = StateBuilder.build(topo, req, Qp, pick)
//                val w = agent.selectAction(state).coerceIn(0, pick.second)
                val w = minOf(pick.second, req.demand)
                println("RL分配 w=$w (Wmax=${pick.second}, demand=${req.demand})")

                algo.pickAndAssignPath_rl(
                    pick = pick,
                    w = w,
                    SlesrcDstPairs = emptyList()
                )


                println(">>> 分配完毕：path=${pick.third.map { it.id }}")
            }

            algo.P2Extra()

            algo.P4()

            // 处理完成或超时
            val updatedQc = algo.If_TimeOut_or_Finished(Qc)
            println("时隙结束: 已完成=${algo.countSuccessPair}, 失败=${algo.countFailedPair}")
//            printTopoState(topo)

            Qc.clear()
            Qc.addAll(updatedQc)
            println(">>> [CHECK-CLEAR BEFORE] 清理前 topo 状态：")
            println("    assigned=${topo.links.count{ it.assigned }}, entangled=${topo.links.count{ it.entangled }}")

            topo.nodes.forEach { n ->
                if (n.internalLinks.isNotEmpty()) {
                    println("    节点 N#${n.id} internalLinks=${n.internalLinks.map{(l1,l2)->"(${l1.id},${l2.id})"}}")
                }
            }

            topo.clearEntanglements()

            val assignCnt2 = topo.links.count { it.assigned }
            val entCnt2 = topo.links.count { it.entangled }
            val utilCnt2 = topo.links.count { it.utilized }

            println("    assigned=$assignCnt2, entangled=$entCnt2, utilized=$utilCnt2")

            if (topo.nodes.any { it.remainingQubits != it.nQubits }) {
                println("remainingQubits 未完全恢复！")
            } else {
                println("remainingQubits 全部恢复")
            }

        }

        // Episode 输出
        val succ = algo.countSuccessPair
        val fail = algo.countFailedPair
        val total = succ + fail
        val rate = if (total > 0) succ * 100.0 / total else 0.0

        println("\n===== Episode $episode 统计 =====")

        println("成功请求数：$succ")
        println("失败请求数：$fail")
        println("总请求数：$total")
        println("成功率：${"%.2f".format(rate)}%")
        val succSatisList = algo.cal_Satis_succ(algo.SuccPairs)
        val failSatisList = algo.cal_Satis_fail(algo.FailPairs)

        val weightSatis = succSatisList.sum() + failSatisList.sum()
        val weightSatisFormatted = String.format("%.2f", weightSatis).toDouble()
        println("网络满意度：$weightSatisFormatted")
        println("====================================")
    }

    println("Training Finished.")
}
