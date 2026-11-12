package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.ADMINISTRATOR_ID
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.BOARD_HEIGHT
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.BOARD_WIDTH
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.PLAYER_COUNT
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.TASK_SIZE
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Point
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Rejection
import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ScavengerHuntGameTest {
    private val origin = Point(255, 255)

    @Test
    fun `administrative round setup preserves the legacy location and task sequence`() {
        val initial = ScavengerHuntGame(turnsRemaining = 40)
        val result = initial.startRound(100)
        val game = result.game
        val round = requireNotNull(game.round)
        val reference = Random(42)
        val expectedLocations = List(6) {
            Point((reference.nextDouble() * 712).toInt(), (reference.nextDouble() * 602).toInt())
        } + origin
        assertNull(result.rejection)
        assertEquals(40, game.turnsRemaining)
        assertEquals(100L, round.firstInstruction)
        assertEquals(expectedLocations, round.locations)
        assertEquals(22, round.players.size)
        round.players.forEach { player ->
            assertEquals(origin, player.position)
            assertEquals(20, player.energy)
            assertEquals(0, player.color)
            assertEquals(0, player.visitedMask)
            assertNull(player.finishAfterInstructions)
            assertEquals(List(20) { (reference.nextDouble() * Int.MAX_VALUE).toInt() }, player.task)
        }

        val selected = game.authenticate(1, 21).game
        val completed = selected.completeTask(round.players[21].task.sorted()).game
        assertEquals(List(20) { (reference.nextDouble() * Int.MAX_VALUE).toInt() }, completed.round!!.players[21].task)
        val restarted = completed.authenticate(2, ADMINISTRATOR_ID).game.startRound(200).game
        assertEquals(round.copy(firstInstruction = 200), restarted.round)
        assertEquals(0, restarted.authenticationSequence)
        assertEquals(40, restarted.turnsRemaining)
        assertEquals(result, initial.startRound(100))
        assertNull(initial.round)
    }

    @Test
    fun `authentication advances for each attempt and keeps the last valid identity`() {
        val initial = ScavengerHuntGame()
        val rejected = initial.authenticate(999, 0)
        assertEquals(Rejection.AUTHENTICATION, rejected.rejection)
        assertEquals(1, rejected.game.authenticationSequence)
        assertEquals(ADMINISTRATOR_ID, rejected.game.playerId)
        val negative = rejected.game.authenticate(2, -1)
        assertEquals(Rejection.UNKNOWN_PLAYER, negative.rejection)
        val overflowId = negative.game.authenticate(3, PLAYER_COUNT)
        assertEquals(Rejection.UNKNOWN_PLAYER, overflowId.rejection)
        assertEquals(ADMINISTRATOR_ID, overflowId.game.playerId)
        val authenticated = overflowId.game.authenticate(4, 0)
        assertNull(authenticated.rejection)
        assertEquals(0, authenticated.game.playerId)
        val wrapped = authenticated.game.copy(authenticationSequence = Int.MAX_VALUE).authenticate(Int.MIN_VALUE, ADMINISTRATOR_ID)
        assertNull(wrapped.rejection)
        assertEquals(Int.MIN_VALUE, wrapped.game.authenticationSequence)
        assertEquals(ADMINISTRATOR_ID, wrapped.game.playerId)
    }

    @Test
    fun `role checks protect game setup and player operations`() {
        val initial = ScavengerHuntGame()
        assertEquals(Rejection.PLAYER_REQUIRED, initial.move(origin, 2).rejection)
        val selected = initial.authenticate(1, 0).game
        val rejected = selected.startRound(2)
        assertEquals(Rejection.ADMINISTRATOR_REQUIRED, rejected.rejection)
        assertSame(selected, rejected.game)
        assertEquals(Rejection.ROUND_NOT_STARTED, selected.move(origin, 3).rejection)
        assertEquals(Rejection.ROUND_NOT_STARTED, selected.changeColor(0x123456).rejection)
    }

    @Test
    fun `moves enforce board limits distance and energy without changing other players`() {
        val game = playerGame()
        for (point in listOf(Point(-1, 0), Point(BOARD_WIDTH + 1, 0), Point(0, -1), Point(0, BOARD_HEIGHT + 1))) {
            val outside = game.move(point, 1)
            assertEquals(Rejection.OUTSIDE_BOARD, outside.rejection)
            assertSame(game, outside.game)
        }
        val tooFar = game.move(Point(257, 257), 1)
        assertEquals(Rejection.MOVE_TOO_FAR, tooFar.rejection)
        val move = game.move(Point(257, 256), 2)
        assertNull(move.rejection)
        assertEquals(Point(257, 256), move.game.round!!.players[0].position)
        assertEquals(19, move.game.round.players[0].energy)
        assertSame(game.round!!.players[1], move.game.round.players[1])
        assertEquals(origin, game.round.players[0].position)

        var exhausted = move.game
        repeat(19) { exhausted = exhausted.move(Point(257, 256), 3L + it).game }
        assertEquals(0, exhausted.round!!.players[0].energy)
        val rejected = exhausted.move(origin, 23)
        assertEquals(Rejection.NO_ENERGY, rejected.rejection)
        assertSame(exhausted, rejected.game)
    }

    @Test
    fun `scoreboard tracks each location and records finish once in simulator instructions`() {
        val game = playerGame(firstInstruction = 100)
        val locations = (1..6).map { Point(origin.x + it, origin.y) } + origin
        var playing = game.copy(round = game.round!!.copy(locations = locations))
        for ((index, point) in locations.dropLast(1).withIndex()) {
            playing = playing.move(point, 101L + index).game
            assertEquals((1 shl (index + 1)) - 1, playing.round!!.players[0].visitedMask)
            assertNull(playing.round.players[0].finishAfterInstructions)
        }
        playing = playing.move(Point(259, 255), 107).game
        playing = playing.move(Point(257, 255), 108).game
        val finish = playing.move(origin, 120).game
        assertEquals(0b1111111, finish.round!!.players[0].visitedMask)
        assertEquals(20L, finish.round.players[0].finishAfterInstructions)
        val later = finish.move(origin, 121).game
        assertEquals(20L, later.round!!.players[0].finishAfterInstructions)
        assertEquals(0, later.round.players[1].visitedMask)
    }

    @Test
    fun `sorting checks signed order and rewards only the current player with a fresh task`() {
        val initial = playerGame()
        val moved = initial.move(origin, 1).game
        val painted = moved.changeColor(0xfedcba).game
        val round = painted.round!!
        val player = round.players[0]
        assertEquals(0xfedcba, player.color)
        assertEquals(0, round.players[1].color)
        assertEquals(19, player.energy)
        val rejected = painted.completeTask(player.task.dropLast(1))
        assertEquals(Rejection.INVALID_TASK, rejected.rejection)
        assertSame(painted, rejected.game)
        val wrongOrder = List(TASK_SIZE) { -it }
        assertEquals(Rejection.INVALID_TASK, painted.completeTask(wrongOrder).rejection)
        val signedSorted = listOf(Int.MIN_VALUE) + List(TASK_SIZE - 2) { -1 } + Int.MAX_VALUE
        val completed = painted.completeTask(signedSorted)
        assertNull(completed.rejection)
        val next = completed.game.round!!
        assertEquals(20, next.players[0].energy)
        assertNotEquals(player.task, next.players[0].task)
        assertNotEquals(round.randomState, next.randomState)
        assertSame(round.players[1], next.players[1])
        assertEquals(completed, painted.completeTask(signedSorted))
    }

    private fun playerGame(firstInstruction: Long = 0) =
        ScavengerHuntGame().startRound(firstInstruction).game.authenticate(1, 0).game
}
