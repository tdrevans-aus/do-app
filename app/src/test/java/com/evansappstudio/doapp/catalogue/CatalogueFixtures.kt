package com.evansappstudio.doapp.catalogue

import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatUnit
import java.io.File

/** The bundled asset, read from the source tree (Gradle runs tests from app/). */
internal fun bundledText(): String {
    val relative = "src/main/assets/catalogue/sysd.json"
    val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
        ?: error("missing $relative from ${File(".").absolutePath}")
    return file.readText()
}

internal fun bundled(): Catalogue = CatalogueParser.parse(bundledText()).catalogue

internal fun testItem(
    id: String,
    category: String = "home",
    priority: Int = 50,
    from: RepeatFrom = RepeatFrom.COMPLETION,
    every: Int = 1,
    unit: RepeatUnit = RepeatUnit.MONTH,
    month: Int? = null,
    minute: Int = 9 * 60,
    needsDate: Boolean = false,
    related: List<String> = emptyList(),
    keywords: List<String> = emptyList(),
    regions: List<String> = listOf("*"),
): LocalItem {
    val item = CatalogueItem(
        id = id,
        version = 1,
        category = category,
        title = "Title $id",
        why = "Why $id",
        interval = CatalogueInterval(every, unit),
        repeatFrom = from,
        suggestedMonth = month,
        suggestedMinute = minute,
        alert = CatalogueAlert.NORMAL,
        regions = regions,
        needsDate = needsDate,
        priority = priority,
        related = related,
        keywords = keywords,
    )
    return CatalogueFilter.localise(item, Region.OUTSIDE)
}
