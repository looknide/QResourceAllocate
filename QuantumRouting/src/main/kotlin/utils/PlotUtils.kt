package utils

import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartUtils
import org.jfree.data.category.DefaultCategoryDataset
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import java.io.File

object PlotUtils {

    /** 画折线图：x 为 Int，y 为 Double */
    fun plotLine(
        title: String,
        xLabel: String,
        yLabel: String,
        x: List<Int>,
        y: List<Double>,
        outputFile: String
    ) {
        val series = XYSeries(title)
        for (i in x.indices) {
            series.add(x[i], y[i])
        }

        val dataset = XYSeriesCollection(series)

        val chart = ChartFactory.createXYLineChart(
            title, xLabel, yLabel,
            dataset
        )

        val file = File(outputFile)
        file.parentFile?.mkdirs()
        ChartUtils.saveChartAsPNG(file, chart, 900, 500)
        println("✔ Saved line chart: $outputFile")
    }


    /** 画柱状图（Histogram） */
    fun plotBar(
        title: String,
        xLabel: String,
        yLabel: String,
        categories: List<Int>,
        values: List<Int>,
        outputFile: String
    ) {
        val dataset = DefaultCategoryDataset()
        for (i in categories.indices) {
            dataset.addValue(values[i], "Count", categories[i])
        }

        val chart = ChartFactory.createBarChart(
            title, xLabel, yLabel,
            dataset
        )

        val file = File(outputFile)
        file.parentFile?.mkdirs()
        ChartUtils.saveChartAsPNG(file, chart, 900, 500)
        println("✔ Saved bar chart: $outputFile")
    }
}
