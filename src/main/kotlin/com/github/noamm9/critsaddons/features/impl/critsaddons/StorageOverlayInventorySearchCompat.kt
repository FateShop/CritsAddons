package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.ui.clickgui.components.Setting
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.items.ItemUtils.lore
import net.minecraft.world.item.ItemStack
import java.awt.Color
import java.lang.reflect.Field
import java.lang.reflect.Method

object StorageOverlayInventorySearchCompat {
    private const val INVENTORY_SEARCH_CLASS = "com.github.noamm9.features.impl.misc.InventorySearch"

    private val searchClass: Class<*>? by lazy {
        runCatching { Class.forName(INVENTORY_SEARCH_CLASS) }.getOrNull()
    }

    private val instance: Any? by lazy {
        runCatching { searchClass?.getField("INSTANCE")?.get(null) }.getOrNull()
    }

    private val searchQueryField: Field? by lazy {
        runCatching {
            searchClass?.getDeclaredField("searchQuery")?.apply { isAccessible = true }
        }.getOrNull()
    }

    private val ignoreCapsGetter: Method? by lazy {
        runCatching {
            searchClass?.getDeclaredMethod("getIgnoreCaps")?.apply { isAccessible = true }
        }.getOrNull()
    }

    private val searchLoreGetter: Method? by lazy {
        runCatching {
            searchClass?.getDeclaredMethod("getSearchLore")?.apply { isAccessible = true }
        }.getOrNull()
    }

    private val highlightColorGetter: Method? by lazy {
        runCatching {
            searchClass?.getDeclaredMethod("getHighlightColor")?.apply { isAccessible = true }
        }.getOrNull()
    }

    fun matches(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val query = runCatching { searchQueryField?.get(null) as? String }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false
        val ignoreCaps = readToggle(ignoreCapsGetter)
        val searchLore = readToggle(searchLoreGetter)

        val nameMatches = stack.hoverName.unformattedText.contains(query, ignoreCaps)
        val loreMatches = searchLore && stack.lore.any { it.removeFormatting().contains(query, ignoreCaps) }
        return nameMatches || loreMatches
    }

    fun color(): Color {
        return readSettingValue(highlightColorGetter) as? Color ?: Color(255, 255, 0, 90)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSettingValue(getter: Method?): Any? {
        val owner = instance ?: return null
        val setting = runCatching { getter?.invoke(owner) }.getOrNull() as? Setting<*> ?: return null
        return setting.value
    }

    private fun readToggle(getter: Method?): Boolean {
        return readSettingValue(getter) as? Boolean ?: false
    }
}
