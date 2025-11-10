package quantum.algorithm

import quantum.topo.*
import utils.ReducibleLazyEvaluation
import utils.also
import utils.pmap
import utils.require
import java.util.*
import kotlin.collections.HashMap
import kotlin.math.ceil

open class MyfirstTest(topo: Topo, val allowRecoveryPaths: Boolean = true) : AlgorithmNotCleanSD(topo) {
  override val name: String = "MyfirstTest"

  override fun prepare() {
  }

  val majorPaths = mutableListOf<PickedPathNotClearSD>()
  val recoveryPaths = HashMap<PickedPathNotClearSD, LinkedList<PickedPathNotClearSD>>()

  // 进入其排序P2前应该先完成请求的优先级计算及

/*  override fun P2() {
    require({ topo.isClean() })
    majorPaths.clear()
    recoveryPaths.clear()
    pathToRecoveryPaths.clear()

    while (true) {
      val candidates = calCandidates(SlesrcDstPairs)
      // first代表期望吞吐量
      // 把它改成需求量
      candidates.forEach{candidates->
        println(candidates.third)
      }
      println()
      val pick = candidates.maxBy { it.first }
      if (pick != null && pick.first > 0.0) {
        println(pick.third)
        pickAndAssignPath_rl(pick,SlesrcDstPairs)
      } else {
        break
      }
    }
    if (allowRecoveryPaths)
      P2Extra()
  }*/

  override fun P2() {
    require({ topo.isClean() })
    majorPaths.clear()
    recoveryPaths.clear()
    pathToRecoveryPaths.clear()

    val visitedMap = mutableMapOf<Pair<Node, Node>, Boolean>()
    SlesrcDstPairs.forEach { triple ->
      val pair = Pair(triple.first, triple.second)
      visitedMap[pair] = false
    }

    while (true) {
      var candidates = calCandidates(SlesrcDstPairs)

/*      // 遍历候选路径，并打印每个 SD 对
      candidates.forEach { candidate ->
        println(candidate.third)
      }
      println()*/

      // 对所有路径按照期望吞吐量由大到小排序
      val sortedCandidates = candidates.sortedByDescending { it.first }

      // 初始化索引为 0
      var lastIndex = 0
      var pick: PickedPathNotClearSD? = null


      // 循环直到找到未被访问过的路径或者遍历完所有路径
      while (lastIndex < sortedCandidates.size) {
        pick = sortedCandidates.getOrNull(lastIndex)
        // 首先pick要非空
        if (pick != null && pick.first > 0.0) {
          // 获取选定路径对应的 SD 对
          val SDPair: Pair<Node, Node> = Pair(pick.third.first(),pick.third.last())

          // 检查该 SD 对是否已被访问过
          if (!visitedMap.getOrDefault(SDPair, false)) {
            // 如果未被访问，则跳出循环
            break
          }

          // 如果已被访问，则尝试下一条路径
          lastIndex++

        } else {
          break
        }
      }

      // 检查是否有可选路径
      if (pick != null && pick.first > 0.0) {
        // 执行资源分配
/*        println(pick.third)
        println()*/
        pickAndAssignPath_rl(pick, SlesrcDstPairs)

        // 将该 SD 对标记为已访问
        val SDPair: Pair<Node, Node> = Pair(pick.third.first(), pick.third.last())
        visitedMap[SDPair] = true
      } else {
        // 如果没有满足条件的候选路径，则跳出循环
        break
      }

      // 检查是否所有 SD 对都已被访问
      val allVisited = visitedMap.all { it.value }
      if (allVisited) {
        // 重置所有 SD 对的访问状态
        visitedMap.replaceAll { _, _ -> false }
      }
    }


    if (allowRecoveryPaths)
      P2Extra()
  }

  // 这个函数是在P4之后进行，主要是本次时隙处理的请求进行判断
  // 判断是否满足要求：成功建立的 ebits == demand

  override fun If_TimeOut_or_Finished(srcDstPairs: MutableList<Quadruple<Node, Node, Int, Int, Int, Int,Int>>): MutableList<Quadruple<Node, Node, Int, Int, Int, Int,Int>> {
    val iterator = srcDstPairs.iterator()
    while (iterator.hasNext()) {
      val quadruple = iterator.next()
      val demand = quadruple.demand
      val src = quadruple.src
      val dst = quadruple.dst
      val connectionsCount = topo.getEstablishedEntanglements(src, dst).size

      // 一个时间片已过
      quadruple.waitTime++
      quadruple.accepttime--

      // 判断是否超时

      //可接受时延为一个时间片
      if (quadruple.accepttime == 0) {
        if (demand <= connectionsCount) {
          // 按时完成的计数加1
          quadruple.demand=0
          SuccPairs.add(quadruple)
          countSuccessPair++
        } else {
          // 失败
          countFailedPair++
          FailPairs.add(quadruple)
        }
        // 一个时间片已过，删除该请求
        iterator.remove()
      } else {
        // 若未超时，判断需求是否被满足
        // 需求小于等于已成功建立的ebis
        if (demand <= connectionsCount) {
          // 按时完成的计数加1
          quadruple.demand=0
          SuccPairs.add(quadruple)
          countSuccessPair++
          iterator.remove()
        } else {
          // 未能满足
          val newDemand = demand - connectionsCount
          quadruple.demand = newDemand
        }
      }
    }
    return srcDstPairs
  }

  fun P2Extra() {
    majorPaths.forEach { majorPath ->
      val (_, _, p) = majorPath
      (1..topo.k).forEach { l ->
        (0..p.size - l - 1).forEach { i ->
          val (src, dst) = p[i] to p[i + l]


          //此处不需要传输demand进行比较了？？？我感觉还是需要
          val candidates = calCandidates_recovery(listOf(Pair(src, dst)))
          val pick = candidates.maxBy { it.first }
          if (pick != null && pick.first > 0.0) {
            pickAndAssignPath(pick, majorPath)
          }
        }
      }
    }
  }


  fun pickAndAssignPath(pick: PickedPathNotClearSD, majorPath: PickedPathNotClearSD? = null) {
    // if majorPath is null: *pick* is a major path
    // else: *pick* is a recovery path
    if (majorPath != null)
      recoveryPaths[majorPath]!!.add(pick)
    else {
      majorPaths.add(pick)
      recoveryPaths[pick] = LinkedList<PickedPathNotClearSD>()
    }

    val width = pick.second
    val toAdd = Triple(mutableListOf<LinkBundle>(), width, mutableMapOf<Edge, List<Pair<Connection, Int>>>())

    pick.third.edges().forEach { (n1, n2) ->
      val links = n1.links.filter { !it.assigned && it.contains(n2) }.sortedBy { it.id }.subList(0, width)
      require({ links.size == width })
      toAdd.first.add(links)
      links.forEach {
        //分配qubits并且尝试产生纠缠
        it.assignQubits()
        it.tryEntanglement() // just for display
      }
    }
  }
  fun pickAndAssignPath_rl(pick: PickedPathNotClearSD,SlesrcDstPairs:List<Triple<Node,Node,Int>>, majorPath: PickedPathNotClearSD? = null) {
    // if majorPath is null: *pick* is a major path
    // else: *pick* is a recovery path
    if (majorPath != null)
      recoveryPaths[majorPath]!!.add(pick)
    else {
      majorPaths.add(pick)
      recoveryPaths[pick] = LinkedList<PickedPathNotClearSD>()
    }

    // 按照需求进行资源分配
    // 取需求与带宽的最小值
/*    val width =findDemandForPairs(SlesrcDstPairs,pick)*/

    val width= pick.second
    val maxM = minOf(width,findDemandForPairs(SlesrcDstPairs,pick))
    //打印观察是否满足
/*    println(width)
    println(findDemandForPairs(SlesrcDstPairs,pick))
    println(maxM)*/
    val toAdd = Triple(mutableListOf<LinkBundle>(), width, mutableMapOf<Edge, List<Pair<Connection, Int>>>())

    pick.third.edges().forEach { (n1, n2) ->
      // 找到路径上相邻节点的所有可用链路.只要width个
      val links = n1.links.filter { !it.assigned && it.contains(n2) }.sortedBy { it.id }.subList(0, maxM)
      require({ links.size == maxM })
      toAdd.first.add(links)
      links.forEach {
        //分配qubits并且尝试产生纠缠
        it.assignQubits()
        it.tryEntanglement() // just for display
      }
    }
  }

  // 找到当前SD对的需求量
  fun findDemandForPairs(SlesrcDstPairs: List<Triple<Node, Node, Int>>, pick: PickedPathNotClearSD): Int {
    var Demand = 0
    for ((src, dst, demand) in SlesrcDstPairs) {
      if (src == pick.third.first() && dst == pick.third.last()) {
        Demand=demand
      }
    }
    return Demand
  }

  // 使用 Dijkstra 算法来计算最短路径
  fun calCandidates(ops: List<Triple<Node, Node,Int>>): List<PickedPathNotClearSD> {
    return ops.pmap fxx@{ (src, dst, demand) ->
      // maxM代表最大带宽
      // 最大带宽与demand建立联系，用demand约束最大带宽，减少浪费。
      val maxM = minOf(src.remainingQubits, dst.remainingQubits)
      if (maxM == 0) return@fxx null

      var candidate: PickedPathNotClearSD? = null

      for (w in (maxM downTo 1)) {
        val failNodes = (topo.nodes - src - dst).filter { it.remainingQubits < 2 * w }.toHashSet()

        val edges = topo.links.filter {
          !it.assigned && it.n1 !in failNodes && it.n2 !in failNodes
        }.groupBy { it.n1 to it.n2 }.filter { it.value.size >= w }.map { it.key }.toHashSet()

        val neighborsOf = ReducibleLazyEvaluation<Node, MutableList<Node>>({ mutableListOf() })

        edges.forEach {
          neighborsOf[it.n1].add(it.n2)
          neighborsOf[it.n2].add(it.n1)
        }

        if (neighborsOf[src].isEmpty() || neighborsOf[dst].isEmpty())
          continue

        val prevFromSrc: HashMap<Node, Node> = hashMapOf()

        fun getPathFromSrc(n: Node): MutableList<Node> {
          val path = LinkedList<Node>()

          var cur = n
          while (cur != topo.sentinal) {
            path.addFirst(cur)
            cur = prevFromSrc[cur]!!
          }
          return path.toMutableList()
        }

        val E = topo.nodes.map { Double.NEGATIVE_INFINITY to DoubleArray(w + 1, { 0.0 }) }.toTypedArray()
        val q = PriorityQueue<Edge>(Comparator { (o1, _), (o2, _) ->
          -E[o1.id].first.compareTo(E[o2.id].first)
        })

        E[src.id] = Double.POSITIVE_INFINITY to DoubleArray(w + 1, { 0.0 })
        q.offer(src to topo.sentinal)

        while (q.isNotEmpty()) {
          val (u, prev) = q.poll()  // invariant: top of q reveals the node with highest e under m
          if (u in prevFromSrc) continue  // skip same node suboptimal paths
          prevFromSrc[u] = prev // record

          if (u == dst) {
            candidate = E[u.id].first to w also getPathFromSrc(dst)
            /*return@fxx candidate*/
            break
          }

          neighborsOf[u].forEach { neighbor ->
            val tmp = E[u.id].second.clone()
            val e = topo.e(getPathFromSrc(u) + neighbor, w, tmp)
            val newE = e to tmp
            val oldE = E[neighbor.id]

            if (oldE.first < newE.first) {
              E[neighbor.id] = newE
              q.offer(neighbor to u)
            }
          }
        }

        if (candidate != null) {
          //println(candidate)
          break
        }

      }
      //println(candidate)
      candidate
    }.filter{it != null}as List<PickedPathNotClearSD>
  }

  fun calCandidates_recovery(ops: List<Pair<Node, Node>>): List<PickedPathNotClearSD> {
    return ops.pmap fxx@{ (src, dst) ->
      val maxM = Math.min(src.remainingQubits, dst.remainingQubits)
      if (maxM == 0) return@fxx null

      var candidate: PickedPathNotClearSD? = null

      for (w in (maxM downTo 1)) {
        val failNodes = (topo.nodes - src - dst).filter { it.remainingQubits < 2 * w }.toHashSet()

        val edges = topo.links.filter {
          !it.assigned && it.n1 !in failNodes && it.n2 !in failNodes
        }.groupBy { it.n1 to it.n2 }.filter { it.value.size >= w }.map { it.key }.toHashSet()

        val neighborsOf = ReducibleLazyEvaluation<Node, MutableList<Node>>({ mutableListOf() })

        edges.forEach {
          neighborsOf[it.n1].add(it.n2)
          neighborsOf[it.n2].add(it.n1)
        }

        if (neighborsOf[src].isEmpty() || neighborsOf[dst].isEmpty())
          continue

        val prevFromSrc: HashMap<Node, Node> = hashMapOf()

        fun getPathFromSrc(n: Node): MutableList<Node> {
          val path = LinkedList<Node>()

          var cur = n
          while (cur != topo.sentinal) {
            path.addFirst(cur)
            cur = prevFromSrc[cur]!!
          }
          return path.toMutableList()
        }

        val E = topo.nodes.map { Double.NEGATIVE_INFINITY to DoubleArray(w + 1, { 0.0 }) }.toTypedArray()
        val q = PriorityQueue<Edge>(Comparator { (o1, _), (o2, _) ->
          -E[o1.id].first.compareTo(E[o2.id].first)
        })

        E[src.id] = Double.POSITIVE_INFINITY to DoubleArray(w + 1, { 0.0 })
        q.offer(src to topo.sentinal)

        while (q.isNotEmpty()) {
          val (u, prev) = q.poll()  // invariant: top of q reveals the node with highest e under m
          if (u in prevFromSrc) continue  // skip same node suboptimal paths
          prevFromSrc[u] = prev // record

          if (u == dst) {
            candidate = E[u.id].first to w also getPathFromSrc(dst)
            break
          }

          neighborsOf[u].forEach { neighbor ->
            val tmp = E[u.id].second.clone()
            val e = topo.e(getPathFromSrc(u) + neighbor, w, tmp)
            val newE = e to tmp
            val oldE = E[neighbor.id]

            if (oldE.first < newE.first) {
              E[neighbor.id] = newE
              q.offer(neighbor to u)
            }
          }
        }

        if (candidate != null) break
      }

      candidate
    }.filter { it != null } as List<PickedPathNotClearSD>
  }

  data class RecoveryPath(val path: Path, val width: Int, var taken: Int = 0, var available: Int = 0)

  val pathToRecoveryPaths = ReducibleLazyEvaluation<PickedPathNotClearSD, MutableList<RecoveryPath>>({ mutableListOf() })

  override fun P4() {
    majorPaths.forEach { pathWithWidth ->
/*      val (_, width, majorPath) = pathWithWidth*/

      // 跟据实际需求来进行纠缠交换和路径恢复
      val width = minOf(pathWithWidth.second,findDemandForPairs(SlesrcDstPairs,pathWithWidth))
/*      println(width)
      println(pathWithWidth.third)*/

      val majorPath = pathWithWidth.third
      val oldNumOfPairs = topo.getEstablishedEntanglements(majorPath.first(), majorPath.last()).size  // just for logging

      val recoveryPaths = this.recoveryPaths.get(pathWithWidth)!!.sortedBy { it.third.size * 10000 + majorPath.indexOf(it.third.first()) }

      recoveryPaths.forEach { (_, w, p) ->
        val available = p.edges().map { (n1, n2) -> n1.links.count { it.contains(n2) && it.entangled } }.min()!!
        pathToRecoveryPaths[pathWithWidth].add(RecoveryPath(p, w, 0, available))
      }

      val edges = (0..majorPath.size - 2).zip(1..majorPath.size - 1)
      val rpToWidth = recoveryPaths.map { it.third to it.second }.toMap().toMutableMap()

      for (i in (1..width)) {   // for w-width major path, treat it as w different paths, and repair separately
        // find all broken edges on the major path
        val brokenEdges = LinkedList(edges.filter { (i1, i2) ->
          val (n1, n2) = majorPath[i1] to majorPath[i2]
          n1.links.any { it.contains(n2) && it.assigned && it.notSwapped() && !it.entangled }
        })

        val edgeToRps = brokenEdges.map { it to mutableListOf<Path>() }.toMap()
        val rpToEdges = recoveryPaths.map { it.third to mutableListOf<Pair<Int, Int>>() }.toMap()

        recoveryPaths.forEach { (_, _, rp) ->  // rp is calculated from P2
          val (s1, s2) = majorPath.indexOf(rp.first()) to majorPath.indexOf(rp.last())

          (s1..s2 - 1).zip(s1 + 1..s2).filter { it in brokenEdges }.forEach {
            rpToEdges[rp]!!.add(it)
            edgeToRps[it]!!.add(rp)
          }
        }

        var realPickedRps = HashSet<Path>()
        var realRepairedEdges = hashSetOf<Pair<Int, Int>>()

        // try to cover the broken edges
        for (brokenEdge in brokenEdges) {
          if (realRepairedEdges.contains(brokenEdge)) continue  // repaired edges will never change this state, for low time complexity.
          var repaired = false
          var next = 0

          tryRp@ for (rp in edgeToRps[brokenEdge]!!.filter { rpToWidth[it]!! > 0 && it !in realPickedRps }
            .sortedBy { majorPath.indexOf(it.first()) * 10000 + majorPath.indexOf(it.last()) }) {  // available rp, sorted by positions of their first switch node
            if (majorPath.indexOf(rp.first()) < next) continue
            next = majorPath.indexOf(rp.last())

            val pickedRps = realPickedRps.toHashSet()
            val repairedEdges = realRepairedEdges.toHashSet()

            val otherCoveredEdges = rpToEdges[rp]!!.toHashSet() - brokenEdge

            for (edge in otherCoveredEdges) { // delete covered rps, or abort
              val prevRp = edgeToRps[edge]!!.intersect(pickedRps).minusElement(rp).firstOrNull()  // the previous rp is covered

              if (prevRp == null) {
                repairedEdges.add(edge)
              } else {
                continue@tryRp  // the rps overlap. taking time to search recursively. just abort
              }
            }

            repaired = true
            repairedEdges.add(brokenEdge)
            pickedRps.add(rp)

            (realPickedRps - pickedRps).forEach { rpToWidth[it] = rpToWidth[it]!! + 1 }   // release the previous rps
            (pickedRps - realPickedRps).forEach { rpToWidth[it] = rpToWidth[it]!! - 1 }

            realPickedRps = pickedRps
            realRepairedEdges = repairedEdges
            break  // one rp is sufficient. after all, we cannot swap one link to two
          }

          if (!repaired) {  // this major path cannot be repaired
            break  //
          }
        }

        val p = realPickedRps.fold(majorPath) { acc, rp ->
          val pathData = pathToRecoveryPaths[pathWithWidth].first { it.path == rp }
          pathData.taken++

          val toAdd = rp.edges()
          val toDelete = acc.dropWhile { it != rp.first() }.dropLastWhile { it != rp.last() }.edges()

          val edgesOfNewPathAndCycles = acc.edges().toSet() - toDelete + toAdd

          val p = topo.shortestPath(edgesOfNewPathAndCycles, acc.first(), acc.last(), ReducibleLazyEvaluation({ 1.0 })).second
          p
        }

        p.dropLast(2).zip(p.drop(1).dropLast(1)).zip(p.drop(2)).forEach { (n12, next) ->
          val (prev, n) = n12

          val prevLinks = n.links.filter { it.entangled && !it.swappedAt(n) && it.contains(prev) && !it.utilized }.sortedBy { it.id }.take(1)
          val nextLinks = n.links.filter { it.entangled && !it.swappedAt(n) && it.contains(next) && !it.utilized }.sortedBy { it.id }.take(1)

          prevLinks.zip(nextLinks).forEach { (l1, l2) ->
            n.attemptSwapping(l1, l2)
            l1.utilize()
            if (next == p.last()) {
              l2.utilize()
            }
          }
        }
      }

      // succ表示成功建立的ebits数量
      var succ = 0
      if (majorPath.size > 2) {
        succ = topo.getEstablishedEntanglements(majorPath.first(), majorPath.last()).size - oldNumOfPairs
      } else {
        val SDlinks = majorPath.first().links.filter { it.entangled && !it.swappedAt(majorPath.first()) && it.contains(majorPath.last()) && !it.utilized }.sortedBy { it.id }
        if (SDlinks.isNotEmpty()) {
          succ = SDlinks.size.coerceAtMost(width)
          (0..succ - 1).forEach { pid ->
            SDlinks[pid].utilize()
          }
        }
      }
      logWriter.appendln(""" ${majorPath.map { it.id }}, $width $succ""")
      pathToRecoveryPaths[pathWithWidth].forEach {
        logWriter.appendln("""  ${it.path.map { it.id }}, $width ${it.available} ${it.taken}""")
      }
    }

    logWriter.appendln()
  }

}
