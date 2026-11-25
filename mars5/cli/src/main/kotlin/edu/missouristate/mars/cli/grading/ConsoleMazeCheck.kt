package edu.missouristate.mars.cli.grading

/** Fixed public 8x8 maze ABI; no downloaded predicate or reference neighbor order. */
sealed class ConsoleMazeCheck {
  /** Run the same bounded publication checks from a handwritten or generated assignment. */
  fun verify(execution: AssemblyCase) = run(execution) {}

  internal abstract fun run(execution: AssemblyCase, checkCancellation: () -> Unit)

  data class Search(val walls: String, val start: Int, val goal: Int, val fixtureIndex: Int) :
      ConsoleMazeCheck() {
    init {
      require(walls.length == 128 && walls.chunked(2).all { it == "00" || it == "01" }) {
        "A maze needs 64 zero/one wall bytes"
      }
      require(start in 0..63 && goal in 0..63 && fixtureIndex in 0..65535) {
        "Invalid maze endpoint or fixture index"
      }
      require(
          walls.substring(start * 2, start * 2 + 2) == "00" &&
              walls.substring(goal * 2, goal * 2 + 2) == "00",
      ) {
        "Maze endpoints must be open"
      }
    }

    override fun run(execution: AssemblyCase, checkCancellation: () -> Unit) =
        with(execution) {
          expectDataAddress("frame", 0x10010000L)
          expectDataAddress("frame_guard", 0x10010100L)
          val input = walls.chunked(2).map { it.toInt(16) }
          val protected =
              mutableListOf(
                  traceMemoryStores("walls", 64),
                  traceMemoryStores("start", 4),
                  traceMemoryStores("goal", 4),
              )
          for ((index, label) in guards.withIndex()) {
            setWord(label, guard(index))
            protected += traceMemoryStores(label, 4)
          }
          prepareBytes(this, "walls", input)
          setWord("start", start)
          setWord("goal", goal)
          for (cell in 0..63) setWord("frame", 0x12345678, offset = cell * 4)
          for (label in listOf("states", "parents", "distance", "queue", "path")) for (word in
              0..15) setWord(label, 0x5a5a5a5a, offset = word * 4)
          for (label in listOf("head", "tail", "current", "phase", "result", "length")) setWord(
              label,
              123456,
          )
          val publications = traceExecution("maze_ready")
          val oracle = shortest(input, start)
          val states = IntArray(64)
          val parents = IntArray(64) { 255 }
          val distances = IntArray(64) { 255 }
          val queue = mutableListOf(start)
          val path = IntArray(64)
          states[start] = 1
          distances[start] = 0
          var head = 0
          var current = -1
          fun snapshot(phase: Int, result: Int = -1, length: Int = -1) {
            checkCancellation()
            expectWord("phase", phase)
            expectWord("head", head)
            expectWord("tail", queue.size)
            expectWord("current", current)
            expectWord("result", result)
            expectWord("length", length)
            expectCondition(
                readBytes("states", 64).contentEquals(states),
                "states must match the published BFS transitions",
            )
            expectCondition(
                readBytes("parents", 64).contentEquals(parents),
                "parents must record each discovery predecessor",
            )
            expectCondition(
                readBytes("distance", 64).contentEquals(distances),
                "distances must be shortest edge counts",
            )
            expectCondition(
                readBytes("path", 64).contentEquals(path),
                "path mask must match the parent chain",
            )
            expectCondition(
                readBytes("queue", 64).toList() == queue + List(64 - queue.size) { 0 },
                "queue must retain FIFO history and its zero unused suffix",
            )
            for (cell in 0..63) expectWord(
                "frame",
                color(input[cell], states[cell], path[cell]),
                offset = cell * 4,
            )
            expectCondition(
                readBytes("walls", 64).toList() == input,
                "maze input must remain unchanged",
            )
            expectWord("start", start)
            expectWord("goal", goal)
            for ((index, label) in guards.withIndex()) expectWord(label, guard(index))
            protected.forEach { it.expectNone() }
          }
          runUntil("maze_ready")
          snapshot(0)
          step()
          var found = false
          while (head < queue.size && !found) {
            current = queue[head++]
            states[current] = 2
            val discovered =
                if (current == goal) emptySet()
                else adjacent(current).filter { input[it] == 0 && states[it] == 0 }.toSet()
            runUntil("maze_ready")
            expectWord("phase", 1)
            expectWord("head", head)
            expectWord("current", current)
            val tail = readWord("tail")
            expectCondition(
                tail == queue.size + discovered.size && tail <= 64,
                "enqueue every unseen open neighbor exactly once",
            )
            val actual = readBytes("queue", tail).toList()
            expectCondition(
                actual.take(queue.size) == queue,
                "dequeue must not overwrite FIFO history",
            )
            val suffix = actual.drop(queue.size)
            expectCondition(
                suffix.size == suffix.toSet().size && suffix.toSet() == discovered,
                "new queue entries must be exactly the unseen orthogonal neighbors",
            )
            for (cell in suffix) {
              states[cell] = 1
              parents[cell] = current
              distances[cell] = distances[current] + 1
              expectCondition(
                  distances[cell] == oracle[cell],
                  "FIFO discovery must assign a shortest distance",
              )
              queue += cell
            }
            snapshot(1)
            found = current == goal
            step()
          }
          if (found) {
            var cell = goal
            var remaining = oracle[cell]
            while (true) {
              path[cell] = 1
              if (cell == start) break
              val parent = parents[cell]
              expectCondition(
                  parent in 0..63 && parent in adjacent(cell),
                  "path predecessor must be an orthogonal cell",
              )
              expectCondition(
                  remaining > 0 && distances[parent] == remaining - 1,
                  "parent chain must strictly decrease distance",
              )
              remaining--
              cell = parent
            }
            expectCondition(
                remaining == 0 && path.sum() == oracle[goal] + 1,
                "path must contain one more cell than edge",
            )
          }
          expectCondition(
              found == (oracle[goal] != 255),
              "search result must agree with reachability",
          )
          val result = if (found) 1 else 0
          val length = if (found) oracle[goal] else -1
          runUntil("maze_ready")
          snapshot(2, result, length)
          runToExit()
          snapshot(2, result, length)
          expectCondition(
              publications.snapshot().instructions == head + 2L,
              "publish initialization, every complete expansion and final result exactly once",
          )
        }

    private fun guard(index: Int) = 0x13579bdf xor (fixtureIndex * 131 + index)
  }

  /** Five original independent renderer fixtures, including wall/path/state priority. */
  data class Renderer(val fixture: Int) : ConsoleMazeCheck() {
    init {
      require(fixture in 0..4) { "Choose renderer fixture zero through four" }
    }

    override fun run(execution: AssemblyCase, checkCancellation: () -> Unit) =
        with(execution) {
          val walls = List(64) { if (fixture == 1 || fixture >= 3 && it % 4 == 0) 1 else 0 }
          val states = List(64) { if (fixture < 2) 0 else (it + fixture) % 3 }
          val path = List(64) { if (fixture >= 3 && it % (fixture - 1) == 0) 1 else 0 }
          val inputs =
              mapOf(
                  "walls" to walls,
                  "states" to states,
                  "path" to path,
                  "parents" to List(64) { 73 },
                  "distance" to List(64) { 147 },
                  "queue" to List(64) { 219 },
              )
          val metadata =
              listOf("start", "goal", "head", "tail", "current", "phase", "result", "length")
          val protected =
              inputs.keys.map { traceMemoryStores(it, 64) } +
                  (guards + metadata).map { traceMemoryStores(it, 4) }
          for ((label, bytes) in inputs) prepareBytes(this, label, bytes)
          for ((index, label) in (guards + metadata).withIndex()) setWord(label, guard(index))
          for (cell in 0..63) setWord("frame", 0x12345678, offset = cell * 4)
          for (index in 0..7) setRegister("s$index", 0x1122334455667700L + index)
          setRegister("sp", 0x7fffeff0L)
          setRegisterToTextAddress("ra", "done")
          val publications = traceExecution("maze_ready")
          startAt("render")
          runUntil("maze_ready")
          fun snapshot() {
            checkCancellation()
            for (cell in 0..63) expectWord(
                "frame",
                color(walls[cell], states[cell], path[cell]),
                offset = cell * 4,
            )
            for ((label, bytes) in inputs) expectCondition(
                readBytes(label, 64).toList() == bytes,
                "renderer must preserve $label",
            )
            for ((index, label) in (guards + metadata).withIndex()) expectWord(label, guard(index))
            for (index in 0..7) expectRegister("s$index", 0x1122334455667700L + index)
            expectRegister("sp", 0x7fffeff0L)
            protected.forEach { it.expectNone() }
          }
          snapshot()
          runUntil("done")
          snapshot()
          runToExit()
          snapshot()
          expectCondition(
              publications.snapshot().instructions == 1L,
              "renderer must reach maze_ready exactly once",
          )
        }

    private fun guard(index: Int) = 0x13579bdf xor (fixture * 31 + index)
  }

  companion object {
    private val guards =
        listOf(
            "frame_guard",
            "walls_guard",
            "start_guard",
            "goal_guard",
            "states_guard",
            "parents_guard",
            "distance_guard",
            "queue_guard",
            "path_guard",
            "metadata_guard",
        )

    private fun prepareBytes(execution: AssemblyCase, label: String, bytes: List<Int>) {
      execution.expectCondition(
          (execution.readDataAddress(label) and 3L) == 0L,
          "$label must have a four-byte-aligned base",
      )
      execution.setBytes(label, *bytes.toIntArray())
    }

    private fun adjacent(cell: Int): List<Int> {
      val row = cell / 8
      val column = cell % 8
      return listOf(row - 1 to column, row to column + 1, row + 1 to column, row to column - 1)
          .filter { (r, c) -> r in 0..7 && c in 0..7 }
          .map { (r, c) -> r * 8 + c }
    }

    private fun shortest(walls: List<Int>, start: Int): IntArray {
      val distance = IntArray(64) { 255 }
      val queue = ArrayDeque<Int>()
      distance[start] = 0
      queue.addLast(start)
      while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (next in adjacent(current)) if (walls[next] == 0 && distance[next] == 255) {
          distance[next] = distance[current] + 1
          queue.addLast(next)
        }
      }
      return distance
    }

    private fun color(wall: Int, state: Int, path: Int): Int =
        when {
          wall != 0 -> 0x202020
          path != 0 -> 0x0072b2
          state == 0 -> 0xffffff
          state == 1 -> 0xe69f00
          else -> 0x009e73
        }
  }
}
