package quantum.rl

import quantum.algorithm.PickedPathNotClearSD
import quantum.algorithm.AlgorithmNotCleanSD
import quantum.topo.Topo
import quantum.topo.Node

data class RLState(
    val topo: Topo,
    val requests: List<AlgorithmNotCleanSD.Quadruple<Node,Node,Int,Int,Int,Int,Int>>,
    val candidates: List<PickedPathNotClearSD>
)
