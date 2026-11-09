package edu.missouristate.mars.plugin.tools

/** Immutable game state shared by the memory-mapped device and its scoreboard. */
internal data class ScavengerHuntGame(
    val playerId: Int = administratorId,
    val authenticationSequence: Int = 0,
    val turnsRemaining: Int = 0,
    val round: Round? = null,
) {
    data class Point(val x: Int, val y: Int)

    data class Player(
        val position: Point,
        val energy: Int,
        val color: Int,
        val task: List<Int>,
        val visitedMask: Int = 0,
        val finishAfterInstructions: Long? = null,
    )

    data class Round(
        val locations: List<Point>,
        val players: List<Player>,
        val firstInstruction: Long,
        val randomState: Long,
    )

    data class Result(val game: ScavengerHuntGame, val rejection: Rejection? = null)

    enum class Rejection {
        AUTHENTICATION, UNKNOWN_PLAYER, ADMINISTRATOR_REQUIRED, PLAYER_REQUIRED, ROUND_NOT_STARTED,
        NO_ENERGY, OUTSIDE_BOARD, MOVE_TOO_FAR, INVALID_TASK,
    }

    fun authenticate(authentication: Int, requestedId: Int): Result {
        // Every attempt consumes a sequence value, including a rejected attempt. Int wraps at 32 bits.
        val next = authenticationSequence + 1
        val advanced = copy(authenticationSequence = next)
        if (authentication != next) return Result(advanced, Rejection.AUTHENTICATION)
        if (requestedId != administratorId && requestedId !in 0 until playerCount) {
            return Result(advanced, Rejection.UNKNOWN_PLAYER)
        }
        return Result(advanced.copy(playerId = requestedId))
    }

    fun startRound(instruction: Long): Result {
        if (playerId != administratorId) return Result(this, Rejection.ADMINISTRATOR_REQUIRED)
        val random = ScavengerRandom(initialRandomState)
        val locations = List(locationCount - 1) {
            Point((random.nextDouble() * boardWidth).toInt(), (random.nextDouble() * (boardHeight - 50)).toInt())
        } + start
        val players = List(playerCount) { Player(start, startingEnergy, 0, random.nextTask()) }
        return Result(copy(authenticationSequence = 0, round = Round(locations, players, instruction, random.state)))
    }

    fun move(destination: Point, instruction: Long): Result = updatePlayer { activeRound, player ->
        if (player.energy <= 0) return@updatePlayer Result(this, Rejection.NO_ENERGY)
        if (destination.x !in 0..boardWidth || destination.y !in 0..boardHeight) {
            return@updatePlayer Result(this, Rejection.OUTSIDE_BOARD)
        }
        val dx = destination.x - player.position.x
        val dy = destination.y - player.position.y
        // A squared distance of at most 6 is the integer-coordinate equivalent of 2.5 pixels.
        if (dx * dx + dy * dy > 6) return@updatePlayer Result(this, Rejection.MOVE_TOO_FAR)

        var visited = player.visitedMask
        for ((index, location) in activeRound.locations.withIndex()) {
            if (destination == location) visited = visited or (1 shl index)
        }
        val finish = if (visited == allLocations && player.finishAfterInstructions == null) {
            instruction - activeRound.firstInstruction
        } else player.finishAfterInstructions
        replacePlayer(activeRound, player.copy(
            position = destination,
            energy = player.energy - 1,
            visitedMask = visited,
            finishAfterInstructions = finish,
        ))
    }

    fun completeTask(words: List<Int>): Result = updatePlayer { activeRound, player ->
        if (words.size != taskSize || (1 until taskSize).any { words[it - 1] > words[it] }) {
            return@updatePlayer Result(this, Rejection.INVALID_TASK)
        }
        val random = ScavengerRandom(activeRound.randomState)
        val task = random.nextTask()
        replacePlayer(activeRound.copy(randomState = random.state), player.copy(energy = startingEnergy, task = task))
    }

    fun changeColor(color: Int): Result = updatePlayer { activeRound, player ->
        replacePlayer(activeRound, player.copy(color = color))
    }

    private inline fun updatePlayer(update: (Round, Player) -> Result): Result {
        if (playerId == administratorId) return Result(this, Rejection.PLAYER_REQUIRED)
        val activeRound = round ?: return Result(this, Rejection.ROUND_NOT_STARTED)
        return update(activeRound, activeRound.players[playerId])
    }

    private fun replacePlayer(activeRound: Round, player: Player): Result {
        val players = activeRound.players.toMutableList()
        players[playerId] = player
        return Result(copy(round = activeRound.copy(players = players)))
    }

    companion object {
        const val administratorId = 999
        const val playerCount = 22
        const val boardWidth = 712
        const val boardHeight = 652
        const val locationCount = 7
        const val taskSize = 20
        private const val startingEnergy = 20
        private const val allLocations = (1 shl locationCount) - 1
        private const val initialRandomState = 42L xor 0x5deece66dL
        private val start = Point(255, 255)
    }
}

/** The specified java.util.Random sequence, with explicit state for restoring a game checkpoint. */
private class ScavengerRandom(var state: Long) {
    private fun next(bits: Int): Long {
        state = (state * 0x5deece66dL + 11) and ((1L shl 48) - 1)
        return state ushr (48 - bits)
    }

    fun nextDouble(): Double = ((next(26) shl 27) + next(27)) / (1L shl 53).toDouble()

    fun nextTask(): List<Int> = List(ScavengerHuntGame.taskSize) { (nextDouble() * Int.MAX_VALUE).toInt() }
}
