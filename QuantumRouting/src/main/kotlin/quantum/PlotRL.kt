package quantum.rl

import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import kotlin.math.max

class PlotRL(
    val rewardLog: List<Double>,
    val actionLog: List<Int>,  // 保持为Int（离散动作索引）
    val successLog: List<Int>,
    val failLog: List<Int>
) {

    fun save() {
        val width = 1600
        val height = 1200
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.graphics as Graphics2D

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)

        // 安全绘制各个图表
        try {
            drawRewardCurve(g, 0, 0, width/2, height/2)
            drawActionDistribution(g, width/2, 0, width/2, height/2)
            drawSuccessFail(g, 0, height/2, width, height/2)

            // 只有在有足够数据时才绘制ActionOverTime
            if (actionLog.size > 200) {
                drawActionOverTime(g, width/2, height/4, width/2, height/4)
            }
        } catch (e: Exception) {
            println("绘图错误: ${e.message}")
            g.color = Color.RED
            g.font = Font("Arial", Font.BOLD, 20)
            g.drawString("绘图错误: ${e.message}", 100, 100)
        }

        ImageIO.write(image, "png", File("ppo_training_results.png"))
        println("Plot saved as ppo_training_results.png")
    }

    private fun drawRewardCurve(g: Graphics2D, x: Int, y: Int, width: Int, height: Int) {
        val padding = 50
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        val chartX = x + padding
        val chartY = y + padding

        // 绘制标题
        g.color = Color.BLACK
        g.font = Font("Arial", Font.BOLD, 16)
        g.drawString("PPO Reward Curve", chartX, chartY - 20)
        g.drawRect(chartX, chartY, chartWidth, chartHeight)

        if (rewardLog.isNotEmpty()) {
            // 计算滑动平均
            val window = minOf(50, rewardLog.size / 10)
            val smoothed = mutableListOf<Double>()

            for (i in rewardLog.indices) {
                val start = max(0, i - window + 1)
                val end = i + 1
                if (start < end) {  // 安全检查
                    val avg = rewardLog.subList(start, end).average()
                    smoothed.add(avg)
                } else {
                    smoothed.add(rewardLog[i])
                }
            }

            if (smoothed.isNotEmpty()) {
                val minReward = smoothed.minOrNull() ?: 0.0
                val maxReward = smoothed.maxOrNull() ?: 1.0
                val range = maxReward - minReward

                if (range > 0) {
                    g.color = Color.BLUE
                    for (i in 1 until smoothed.size) {
                        val x1 = chartX + (i-1) * chartWidth / (smoothed.size - 1)
                        val y1 = chartY + chartHeight - ((smoothed[i-1] - minReward) / range * chartHeight).toInt()
                        val x2 = chartX + i * chartWidth / (smoothed.size - 1)
                        val y2 = chartY + chartHeight - ((smoothed[i] - minReward) / range * chartHeight).toInt()

                        g.drawLine(x1, y1, x2, y2)
                    }

                    // 显示统计
                    g.color = Color.BLACK
                    g.font = Font("Arial", Font.PLAIN, 12)
                    g.drawString("Avg Reward: %.3f".format(rewardLog.average()), chartX + 10, chartY + 20)
                }
            }
        }
    }

    private fun drawActionDistribution(g: Graphics2D, x: Int, y: Int, width: Int, height: Int) {
        val padding = 50
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        val chartX = x + padding
        val chartY = y + padding

        g.color = Color.BLACK
        g.font = Font("Arial", Font.BOLD, 16)
        g.drawString("Action Distribution", chartX, chartY - 20)
        g.drawRect(chartX, chartY, chartWidth, chartHeight)

        if (actionLog.isNotEmpty()) {
            // 确定动作范围
            val minAction = actionLog.minOrNull() ?: 0
            val maxAction = actionLog.maxOrNull() ?: 19
            val actionRange = maxAction - minAction + 1

            // 创建直方图
            val bins = minOf(actionRange, 20)
            val histogram = IntArray(bins) { 0 }

            for (action in actionLog) {
                if (actionRange > 0) {
                    val bin = ((action - minAction).toDouble() / actionRange * bins).toInt().coerceIn(0, bins-1)
                    histogram[bin]++
                }
            }

            val maxCount = histogram.maxOrNull() ?: 1

            if (maxCount > 0) {
                g.color = Color(0, 150, 0)
                val barWidth = chartWidth / bins

                for (i in histogram.indices) {
                    val barHeight = (histogram[i].toDouble() / maxCount * chartHeight * 0.9).toInt()
                    val barX = chartX + i * barWidth
                    val barY = chartY + chartHeight - barHeight

                    g.fillRect(barX, barY, barWidth - 2, barHeight)
                }

                // 显示统计
                g.color = Color.BLACK
                g.font = Font("Arial", Font.PLAIN, 12)
                val avgAction = actionLog.average()
                g.drawString("Avg Action: %.2f".format(avgAction), chartX + 10, chartY + 20)

                // 显示最常见的动作
                val mostCommonIndex = histogram.indices.maxByOrNull { histogram[it] } ?: 0
                val mostCommonAction = minAction + mostCommonIndex * actionRange / bins
                g.drawString("Most Common: $mostCommonAction", chartX + 10, chartY + 40)
            }
        }
    }

    private fun drawActionOverTime(g: Graphics2D, x: Int, y: Int, width: Int, height: Int) {
        val padding = 30
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        val chartX = x + padding
        val chartY = y + padding

        g.color = Color.BLACK
        g.font = Font("Arial", Font.BOLD, 14)
        g.drawString("Action Over Time (Sampled)", chartX, chartY - 10)
        g.drawRect(chartX, chartY, chartWidth, chartHeight)

        if (actionLog.size > 100) {
            // 采样显示
            val step = max(1, actionLog.size / 200)

            // 找到动作范围
            val minAction = actionLog.minOrNull() ?: 0
            val maxAction = actionLog.maxOrNull() ?: 19
            val actionRange = maxAction - minAction + 1

            if (actionRange > 0) {
                g.color = Color(200, 100, 0)

                // 绘制原始动作（采样）
                for (i in 0 until actionLog.size - step step step) {
                    val x1 = chartX + (i * chartWidth / actionLog.size).toInt()
                    val y1 = chartY + chartHeight - ((actionLog[i] - minAction).toDouble() / actionRange * chartHeight).toInt()

                    // 只绘制点
                    g.fillOval(x1 - 1, y1 - 1, 3, 3)
                }

                // 绘制滑动平均
                val window = minOf(100, actionLog.size / 10)
                if (window > 0 && actionLog.size > window) {
                    g.color = Color.RED

                    for (i in window until actionLog.size step step) {
                        val start = max(0, i - window)
                        val end = i

                        if (start < end) {
                            val avg = actionLog.subList(start, end).average()

                            val x1 = chartX + ((i - step) * chartWidth / actionLog.size).toInt()
                            val y1 = chartY + chartHeight - ((actionLog.subList(max(0, start - step), end - step).average() - minAction) / actionRange * chartHeight).toInt()
                            val x2 = chartX + (i * chartWidth / actionLog.size).toInt()
                            val y2 = chartY + chartHeight - ((avg - minAction) / actionRange * chartHeight).toInt()

                            g.drawLine(x1, y1, x2, y2)
                        }
                    }
                }
            }
        }
    }

    private fun drawSuccessFail(g: Graphics2D, x: Int, y: Int, width: Int, height: Int) {
        val padding = 50
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        val chartX = x + padding
        val chartY = y + padding

        g.color = Color.BLACK
        g.font = Font("Arial", Font.BOLD, 16)
        g.drawString("Success vs Fail per Episode", chartX, chartY - 20)
        g.drawRect(chartX, chartY, chartWidth, chartHeight)

        if (successLog.isNotEmpty() && successLog.size == failLog.size) {
            val episodes = successLog.size
            val maxValue = max(
                successLog.maxOrNull() ?: 1,
                failLog.maxOrNull() ?: 1
            ).coerceAtLeast(1)

            if (maxValue > 0) {
                // 绘制成功柱状图
                g.color = Color(0, 180, 0)
                val barWidth = chartWidth / episodes / 2

                for (i in successLog.indices) {
                    val barHeight = (successLog[i].toDouble() / maxValue * chartHeight).toInt()
                    val barX = chartX + i * (barWidth * 2)
                    val barY = chartY + chartHeight - barHeight

                    g.fillRect(barX, barY, barWidth - 2, barHeight)
                }

                // 绘制失败柱状图
                g.color = Color(220, 0, 0)
                for (i in failLog.indices) {
                    val barHeight = (failLog[i].toDouble() / maxValue * chartHeight).toInt()
                    val barX = chartX + i * (barWidth * 2) + barWidth
                    val barY = chartY + chartHeight - barHeight

                    g.fillRect(barX, barY, barWidth - 2, barHeight)
                }

                // 绘制成功率曲线
                if (episodes > 1) {
                    g.color = Color.BLUE
                    for (i in 1 until episodes) {
                        val total1 = successLog[i-1] + failLog[i-1]
                        val total2 = successLog[i] + failLog[i]

                        val rate1 = if (total1 > 0) successLog[i-1].toDouble() / total1 else 0.0
                        val rate2 = if (total2 > 0) successLog[i].toDouble() / total2 else 0.0

                        val x1 = chartX + (i-1) * (barWidth * 2) + barWidth
                        val y1 = chartY + chartHeight - (rate1 * chartHeight).toInt()
                        val x2 = chartX + i * (barWidth * 2) + barWidth
                        val y2 = chartY + chartHeight - (rate2 * chartHeight).toInt()

                        g.drawLine(x1, y1, x2, y2)
                    }
                }

                // 显示最后成功率
                val lastTotal = successLog.last() + failLog.last()
                val lastRate = if (lastTotal > 0) successLog.last() * 100.0 / lastTotal else 0.0

                g.color = Color.BLACK
                g.font = Font("Arial", Font.BOLD, 12)
                g.drawString("Final Success Rate: %.1f%%".format(lastRate), chartX + chartWidth - 200, chartY - 5)
            }
        }
    }
}