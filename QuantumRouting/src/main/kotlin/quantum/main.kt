package quantum

import quantum.algorithm.*
import quantum.topo.Topo
import utils.*
import java.awt.event.WindowEvent
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.*
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.WindowConstants
import kotlin.math.pow
import quantum.rl.RLStateWriter
import quantum.rl.RequestQuadruple
// 大规模拓扑运行
fun sim() {
  // 1. 拓扑集合生成
  val topos = (nList * dList).map { (n, d) ->
    n to d to Topo.generate(n, 0.9, 5, 0.1, d)
  }.toMap()
  
  val alphaStore = ReducibleLazyEvaluation<Pair<Topo, Double>, Double>({ (topo, p) ->
    dynSearch(1E-10, 1.0, p, { x ->
      topo.links.map { Math.E.pow(-x * +(it.n1.loc - it.n2.loc)) }.sum() / topo.links.size
    }, false, 0.001)
  })
  val repeat = 1000 // 时隙数量
  //  2. 外层拓扑循环
  topoRange.forEach { topoIdx ->
    val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    // 进入拓扑循环
    allAvailableSettings.forEach { (d, n, p, q, k, nsd) ->
      val topo = topos[n to d]!!
      val alpha = alphaStore[topo to p] // topo的参数

      // 2.1每个时隙随机生成nsd个请求对
      val testSet = (1..repeat).map {
        (0 until n).shuffled(randGen).take(2 * nsd).chunked(2).map { it.toPair() }
      } 

      // 2.2各算法实例化(没有执行)
      val algorithms = synchronized(topo) {
        topo.q = q
        topo.k = k
        topo.alpha = alpha
        // 各算法实例化
        val algorithms = mutableListOf(
          OnlineAlgorithm(Topo(topo)),
          OnlineAlgorithm(Topo(topo), false), //false表示关闭负载均衡
          CreationRate(Topo(topo)),
          SingleLink(Topo(topo)),
          GreedyGeographicRouting(Topo(topo)),
          GreedyHopRouting(Topo(topo))
        )     
        if (n == nList.first() && q == qList.first() && p == pList.first() && d == dList.first() && k == kList.first()) {
          algorithms.addAll(listOf(
            BotCap(Topo(topo)),
            SumDist(Topo(topo)), MultiMetric(Topo(topo)),     
            BotCap(Topo(topo), false), CreationRate(Topo(topo), false),
            SumDist(Topo(topo), false), MultiMetric(Topo(topo), false),
            SingleLink(Topo(topo), false)
          ))
        }
        // 检查跳过已完成的实验
        algorithms.filter {
          val done = try {
            File("dist/${id(n, topoIdx, q, k, p, d, nsd, it.name)}.txt").readLines().drop(2).any { it.startsWith("--") }
          } catch (e: Exception) {false}
          if (done)
            println("skip ${id(n, topoIdx, q, k, p, d, nsd, it.name)}")
          !done
        }
      }
      // 2.3中间层算法循环（算法执行）
      algorithms.forEach { solver ->
        solver.settings = id(n, topoIdx, q, k, p, d, nsd, solver.name)
        val fn = "dist/${solver.settings}.txt" //algorithms的每个实例为solver
      
        // 进入算法循环  
        executor.execute {
          val topo = solver.topo
          println(topo.getStatistics())
          
          solver.logWriter = BufferedWriter(FileWriter(fn))
          // 【新增 1】状态输出文件(RL)
        //   val graphEncWriter = BufferedWriter(FileWriter("dist/graph-encoding-${solver.name}-${n}-${d}.jsonl"))
        //   var slotIndex = 0
          // 【新增结束】
          // 2.3.2 每个时隙循环
          testSet.forEach {
            // 【新增 2】输出每个时隙图状态
            // val features = GraphEncoding.collect(topo)
            // GraphEncoding.process(features, graphEncWriter, slotIndex, solver.name)
            // slotIndex += 1
            // 【新增结束】
            // 该时隙内该算法实例work
            solver.work(it.map { Pair(topo.nodes[it.first], topo.nodes[it.second]) })
          }

          // 【新增 3】关闭状态文件写入
        //   graphEncWriter.close()
          // 【新增结束】
        //   solver.logWriter.append("-----------\n")
        //   solver.logWriter.append(topo.toString()+ "\n")
        //   solver.logWriter.append(topo.getStatistics()+ "\n")
        //   solver.logWriter.close()
        }
      }
    }
    
    // 2.2拓扑循环结束
    executor.shutdown()
    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
  }
}



// 泊松分布请求生成
fun generatePoissonSample(mean: Double, randGen: Random): Int {
  var k = 0
  var p = 1.0
  val L = Math.exp(-mean)
  while (p > L) {
    k++
    p *= randGen.nextDouble()
  }
  return k - 1
}
// 单拓扑运行
fun simpleTest() {

  // 1. 单个拓扑生成
  val netTopology = Topo.generate(50, 0.9, 5, 0.1, 6)

  // 目标 p + alpha 搜索
  val pList = listOf(0.9, 0.85, 0.8)
  val alphas = pList.map { expectedAvgP ->
      var alpha = 0.1
      var step = 0.1
      var lastAdd = true

      while (true) {
          val topo = Topo(
              netTopology.toString().lines().mapIndexed { i, line ->
                  if (i == 1) alpha.toString() else line
              }.joinToString("\n")
          )

          val avgP = topo.links.map {
              Math.E.pow(-alpha * +(it.n1.loc - it.n2.loc))
          }.sum() / topo.links.size

          if (Math.abs(avgP - expectedAvgP) / expectedAvgP < 0.001)
              break
          else if (avgP > expectedAvgP) {
              if (!lastAdd) step /= 2
              alpha += step
              lastAdd = true
          } else {
              if (lastAdd) step /= 2
              alpha -= step
              lastAdd = false
          }
      }
      alpha
  }

  // 2. 泊松分布生成请求
  val testSet04 = mutableListOf<List<List<Pair<Int, Int>>>>()
  val combinations = netTopology.nodes.combinations(2)
  val randGen = Random(System.currentTimeMillis())
  val meanReq = 5.0

  repeat(100) {
      val reqCount = generatePoissonSample(meanReq, randGen)
      val randomPairs = combinations.shuffled(randGen)
          .take(reqCount)
          .map { it[0].id to it[1].id }

      testSet04.add(listOf(randomPairs))
  }

  val children = mutableListOf<String>()

  // 3. 主循环
  pList.zip(alphas).forEach { (pVal, alphaVal) ->

      val topo = Topo(
          netTopology.toString().lines().mapIndexed { i, line ->
              if (i == 1) alphaVal.toString() else line
          }.joinToString("\n")
      )

       println(topo.getStatistics())

      val algorithms = listOf(MyfirstTest(Topo(topo)))
      algorithms.forEach {
          it.logWriter = BufferedWriter(FileWriter("dist/test-${it.name}.txt"))
      }

      val results: MutableList<MutableList<Pair<Double, Double>>> =
          MutableList(algorithms.size) { mutableListOf<Pair<Double, Double>>() }
        //每个Algorithm都是一个solver,外层算法循环
      algorithms.parallelStream().forEach { solver ->

          val topoSolver = solver.topo
          solver.prepare()

          val graphEncWriter = BufferedWriter(
              FileWriter("dist/rl-state-${solver.name}.jsonl")
          )

          var slotIndex = 0
          val algoIdx = algorithms.indexOf(solver)

          // === 主计算区，各算法的时隙循环 ===
          val slotResults: List<Pair<Double, Double>> =
              testSet04.map { slotRequestsLists ->

                  val perSlotResults: MutableList<Pair<Int, Int>> =
                      mutableListOf()

                  slotRequestsLists.forEach { rawPairs ->

                      println("===== Slot $slotIndex =====")
                      println(topoSolver.getStatistics())
                      println("===========================")

                      val randomInner = Random()
                        //该时隙请求初始化生成
                      val requestsThisSlot: List<RequestQuadruple> =
                          rawPairs.map { (srcId, dstId) ->
                              val demand = randomInner.nextInt(5) + 1
                              val accepttime = randomInner.nextInt(5) + 1
                              val priority = randomInner.nextInt(5) + 1

                              AlgorithmNotCleanSD.Quadruple(
                                  topoSolver.nodes[srcId],
                                  topoSolver.nodes[dstId],
                                  demand,
                                  accepttime,
                                  priority,
                                  0,
                                  demand
                              )
                          }

                      RLStateWriter.writeSlotState(
                          topo = topoSolver,
                          requests = requestsThisSlot,
                          slotIndex = slotIndex,
                          solverName = solver.name,
                          writer = graphEncWriter
                      )

                      slotIndex++

                      // solver.work 只接受 Pair<Node,Node>
                      val result: Pair<Int, Int> = solver.work(requestsThisSlot)

                      perSlotResults.add(result)
                  }

                  val avgEnt =
                      perSlotResults.sumBy { it.first }.toDouble() / perSlotResults.size
                  val avgSucc =
                      perSlotResults.sumBy { it.second }.toDouble() / perSlotResults.size

                  Pair(avgEnt, avgSucc)
              }

          results[algoIdx].addAll(slotResults)

          graphEncWriter.close()
      }

      // 输出日志
      algorithms.forEach {
          it.logWriter.append("-----------\n")
          it.logWriter.append(topo.toString() + "\n")
          it.logWriter.append(topo.getStatistics() + "\n")
      }

      // 画图信息
      children += listOf(
          """
          {
            'name': 'num-ebit-pairs-${topo.n}-${pVal}-${topo.q}',
            'solutionList': ${algorithms.map { "\"${it.name}\"" }},
            'xTitle': '\\# src-dst pairs in one time slot',
            'yTitle': '\\# success pairs',
            'x': ${(1..results[0].size).toList()},
            'y': ${results.map { it.map { it.first } }},
          }
          """.trimIndent(),
          """
          {
            'name': 'num-succ-pairs-${topo.n}-${pVal}-${topo.q}',
            'solutionList': ${algorithms.map { "\"${it.name}\"" }},
            'xTitle': '\\# src-dst pairs in one time slot',
            'yTitle': '\\# entanglements',
            'x': ${(1..results[0].size).toList()},
            'y': ${results.map { it.map { it.second } }},
          }
          """.trimIndent()
      )

      // 写出 plot 数据
      File("../plot/last-plot-data.json").writeText(
          """
          {
            'type': "line",
            'figWidth': 600,
            'figHeight': 350,
            'usetex': True,
            'legendLoc': 'best',
            'legendColumn': 1,
            'markerSize': 8,
            'lineWidth': 1,
            'xLog': False,
            'yLog': False,
            'xGrid': True,
            'yGrid': True,
            'xFontSize': 16,
            'xTickRotate': False,
            'yFontSize': 16,
            'legendFontSize': 14,
            'output': True,
            'children': $children
          }
          """
      )
  }
}




var visualize = true

class Main {
  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      visualize = try {
        val solver = MyfirstTest(Topo.generate(50,0.9,5,0.1,6))
        solver.logWriter = BufferedWriter(FileWriter("""dist/test-gui.txt"""))
        
        val window = Visualizer(solver)
        window.title = "Routing Quantum Entanglement - ${solver.name}"
        window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE)
        window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
        true
      } catch (e: java.lang.Exception) {
        print("WARNING: GUI not available. Ignore this? \n(The final results are the same. Only difference: \n you CANNOT see the network visualizer) [Y/N]")
        val scanner = Scanner(System.`in`)
        if (scanner.next().first().toLowerCase() == 'y')
          false
        else throw Exception("No GUI available, and user refuses to continue. ")
      }

      val plotDir = File("../plot")
      plotDir.mkdirs()

      val file1 = File("dist/Satisf.txt")
      if (file1.exists()) file1.delete()
      val file2 = File("dist/SuccRate.txt")
      if (file2.exists()) file2.delete()
      val file3 = File("dist/SumPair.txt")
      if (file3.exists()) file3.delete()

      simpleTest()
      try {
        val l = args.map { it.toInt() }
        if (l.isNotEmpty()) nList = l
      } catch (e: Exception) {}
    }
  }
}
