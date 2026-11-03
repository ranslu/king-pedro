package com.slusarchuk.kingpedro.characters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Roster grid + seat picker, per the design spec:
 *  - Tap a portrait to see bio + "Hear me" voice sample
 *  - Tap a seat, then tap a character to assign (tap-to-assign; no drag-and-drop,
 *    which tested poorly on small screens per the plan)
 *  - Random / Fill-empty-seats convenience buttons
 *  - Chattiness slider (Off / Occasional / Chatty), mirrors Hoyle's speech meter
 *
 * Wood/linen/vyshyvanka theming should be applied via MaterialTheme colors —
 * not hardcoded here, so it stays consistent with the rest of the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterSelectScreen(
    roster: List<CharacterProfile>,
    barkLibrary: BarkLibrary,
    voiceEngine: CharacterVoiceEngine,
    onConfirmTable: (TableAssignment) -> Unit
) {
    var selectedSeat by remember { mutableStateOf<Seat>>(null) }
    var previewCharacter by remember { mutableStateOf<CharacterProfile?>(null) }
    var chattiness by remember { mutableStateOf(1) } // 0=Off, 1=Occasional, 2=Chatty

    var west by remember { mutableStateOf<CharacterProfile?>(null) }
    var north by remember { mutableStateOf<CharacterProfile?>(null) }
    var east by remember { mutableStateOf<CharacterProfile?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        Text("Seat the Table", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Tap a seat, then tap a character to assign them there.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(12.dp))

        // --- Seat row: partner (north) above, opponents (west/east) flanking ---
        SeatSlot("Partner (North)", north, selectedSeat == Seat.NORTH) { selectedSeat = Seat.NORTH }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SeatSlot("Opponent (West)", west, selectedSeat == Seat.WEST, Modifier.weight(1f)) { selectedSeat = Seat.WEST }
            Spacer(Modifier.width(8.dp))
            SeatSlot("Opponent (East)", east, selectedSeat == Seat.EAST, Modifier.weight(1f)) { selectedSeat = Seat.EAST }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { 
                val available = roster.shuffled()
                west = available.getOrNull(0)
                north = available.getOrNull(1)
                east = available.getOrNull(2)
            }) { Text("Random Table") }

            OutlinedButton(onClick = { 
                val unused = roster.filterNot { it == west || it == north || it == east }.shuffled()
                var pool = unused
                if (west == null) { west = pool.firstOrNull(); pool = pool.drop(1) }
                if (north == null) { north = pool.firstOrNull(); pool = pool.drop(1) }
                if (east == null) { east = pool.firstOrNull() }
            }) { Text("Fill Empty Seats") }
        }

        Spacer(Modifier.height(16.dp))
        Text("Chattiness: ${listOf("Off", "Occasional", "Chatty")[chattiness]}")
        Slider(
            value = chattiness.toFloat(),
            onValueChange = { chattiness = it.toInt() },
            valueRange = 0f..2f,
            steps = 1
        )

        Spacer(Modifier.height(16.dp))
        Text("Roster", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(roster) { character ->
                CharacterCard(
                    character = character,
                    onSelect = {
                        // Assign to currently selected seat, if any.
                        when (selectedSeat) {
                            Seat.WEST -> west = character
                            Seat.NORTH -> north = character
                            Seat.EAST -> east = character
                            else -> {} 
                        }
                        previewCharacter = character
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                voiceEngine.enabled = chattiness > 0
                onConfirmTable(TableAssignment(south = null, west = west, north = north, east = east))
            },
            enabled = west != null && north != null && east != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Deal the Cards")
        }
    }

    // --- Bio / "Hear me" preview panel ---
    previewCharacter?.let { character ->
        AlertDialog(
            onDismissRequest = { previewCharacter = null },
            title = { Text("${character.name} — ${character.attitudeTag}") },
            text = {
                Column {
                    Text(character.bioShort)
                    Spacer(Modifier.height(8.dp))
                    Text("Skill: " + "★".repeat(character.skillStars) + "☆".repeat(5 - character.skillStars))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val line = barkLibrary.nextBark(character, BarkTrigger.GAME_START_INTRO, force = true)
                    line?.let { voiceEngine.speak(character, it, interrupt = true) }
                }) { Text("Hear me") }
            },
            dismissButton = {
                TextButton(onClick = { previewCharacter = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SeatSlot(
    label: String,
    occupant: CharacterProfile?,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .padding(4.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                occupant?.name ?: "Tap to assign",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            occupant?.let { Text(it.attitudeTag, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CharacterCard(character: CharacterProfile, onSelect: () -> Unit) {
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(character.name, fontWeight = FontWeight.Bold)
            Text(character.attitudeTag, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("★".repeat(character.skillStars) + "☆".repeat(5 - character.skillStars))
            if (character.isTribute) {
                Spacer(Modifier.height(4.dp))
                Text("In memoriam", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
