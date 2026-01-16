package quantum.rl

import quantum.topo.Topo
import quantum.topo.Node
import quantum.algorithm.AlgorithmNotCleanSD.Quadruple
import quantum.algorithm.PickedPathNotClearSD

/**
 * ===========================
 *  FINAL STATE STRUCTURE
 * ===========================
 *
 * state = [
 *     GraphEmbedding(32),
 *     QpEmbedding(16),
 *     RequestEmbedding(16),
 *     PathEmbedding(8)
 * ]  → 共 72 维
 *
 * - GraphEmbedding：网络全局资源负载、成功率、链路拥塞等统计
 * - QpEmbedding：本时隙Qp队列的整体需求压力、急迫性、优先级情况
 * - RequestEmbedding：当前 req 的基础字段（Dd、AT 等）
 * - PickEmbedding(5)   // EXT, Wmax, hop, srcID, dstID
 */
object StateBuilder {

    // ============================================================
    // 主入口：构建 64维状态向量
    // ============================================================
    fun build(
        topo: Topo,
        req: Quadruple<Node,Node,Int,Int,Int,Int,Int>,
        Qp: List<Quadruple<Node,Node,Int,Int,Int,Int,Int>>,
        pick: PickedPathNotClearSD?
    ): FloatArray {

        val graphEmb = buildGraphEmbedding(topo)   // 32 维
        val qpEmb = buildQpEmbedding(Qp)           // 16 维
        val reqEmb = buildRequestEmbedding(req)    // 16 维
        val pickEmb  = buildPickEmbedding(pick)    // 5 维

        val state = FloatArray(graphEmb.size + qpEmb.size + reqEmb.size + pickEmb.size)
        var offset = 0

        System.arraycopy(graphEmb, 0, state, offset, graphEmb.size)
        offset += graphEmb.size

        System.arraycopy(qpEmb, 0, state, offset, qpEmb.size)
        offset += qpEmb.size

        System.arraycopy(reqEmb, 0, state, offset, reqEmb.size)
        offset +=reqEmb.size

        System.arraycopy(pickEmb,  0, state, offset, pickEmb.size)

        return state
    }

    // ============================================================
    // Part 1: 图 embedding（32维）
    // ============================================================
    private fun buildGraphEmbedding(topo: Topo): FloatArray {

        // 节点剩余 qubit
        val nodeRQ = topo.nodes.map { it.remainingQubits.toFloat() }
        val nodeRQStats = statistics7(nodeRQ)

        // 节点度数
        val degrees = topo.nodes.map { it.links.size.toFloat() }
        val degreeStats = statistics4(degrees)

        // 链路资源
        val linkGroups = topo.links.groupBy { it.n1 to it.n2 }
        val W = linkGroups.values.map { it.size.toFloat() }  // 总信道数
        val U = linkGroups.values.map { links ->
            links.count { it.assigned }.toFloat() / links.size.toFloat()
        }

        val WStats = statistics7(W)
        val UStats = statistics4(U)

        // 链路成功率 q 分布
        val linkSuccess = topo.links.map { it.l.toFloat() }
        val successStats = statistics4(linkSuccess)

        // 拼 32维
        val emb = FloatArray(32)
        var i = 0

        nodeRQStats.forEach { emb[i++] = it }   // 7
        degreeStats.forEach { emb[i++] = it }   // 11
        WStats.forEach { emb[i++] = it }        // 18
        UStats.forEach { emb[i++] = it }        // 22
        successStats.forEach { emb[i++] = it }  // 26

        // 图规模信息
        emb[i++] = topo.nodes.size.toFloat()    // 27
        emb[i++] = topo.links.size.toFloat()    // 28

        // padding to 32
        while (i < emb.size) emb[i++] = 0f

        return emb
    }

    // ============================================================
    // Part 2: Qp 全队列 embedding（16维）
    // ============================================================
    private fun buildQpEmbedding(
        Qp: List<Quadruple<Node,Node,Int,Int,Int,Int,Int>>
    ): FloatArray {

        val emb = FloatArray(16)
        if (Qp.isEmpty()) return emb

        val DdList = Qp.map { it.demand.toFloat() }
        val ATList = Qp.map { it.accepttime.toFloat() }
        val priList = Qp.map { it.priority.toFloat() }
        val WTList = Qp.map { it.waitTime.toFloat() }

        // 总需求、平均需求
        emb[0] = DdList.sum()
        emb[1] = DdList.average().toFloat()
        emb[2] = DdList.max() ?: 0f

        // 紧急任务数量（AT<=1）
        emb[3] = ATList.count { it <= 1 }.toFloat()

        // 平均时延
        emb[4] = ATList.average().toFloat()
        emb[5] = (ATList.min() ?: 0f)
        emb[6] = (ATList.max() ?: 0f)

        // 优先级分布
        emb[7] = priList.average().toFloat()
        emb[8] = priList.min() ?: 0f
        emb[9] = priList.max() ?: 0f

        // 平均等待时间
        emb[10] = WTList.average().toFloat()

        // 剩余部分 padding
        return emb
    }


    // ============================================================
    // Part 3: 单请求 embedding（16维）
    // ============================================================
    private fun buildRequestEmbedding(
        req: Quadruple<Node,Node,Int,Int,Int,Int,Int>
    ): FloatArray {

        val (src, dst, Dd, AT, priority, WT, aDemand) = req
        val v = FloatArray(16)

        val fd = aDemand.toFloat().coerceAtLeast(1f)
        val finishedRatio = (aDemand - Dd).toFloat() / fd
        val demandRatio = Dd.toFloat() / fd
        val urgency = 1f / (AT + 1f)

        // 基本字段
        v[0] = Dd.toFloat()
        v[1] = AT.toFloat()
        v[2] = priority.toFloat()
        v[3] = WT.toFloat()
        v[4] = aDemand.toFloat()

        // 加强特征
        v[5] = finishedRatio
        v[6] = demandRatio
        v[7] = urgency

        // src/dst 编码（弱特征，轻微辅助）
        v[8] = src.id.toFloat()
        v[9] = dst.id.toFloat()

        return v
    }


    // ============================================================
    // Part 4: 请求分配路径 embedding（5维）
    // ============================================================
    private fun buildPickEmbedding(pick: PickedPathNotClearSD?): FloatArray {

        val v = FloatArray(5)
        if (pick == null) return v

        val (ext, wmax, pathNodes) = pick

        v[0] = ext.toFloat()           // EXT
        v[1] = wmax.toFloat()          // 可分配最大宽度
        v[2] = pathNodes.size.toFloat()// hop数
        v[3] = pathNodes.first().id.toFloat() // src
        v[4] = pathNodes.last().id.toFloat()  // dst

        return v
    }
}



// ============================================================
// 工具函数：统计特征
// ============================================================
private fun statistics7(list: List<Float>): FloatArray {
    if (list.isEmpty()) return FloatArray(7){0f}
    val sorted = list.sorted()
    val mean = list.average().toFloat()

    val std = kotlin.math.sqrt(
        list.map { (it - mean) * (it - mean) }.average().toFloat()
    )

    val min = sorted.firstOrNull() ?: 0f
    val max = sorted.lastOrNull() ?: 0f
    val p25 = sorted[(sorted.size * 0.25f).toInt()]
    val p50 = sorted[(sorted.size * 0.5f).toInt()]
    val p75 = sorted[(sorted.size * 0.75f).toInt()]

    return floatArrayOf(min, mean, max, std, p25, p50, p75)
}

private fun statistics4(list: List<Float>): FloatArray {
    if (list.isEmpty()) return FloatArray(4){0f}
    val mean = list.average().toFloat()
    val min = list.min() ?: 0f
    val max = list.max() ?: 0f
    val std = kotlin.math.sqrt(
        list.map { (it - mean) * (it - mean) }.average().toFloat()
    )
    return floatArrayOf(min, mean, max, std)
}

