package quantum.rl

import quantum.topo.Topo
import java.io.BufferedWriter
import java.util.*
import kotlin.math.sqrt

/** 轻量图特征（与请求无关） */
data class GraphFeatures(
    val n: Int,
    val m: Int,
    val degrees: IntArray,
    val avgEdgeLen: Double,
    val density: Double
)

/** 从 topo 抽取图特征 */
fun collectGraphFeatures(topo: Topo): GraphFeatures {
    val n = topo.n
    val degrees = IntArray(n)
    var edgeLenSum = 0.0
    topo.links.forEach { e ->
        val u = e.n1.id
        val v = e.n2.id
        degrees[u] += 1
        degrees[v] += 1
        val loc1 = e.n1.loc
        val loc2 = e.n2.loc
        val dx = loc1[0] - loc2[0]
        val dy = loc1[1] - loc2[1]
        edgeLenSum += sqrt(dx * dx + dy * dy)
    }
    val m = topo.links.size
    val avgEdgeLen = if (m > 0) edgeLenSum / m else 0.0
    val density = if (n <= 1) 0.0 else (2.0 * m) / (n.toDouble() * (n - 1).toDouble())
    return GraphFeatures(n = n, m = m, degrees = degrees, avgEdgeLen = avgEdgeLen, density = density)
}

/**
 * 图处理函数：把特征做成稳定编码向量，并写入一行 JSON（JSON Lines）
 */
fun processGraphAndWrite(
    feat: GraphFeatures,
    writer: BufferedWriter,
    slotIndex: Int,
    solverName: String
) {
    val n = feat.n.toDouble()
    val m = feat.m.toDouble()
    val avgDeg = if (feat.n > 0) feat.degrees.sum().toDouble() / feat.n else 0.0
    val density = feat.density
    val avgEdgeLen = feat.avgEdgeLen

    var varSum = 0.0
    feat.degrees.forEach { d -> varSum += (d - avgDeg) * (d - avgDeg) }
    val degStd = if (feat.n > 0) sqrt(varSum / feat.n) else 0.0

    val embed = listOf(n, m, avgDeg, density, avgEdgeLen, degStd)

    val json = buildString {
        append("{")
        append("\"slot\":").append(slotIndex).append(',')
        append("\"solver\":\"").append(solverName).append("\",")
        append("\"embed\":[")
        embed.forEachIndexed { idx, v ->
            if (idx > 0) append(',')
            append(String.format(Locale.US, "%.6f", v))
        }
        append("]")
        append("}")
    }
    writer.append(json).append('\n')
    writer.flush()
}

/** 提供统一入口，方便 main.kt 使用 */
object GraphEncoding {
    fun collect(topo: Topo): GraphFeatures = collectGraphFeatures(topo)
    fun process(feat: GraphFeatures, writer: BufferedWriter, slotIndex: Int, solverName: String) =
        processGraphAndWrite(feat, writer, slotIndex, solverName)
}
