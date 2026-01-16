// 文件路径：src/main/kotlin/rl/RLStateWriter.kt
package quantum.rl

import quantum.topo.Topo
import quantum.topo.Node
import quantum.algorithm.AlgorithmNotCleanSD
import java.io.BufferedWriter
import kotlin.math.exp

// 统一别名（非常重要）
typealias RequestQuadruple =
    AlgorithmNotCleanSD.Quadruple<Node, Node, Int, Int, Int, Int, Int>

/**
 * 单个请求的可序列化特征。
 */
data class RequestFeature(
    val src: Int,
    val dst: Int,
    val Dd: Int,
    val AT: Int,
    val WT: Int,
    val priority: Int
)

data class EdgeIndex(
    val id: Int,
    val u: Int,
    val v: Int
)

data class SlotState(
    val slot: Int,
    val solver: String,
    val nNodes: Int,
    val nEdges: Int,
    val nodeFeatures: List<List<Double>>,
    val edgeFeatures: List<List<Double>>,
    val edges: List<EdgeIndex>,
    val requests: List<RequestFeature>
)

object RLStateWriter {

    private fun computeNodeDemandSums(
        topo: Topo,
        requests: List<RequestQuadruple>
    ): Pair<DoubleArray, DoubleArray> {

        val srcSum = DoubleArray(topo.n)
        val dstSum = DoubleArray(topo.n)

        requests.forEach { q ->
            val s = q.src.id
            val t = q.dst.id
            srcSum[s] += q.demand.toDouble()
            dstSum[t] += q.demand.toDouble()
        }

        return srcSum to dstSum
    }

    private fun buildNodeFeatures(
        topo: Topo,
        requests: List<RequestQuadruple>
    ): List<List<Double>> {

        val degrees = IntArray(topo.n)
        topo.links.forEach {
            degrees[it.n1.id]++
            degrees[it.n2.id]++
        }

        val (srcSum, dstSum) = computeNodeDemandSums(topo, requests)

        return topo.nodes.map { node ->
            listOf(
                degrees[node.id].toDouble(),
                srcSum[node.id],
                dstSum[node.id],
                0.0
            )
        }
    }

    private fun buildEdgeStructureAndFeatures(topo: Topo): Pair<List<EdgeIndex>, List<List<Double>>> {

        val edges = mutableListOf<EdgeIndex>()
        val features = mutableListOf<List<Double>>()

        topo.links.forEachIndexed { idx, e ->
            val u = e.n1.id
            val v = e.n2.id

            val length = e.l
            val W = 1.0
            val U = if (e.entangled) 1.0 else 0.0
            val successProb = exp(-topo.alpha * length)

            edges += EdgeIndex(idx, u, v)
            features += listOf(
                length,
                W,
                U,
                successProb
            )
        }

        return edges to features
    }

    private fun buildRequestFeatures(
        requests: List<RequestQuadruple>
    ): List<RequestFeature> {
        return requests.map { q ->
            RequestFeature(
                src = q.src.id,
                dst = q.dst.id,
                Dd = q.demand,
                AT = q.accepttime,
                WT = q.waitTime,
                priority = q.priority
            )
        }
    }

    fun writeSlotState(
        topo: Topo,
        requests: List<RequestQuadruple>,
        slotIndex: Int,
        solverName: String,
        writer: BufferedWriter
    ) {
        val (edges, edgeFeatures) = buildEdgeStructureAndFeatures(topo)
        val nodeFeatures = buildNodeFeatures(topo, requests)
        val reqFeatures = buildRequestFeatures(requests)

        val sb = StringBuilder()

        sb.append("{")
        sb.append("\"slot\":$slotIndex,")
        sb.append("\"solver\":\"$solverName\",")

        sb.append("\"graph\":{")
        sb.append("\"n_nodes\":${topo.n},")
        sb.append("\"n_edges\":${topo.links.size},")

        sb.append("\"node_features\":[")
        sb.append(nodeFeatures.joinToString(",") {
            "[" + it.joinToString(",") { x -> "%.6f".format(x) } + "]"
        })
        sb.append("],")

        sb.append("\"edge_features\":[")
        sb.append(edgeFeatures.joinToString(",") {
            "[" + it.joinToString(",") { x -> "%.6f".format(x) } + "]"
        })
        sb.append("],")

        sb.append("\"edges\":[")
        sb.append(edges.joinToString(",") {
            """{"id":${it.id},"u":${it.u},"v":${it.v}}"""
        })
        sb.append("]")

        sb.append("},")

        sb.append("\"requests\":[")
        sb.append(reqFeatures.joinToString(",") {
            """{"src":${it.src},"dst":${it.dst},"Dd":${it.Dd},"AT":${it.AT},"WT":${it.WT},"priority":${it.priority}}"""
        })
        sb.append("]")

        sb.append("}")

        writer.write(sb.toString())
        writer.newLine()
        writer.flush()
    }
}
