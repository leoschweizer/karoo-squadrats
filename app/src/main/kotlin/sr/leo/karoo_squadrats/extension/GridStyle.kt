package sr.leo.karoo_squadrats.extension

enum class GridStyle(
    val contourColor: Int,
    val gridColor: Int,
    val shadeColor: Int? = null,
    val shadeExtraWidth: Int = 0,
) {
    SQUADRAT(
        contourColor = 0x80663399.toInt(),
        gridColor = 0x40663399,
        shadeColor = 0x33663399,
        shadeExtraWidth = 6,
    ),
    SQUADRATINHO(
        contourColor = 0xFFE67E22.toInt(),
        gridColor = 0x44E67E22,
    ) {
        override fun contourWidth(baseWidth: Int) = (gridWidth(baseWidth) + 1).coerceAtMost(baseWidth)
        override fun gridWidth(baseWidth: Int) = (baseWidth - 1).coerceAtLeast(1)
    };

    open fun contourWidth(baseWidth: Int): Int = baseWidth
    open fun gridWidth(baseWidth: Int): Int = baseWidth
}
