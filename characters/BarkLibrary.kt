package com.slusarchuk.kingpedro.characters

import android.content.Context
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Loads the character roster from assets/characters.json and serves bark lines
 * using a no-repeat "bag" shuffle per character+trigger, so lines don't repeat
 * until the whole pool has been used once. Enforces per-character cooldown and
 * a table-wide "someone is already talking" lock.
 */
class BarkLibrary(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val roster: List<CharacterProfile>

    private val bags = mutableMapOf<String, MutableList<Int>>()
    private val lastSpokeAt = mutableMapOf<String, Long>()
    private val perCharacterCooldownMs = 4_000L
    private var tableIsTalking = false

    init {
        val text = context.assets.open("characters.json").bufferedReader().use { it.readText() }
        roster = json.decodeFromString(CharacterRoster.serializer(), text).characters
    }

    fun allCharacters(): List<CharacterProfile> = roster
    fun findCharacter(id: String): CharacterProfile? = roster.firstOrNull { it.id == id }

    fun nextBark(character: CharacterProfile, trigger: BarkTrigger, force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        val lastSpoke = lastSpokeAt[character.id] ?: 0L

        if (!force && now - lastSpoke < perCharacterCooldownMs) return null
        if (!force && tableIsTalking) return null

        val lines = character.barks[trigger.key] ?: return null
        if (lines.isEmpty()) return null

        val bagKey = "${character.id}:${trigger.key}"
        val bag = bags.getOrPut(bagKey) { shuffledIndices(lines.size) }
        if (bag.isEmpty()) bag.addAll(shuffledIndices(lines.size))

        val index = bag.removeAt(bag.lastIndex)
        lastSpokeAt[character.id] = now
        return lines[index]
    }

    fun markTableTalking(talking: Boolean) { tableIsTalking = talking }

    private fun shuffledIndices(size: Int): MutableList<Int> =
        (0 until size).shuffled(Random(System.nanoTime())).toMutableList()
}
