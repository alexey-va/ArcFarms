package ru.ruscrafting.farms.paper.worksite

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style

/** Conservative scoreboard wrapping that keeps the effective Adventure style of every text fragment. */
internal object SidebarLineWrapper {
    fun wrap(rows: List<Component>, maxCharacters: Int): List<Component> {
        require(maxCharacters > 0) { "Sidebar wrap width must be positive" }
        return rows.flatMap { wrap(it, maxCharacters) }
    }

    private fun wrap(row: Component, maxCharacters: Int): List<Component> {
        val fragments = mutableListOf<Fragment>()
        flatten(row, Style.empty(), fragments)
        if (fragments.sumOf { it.text.length } <= maxCharacters && fragments.none { '\n' in it.text }) return listOf(row)

        val lines = mutableListOf<Component>()
        var line = Component.empty()
        var width = 0

        fun flush() {
            lines += line
            line = Component.empty()
            width = 0
        }

        fragments.forEach { fragment ->
            split(fragment.text).forEach { raw ->
                if (raw == "\n") {
                    flush()
                    return@forEach
                }
                var token = raw
                if (token.isBlank() && width == 0) return@forEach
                while (token.isNotEmpty()) {
                    if (width > 0 && width + token.length > maxCharacters) flush()
                    if (token.isBlank() && width == 0) break
                    val room = maxCharacters - width
                    val part = token.take(room)
                    line = line.append(Component.text(part).style(fragment.style))
                    width += part.length
                    token = token.drop(part.length)
                    if (token.isNotEmpty()) flush()
                }
            }
        }
        if (width > 0 || lines.isEmpty()) flush()
        return lines
    }

    private fun flatten(component: Component, inherited: Style, output: MutableList<Fragment>) {
        val effective = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
        if (component is TextComponent && component.content().isNotEmpty()) {
            output += Fragment(component.content(), effective)
        }
        component.children().forEach { flatten(it, effective, output) }
    }

    private fun split(value: String): List<String> = buildList {
        val token = StringBuilder()
        value.forEach { character ->
            when {
                character == '\n' -> {
                    if (token.isNotEmpty()) add(token.toString()).also { token.clear() }
                    add("\n")
                }
                character.isWhitespace() -> {
                    if (token.isNotEmpty()) add(token.toString()).also { token.clear() }
                    token.append(character)
                }
                token.isNotEmpty() && token.last().isWhitespace() -> {
                    add(token.toString())
                    token.clear()
                    token.append(character)
                }
                else -> token.append(character)
            }
        }
        if (token.isNotEmpty()) add(token.toString())
    }

    private data class Fragment(val text: String, val style: Style)
}
