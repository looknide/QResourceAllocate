package quantum.algorithm

import quantum.rl.RLAgent
import quantum.rl.StateBuilder
import quantum.topo.*
import utils.require
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.util.*
import kotlin.math.ceil
import kotlin.to

abstract class AlgorithmNotCleanSD(val topo: Topo) {
  abstract val name: String
  lateinit var logWriter: BufferedWriter
  var settings: String = "Simple"
  var countSuccessPair=0
  var countFailedPair=0
  val SuccPairs:MutableList<Quadruple<Node,Node,Int,Int,Int,Int,Int>> = mutableListOf()
  val FailPairs:MutableList<Quadruple<Node,Node,Int,Int,Int,Int,Int>> = mutableListOf()
  var countSumPair=0

  val SumPairfile = File("dist/SumPair.txt")
  val Satisffile = File("dist/Satisf.txt")
  val SuccRatefile = File("dist/SuccRate.txt")

  val sumPairs: MutableList<Int> = mutableListOf()
  val satisfications:  MutableList<Double> = mutableListOf()
  val successtates:  MutableList<Double> = mutableListOf()

  val MaxListLength=15

  val majorPaths = mutableListOf<PickedPathNotClearSD>()
  val recoveryPaths = HashMap<PickedPathNotClearSD, LinkedList<PickedPathNotClearSD>>()


  // 任黎
  // Pair联合两个对象。
  // Triple联合三个对象。
  // Quadruple联合多个对象。
  // 定义一个数据类来表示五个对象的组合——表示一个请求
  data class Quadruple<A, B, C, D,E,F,G>(val src: A, val dst: B, var demand: C, var accepttime: D, val priority:E,
                                       var waitTime:F, var a_demand:G)
  val srcDstPairs:MutableList<Quadruple<Node,Node,Int,Int,Int,Int,Int>> = mutableListOf()
  var SlesrcDstPairs:List<Triple<Node,Node,Int>> = mutableListOf()
  //var SlesrcDstPairs:List<Pair<Node,Node>> = mutableListOf()


  fun work(pairs: List<Quadruple<Node,Node,Int,Int,Int,Int,Int>>): Pair<Int, Int> {
    require({ topo.isClean() })

    // 此处不清除SD对的信息，因为某些请求在一个时隙内不被满足。
    //srcDstPairs.clear()

    // 判断当前优先级队列长度
    // 若大于等于最大长度，直接不加入本次的请求，将本次请求直接列入失败
    // 若小于，但不能全部加入，则选择性加入，按照优先级Qc->Qp
    if (srcDstPairs.size>=MaxListLength){
      // 太拥挤，筛选请求，进行避免.
      println("队列已满")
      FailPairs.addAll(pairs)
      countFailedPair=countFailedPair+pairs.size
    } else {
      if (srcDstPairs.size + pairs.size > MaxListLength) {
        // 有空位,但是会超出,选择优先级高的,加入队列
        println("队列有部分空位")
        val CanEnterNum=MaxListLength - srcDstPairs.size
        val CanEnterPairs: List<Quadruple<Node, Node, Int, Int, Int, Int, Int>> = getTopPriorities(pairs as MutableList<Quadruple<Node, Node, Int, Int, Int, Int, Int>>, CanEnterNum)
        srcDstPairs.addAll(CanEnterPairs.take(CanEnterNum))

        val remainingPairs = CanEnterPairs.drop(CanEnterNum)
        FailPairs.addAll(remainingPairs)
        countFailedPair=countFailedPair+remainingPairs.size
      } else {
        // 有足够空位
        println("队列有足够空位")
        srcDstPairs.addAll(pairs)
      }
    }
    countSumPair=countSumPair+pairs.size

    //写入文件
    val pair=SlesrcDstPairs.map { "${it.first.id}⟷${it.second.id}" }
    logWriter.appendln(pair.joinToString())

    // 打印每个请求
    println() // 输出一个空行
    srcDstPairs.forEach { quadruple ->
      println(quadruple)
    }

    // 取出优先级较高的请求进行处理
    val topsrcDstPairs = getTopPriorities(srcDstPairs,-1)
/*    println("Top srcDstPairs: $topsrcDstPairs")*/

    //取出队列前五个请求的S-D对信息：源节点、终节点
    SlesrcDstPairs= topsrcDstPairs.map { quadruple ->
      Triple(quadruple.src, quadruple.dst,quadruple.demand)
    }

    //将前五个请求的SD对信息打印出来
    SlesrcDstPairs.forEach{pair: Triple<Node, Node,Int> ->
      println(pair)
    }
    //对Qp中的所有请求找到路径，并pickAndAssignPath_rl
    P2()

    tryEntanglement()

    P4()
//    元素为Triple(srcNode, dstNode, 已建立的纠缠路径list)的List
    val established = SlesrcDstPairs.map { (n1, n2) -> n1 to n2 to topo.getEstablishedEntanglements(n1, n2) }

    // 打印纠缠路径
      established.forEach {
        it.second.forEach {
          println("Established path: ${it}")
        }
      }

    println("""[$settings] Established: ${established.map { "${it.first.first.id}⟷${it.first.second.id} × ${it.second.size}" }} - $name""".trimIndent())

    //一轮时隙后的全局请求，即Qc中的请求srcDstPairs
    If_TimeOut_or_Finished(srcDstPairs).forEach { quadruple ->
      println(quadruple)
    }

    println("成功请求数：$countSuccessPair")
    println("失败请求数：$countFailedPair")
    println("总的请求数:$countSumPair")

    val successRate = (countSuccessPair.toDouble() / countSumPair) * 100
    println("成功率：%.2f%%".format(successRate))

    // 记录SumPairs
    sumPairs.add(countSumPair)
    //记录successPairs
    successtates.add(successRate)
    //满意度评估
    // 成功
    var SuccsatisfactionList: MutableList<Double> = mutableListOf()
    SuccsatisfactionList=cal_Satis_succ(SuccPairs)
/*    // 打印输出每个已完成请求的满意度
    SuccsatisfactionList.forEach {satisfication->
      println("用户满意度：$satisfication")
    }*/

    //失败
    var FailsatisfactionList: MutableList<Double> = mutableListOf()
    FailsatisfactionList=cal_Satis_fail(FailPairs)
/*    // 打印输出每个失败请求的满意度
    FailsatisfactionList.forEach {satisfication->
      println("用户满意度：$satisfication")
    }*/

    // 非平均，直接用一个值
    val weightSatis=SuccsatisfactionList.sum()+FailsatisfactionList.sum()
    val weightSatisFormatted = String.format("%.2f", weightSatis).toDouble()
    println("网络满意度：$weightSatisFormatted")
    //记录满意度
    satisfications.add(weightSatisFormatted)
    //写入文件
    //writeInFile(sumPairs,satisfications,successtates)

    topo.clearEntanglements()
    return established.count { it.second.isNotEmpty() } to established.sumBy { it.second.size }
  }





  fun adjustWidth(pick: PickedPathNotClearSD, newW: Int) {

    if (newW <= 0) return

    val path = pick.third
    val edges = path.edges()

    // 1. 清除之前所有 assigned 链路
    edges.forEach { (n1, n2) ->
      n1.links
        .filter { it.contains(n2) && it.assigned }
        .forEach { link ->
          link.assigned = false
          link.entangled = false
          link.utilized = false
//            link.swapper = null
        }
    }

    // 2. 按 newW 重新分配链路
    for ((n1, n2) in edges) {
      val available = n1.links
        .filter { !it.assigned && it.contains(n2) }
        .sortedBy { it.id }

      val toAssign = available.take(newW)
      toAssign.forEach { link ->
        link.assignQubits()
        link.tryEntanglement()
      }
    }

    // 3. 创建新的 pick（因为 Triple 的字段是 val）
    val newPick = PickedPathNotClearSD(
      pick.first,     // EXT 不变
      newW,           // 宽度替换为 RL 选的 newW
      pick.third      // 路径不变
    )

    // 4. 替换 majorPaths 里的原 pick
    val idx = majorPaths.indexOf(pick)
    if (idx >= 0) {
      majorPaths[idx] = newPick
    }

    // 5. 替换 recoveryPaths 的 key
    val rec = recoveryPaths.remove(pick) ?: LinkedList()
    recoveryPaths[newPick] = rec
  }


  //将结果写入文件
  fun writeInFile(sumPairs: MutableList<Int>, satisfications: MutableList<Double>, successtates: MutableList<Double>) {
    try {
      // 如果文件不存在，则创建新文件
      if (!Satisffile.exists()) {
        Satisffile.createNewFile()
      }
      // 将内容写入文件
      val writer = FileWriter(Satisffile,true)
      for (sats in satisfications) {
        writer.write("$sats\n")
      }
      writer.close()
    } catch (e: Exception) {
      println("创建文件时出现错误：${e.message}")
    }

    try {
      // 如果文件不存在，则创建新文件
      if (!SumPairfile.exists()) {
        SumPairfile.createNewFile()
      }
      // 将内容写入文件
      val writer = FileWriter(SumPairfile,true)
      for (sum in sumPairs) {
        writer.write("$sum\n")
      }
      writer.close()
    } catch (e: Exception) {
      println("创建文件时出现错误：${e.message}")
    }

    try {
      // 如果文件不存在，则创建新文件
      if (!SuccRatefile.exists()) {
        SuccRatefile.createNewFile()
      }
      // 将内容写入文件
      val writer = FileWriter(SuccRatefile,true)
      for (succ in successtates) {
        writer.write("$succ\n")
      }
      writer.close()
    } catch (e: Exception) {
      println("创建文件时出现错误：${e.message}")
    }
  }

  // 建立纠缠
  fun tryEntanglement() {
    topo.links.forEach { it.tryEntanglement() }
  }

  // 优先级计算
  fun calculatePriority(priority: Int, demand: Int, acceptTime: Int): Int {
    val total = priority + demand + acceptTime
    val normalizedPriority = total.toFloat() / 3
    return ceil(normalizedPriority).toInt().coerceIn(1, 5)
  }

  // 取优先级靠前的请求进行处理
  fun getTopPriorities(srcDstPairs: MutableList<Quadruple<Node, Node, Int, Int, Int,Int,Int>>,switch:Int): List<Quadruple<Node, Node, Int, Int, Int,Int,Int>> {
    var n=0
    if (switch==-1){
      n = when {
        srcDstPairs.size <= 10 -> 8
        else -> 10
      }
    }else
    {
     n=srcDstPairs.size
    }

    return srcDstPairs.map { quadruple ->
      val demand = quadruple.demand
      val acceptTime = quadruple.accepttime
      val priority = quadruple.priority
      val priorityValue = calculatePriority(priority, demand, acceptTime)
      println("Priority of ${quadruple.src} ⟷ ${quadruple.dst}: $priorityValue")
      quadruple to priorityValue // 将 SD 对与其优先级值一起返回
    }.sortedWith(compareBy<Pair<Quadruple<Node, Node, Int, Int, Int,Int,Int>, Int>> { it.second } // 根据优先级值进行升序排序
      .thenBy { pair -> pair.first.accepttime } // 相同优先级时按照 acceptTime 进行升序排序
    ).take(n)
      .map { it.first }
  }

  // 计算用户满意度
  // 初步设想根据需求量和等待时间来进行判断:
  // 取值范围0-1
  // 0；不满意   0-1：比较满意
  // 成功的请求进行计算，未成功的直接为0
  // 直接为0，不太合适
  fun calSatisfaction(succPairs: MutableList<Quadruple<Node, Node, Int, Int,Int,Int,Int>>): MutableList<Double> {
    val SatisfactionList: MutableList<Double> = mutableListOf()
    succPairs.forEach { quadruple ->
      val satisfaction: Double
      val waitTimeWeight = 0.6
      val demandWeight = 0.4
      val normalizedWaitTime = 1.0 - ((quadruple.waitTime.toDouble()-1) / 5)
      val normalizedDemand = quadruple.a_demand.toDouble()/10
      // 计算满意度
      satisfaction = waitTimeWeight * normalizedWaitTime + demandWeight * normalizedDemand
      SatisfactionList.add(String.format("%.2f", satisfaction).toDouble())
    }
    return SatisfactionList
  }

  // 改为计算网速——更符合实际情况
  fun cal_Satis_succ(succPairs: MutableList<Quadruple<Node, Node, Int, Int,Int,Int,Int>>): MutableList<Double> {
    val SatisfactionList: MutableList<Double> = mutableListOf()
    succPairs.forEach { quadruple ->
      val satisfaction: Double = quadruple.priority.toDouble() * (quadruple.a_demand.toDouble() / quadruple.waitTime.toDouble())
      SatisfactionList.add(satisfaction)
    }
    return SatisfactionList
  }

  // 计算失败的——失败的分情况，直接没有等\等了但是仍没有完成 （没等=0，等了但是没完成=负数，扣分）
  fun cal_Satis_fail(failPairs: MutableList<Quadruple<Node, Node, Int, Int,Int,Int,Int>>): MutableList<Double> {
    val SatisfactionList: MutableList<Double> = mutableListOf()
    failPairs.forEach { quadruple ->
      val satisfaction: Double
      // 计算满意度
      if (quadruple.waitTime == 0){
        // waitTime=0，说明直接被拒绝了
        satisfaction= 0.0
      }else {
        // 不等于0说明，等待了一段时间
        // 等待越长越不满意
        satisfaction = (-quadruple.waitTime).toDouble()
      }
      SatisfactionList.add(String.format("%.2f", satisfaction).toDouble())
    }
    return SatisfactionList
  }
  //判断请求是否超时、是否完成
  abstract fun If_TimeOut_or_Finished (srcDstPairs: MutableList<Quadruple<Node, Node, Int, Int, Int,Int,Int>>): MutableList<Quadruple<Node, Node, Int, Int, Int,Int,Int>>

  abstract fun prepare()
  
  abstract fun P2()
  
  abstract fun P4()
}

typealias PickedPathNotClearSD = Triple<Double, Int, Path>