package quantum

import quantum.topo.Topo
import quantum.topo.Node
import quantum.algorithm.MyfirstTest
import quantum.algorithm.AlgorithmNotCleanSD
import quantum.rl.*

fun main() {
    val rewardLog = mutableListOf<Double>()
    val actionLog = mutableListOf<Int>()
    val successLog = mutableListOf<Int>()
    val failLog = mutableListOf<Int>()

    // ======================================================
    // 初始化拓扑
    // ======================================================
    val baseTopo = Topo.generate(
        n = 80, //50
        q = 0.9,
        k = 5,
        a = 0.01,
        degree = 6   //6
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
        topo.fullReset()
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
            val Qp = RequestSelector.selectProcessableRequests(Qc)
            // 使用 FIFO 调度
//            val Qp = FifoRequestSelector.selectProcessableRequests(Qc)
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

                println(">>> 处理请求 $req, path=${pick.third.map { it.id }}, EXT=${pick.first}, Wmax=${pick.second}")

                // ----------- RL 决定 w（关键部分） -----------
                val state = StateBuilder.build(topo, req, Qp, pick)
//                val w = agent.selectAction(state).coerceIn(0, pick.second)
                val Wmax = minOf(pick.second, req.demand)
                /*val action = agent.selectAction(state, Wmax)
                val w = action.coerceIn(0, Wmax)
                println("RL分配 w=$w (Wmax=${pick.second}, demand=${req.demand})")*/
                // PPO 输出的是“比例动作索引”
                val action = agent.selectAction(state)
                // 改进的映射函数：避免极端值，添加小的随机探索
                val exploration = if (episode < 5) {
                    // 早期更多探索
                    kotlin.random.Random.nextDouble(-0.15, 0.15)
                } else {
                    // 后期减少探索
                    kotlin.random.Random.nextDouble(-0.05, 0.05)
                }
                // 映射为 [0,1] 比例
                val ratio = action.toDouble() / (agent.actionDim - 1)
                // 实际分配量
                val w = kotlin.math.floor(ratio * Wmax).toInt()
                println("RL分配: action=$action, ratio=%.2f, w=$w, Wmax=$Wmax".format(ratio))

                val succBefore = topo.getEstablishedEntanglements(req.src, req.dst).size //分配前快照
                // ----------- 实际分配 -----------
                algo.pickAndAssignPath_rl(
                    pick = pick,
                    w = w,
                    SlesrcDstPairs = emptyList()
                )

                // ----------- 计算 step 实际完成数量 -----------
                val succAfter = topo.getEstablishedEntanglements(req.src, req.dst).size
                val succDiff = (succAfter - succBefore).coerceAtLeast(0)
                val waste = (w - succDiff).coerceAtLeast(0)

                println(">>> 分配完毕：succDiff=$succDiff, waste=$waste")

                // ----------- Step Reward -----------
                val fb = RewardCalculator.StepFeedback(
                    succPairsOnThisPath = succDiff,
                    waste = waste,
                    finished = false,
                    failedOrTimeout = false
                )
                val stepReward = RewardCalculator.calcStepReward(
                    req = req,
                    Wmax = Wmax,
                    w = w,
                    ratio = ratio,  // 添加这个参数
                    fb = fb
                )
                rewardLog.add(stepReward)
                actionLog.add(action)

                // ----------- PPO 缓冲区存储 -----------
                agent.storeTransition(
                    state = state,
                    action = action,
                    reward = stepReward,
                    done = false
                )
            }

            algo.P2Extra()

            algo.P4()

            // 处理完成或超时
            val updatedQc = algo.If_TimeOut_or_Finished(Qc)
            println("时隙结束: 已完成=${algo.countSuccessPair}, 失败=${algo.countFailedPair}")

            Qc.clear()
            Qc.addAll(updatedQc)

            topo.clearEntanglements()

            val assignCnt2 = topo.links.count { it.assigned }
            val entCnt2 = topo.links.count { it.entangled }
            val utilCnt2 = topo.links.count { it.utilized }

            println("assigned=$assignCnt2, entangled=$entCnt2, utilized=$utilCnt2")

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
        successLog.add(algo.countSuccessPair)
        failLog.add(algo.countFailedPair)

        // ======= PPO 更新 =======
        agent.update()

    }
    PlotRL(
        rewardLog = rewardLog,
        actionLog = actionLog,
        successLog = successLog,
        failLog = failLog,
    ).save()

    println("Training Finished.")
}

