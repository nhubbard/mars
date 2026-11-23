package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.cli.grading.FunctionCheckCase
import edu.missouristate.mars.cli.grading.ConsoleCheckCase
import java.util.List.copyOf
import java.util.Map.copyOf as copyMap
import javax.swing.plaf.basic.BasicHTML
import org.jdom.Verifier

internal data class MarsLessonAnchor(val label: String, val mnemonic: String, val occurrence: Int)
internal data class MarsLessonHint(val id: String, val text: String)

internal sealed interface MarsNativeLessonActivity {
    val id: String
    val explanation: String
    val anchor: MarsLessonAnchor?
    val hints: List<MarsLessonHint>
}

internal data class MarsNativeLessonStep(
    override val explanation: String, val prediction: MarsPrediction,
    override val anchor: MarsLessonAnchor?, override val hints: List<MarsLessonHint>,
) : MarsNativeLessonActivity {
    override val id: String get() = prediction.id
}

internal data class MarsNativeRunStep(
    override val id: String, override val explanation: String,
    val integerInputs: List<Int>, val stdout: String, val success: String,
    override val anchor: MarsLessonAnchor?, override val hints: List<MarsLessonHint>,
    val source: String? = null,
) : MarsNativeLessonActivity

internal data class MarsNativeDebugStop(
    val id: String, val explanation: String, val anchor: MarsLessonAnchor,
    val registers: Map<String, Long>, val success: String, val hints: List<MarsLessonHint>,
    val vectorRegisters: Map<String, VectorRegister> = emptyMap(),
)

internal data class MarsNativeDebugStep(
    override val id: String, override val explanation: String, val integerInputs: List<Int>,
    val stops: List<MarsNativeDebugStop>, val success: String, override val hints: List<MarsLessonHint>,
    val source: String? = null,
) : MarsNativeLessonActivity {
    override val anchor: MarsLessonAnchor get() = stops.first().anchor
}

internal data class MarsNativeAssessmentStep(
    override val id: String, override val explanation: String,
    val source: String, val caseProfile: String, val cases: List<ConsoleCheckCase>, val success: String,
    override val hints: List<MarsLessonHint>,
    val functionCases: List<FunctionCheckCase> = emptyList(),
) : MarsNativeLessonActivity {
    init { require(cases.isEmpty() != functionCases.isEmpty()) { "An assessment needs exactly one nonempty check kind" } }
    override val anchor: MarsLessonAnchor? get() = null
}

/** Data interpreted by the native trainer; downloaded content cannot name executable actions or scripts. */
internal data class MarsNativeLessonPlan(
    val introduction: String, val conclusion: String, val steps: List<MarsNativeLessonActivity>,
) {
    companion object {
        const val MAX_BYTES = 1024 * 1024
        private val ID = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")

        fun parse(bytes: ByteArray, caseData: (String) -> ByteArray = {
            throw IllegalArgumentException("Assessment case data must come from the course archive")
        }): MarsNativeLessonPlan {
            val root = MarsLessonJson.parse(bytes, MAX_BYTES)
            root.fields(setOf("formatVersion", "introduction", "conclusion", "steps"))
            val version = root.get("formatVersion").toString()
            require(version in setOf("1", "2", "3", "4", "5", "6", "7")) { "Unsupported native lesson format" }
            val steps = root.array("steps")
            require(steps.size() in 1..128) { "A native lesson needs 1 through 128 steps" }
            val profiles = mutableMapOf<String, List<ConsoleCheckCase>>()
            val functionProfiles = mutableMapOf<String, List<FunctionCheckCase>>()
            val parsed = steps.map {
                val step = it.objectValue()
                if (version == "1") parsePrediction(step, false)
                else when (step.text("kind", 32)) {
                    "prediction" -> parsePrediction(step, true)
                    "run" -> parseRun(step, version)
                    "debug" -> {
                        require(version in setOf("3", "4", "5", "6", "7")) { "Debug observations require native lesson format 3 or later" }
                        parseDebug(step, version)
                    }
                    "assessment" -> {
                        require(version in setOf("4", "5", "6", "7")) { "Independent assessment requires native lesson format 4 or later" }
                        val fields = setOf("kind", "id", "explanation", "source", "caseProfile", "success")
                        step.fields(fields + "hints" + if (version in setOf("6", "7")) setOf("checkKind") else emptySet(), fields)
                        val source = MarsLessonPackages.path(step.text("source", 240))
                        val profile = MarsLessonPackages.path(step.text("caseProfile", 240))
                        val kind = if (step.has("checkKind")) step.text("checkKind", 32) else "console"
                        require(kind in setOf("console", "function")) { "Unsupported assessment check kind" }
                        val functions = if (kind == "function") functionProfiles.getOrPut(profile) {
                            MarsFunctionCaseData.parse(caseData(profile))
                        } else emptyList()
                        val console = if (kind == "console") profiles.getOrPut(profile) {
                            MarsConsoleCaseData.parse(caseData(profile))
                        } else emptyList()
                        MarsNativeAssessmentStep(step.identifier("id"), step.text("explanation", 8192), source, profile,
                            console, step.text("success", 8192), hints(step), functions)
                    }
                    else -> throw IllegalArgumentException("Unsupported native lesson activity")
                }
            }
            require(parsed.map { it.id }.distinct().size == parsed.size) { "Duplicate native lesson step ID" }
            return MarsNativeLessonPlan(root.text("introduction", 8192), root.text("conclusion", 8192), copyOf(parsed))
        }

        private fun parsePrediction(step: JsonObject, tagged: Boolean): MarsNativeLessonStep {
            val required = setOf("id", "explanation", "question", "choices", "correctChoice") +
                if (tagged) setOf("kind") else emptySet()
            step.fields(required + setOf("anchor", "hints"), required)
            val id = step.identifier("id")
            val choices = step.array("choices")
            require(choices.size() in 2..6) { "A prediction needs 2 through 6 choices" }
            val parsedChoices = choices.map {
                val choice = it.objectValue()
                choice.fields(setOf("id", "text", "feedback"))
                MarsPredictionChoice(choice.identifier("id"), choice.text("text", 2048), choice.text("feedback", 8192))
            }
            require(parsedChoices.map { it.id }.distinct().size == parsedChoices.size) { "Duplicate prediction choice ID" }
            val correct = step.identifier("correctChoice")
            require(parsedChoices.any { it.id == correct }) { "The correct choice must exist" }
            return MarsNativeLessonStep(
                step.text("explanation", 8192),
                MarsPrediction(id, step.text("question", 2048), copyOf(parsedChoices), correct), anchor(step), hints(step),
            )
        }

        private fun parseRun(step: JsonObject, version: String): MarsNativeRunStep {
            val required = setOf("kind", "id", "explanation", "integerInputs", "stdout", "success")
            step.fields(required + setOf("anchor", "hints") + sourceField(version), required)
            return MarsNativeRunStep(step.identifier("id"), step.text("explanation", 8192), integerInputs(step),
                step.text("stdout", 16384, allowBlank = true), step.text("success", 8192), anchor(step), hints(step), source(step))
        }

        private fun integerInputs(step: JsonObject): List<Int> {
            val inputs = step.array("integerInputs")
            require(inputs.size() <= 64) { "A Run observation supports at most 64 integer inputs" }
            val parsed = inputs.map {
                require(it is JsonPrimitive && it.isNumber && Regex("-?(0|[1-9][0-9]{0,9})").matches(it.asString)) {
                    "Expected a signed 32-bit integer input"
                }
                requireNotNull(it.asString.toIntOrNull()) { "Integer input exceeds its 32-bit range" }
            }
            return copyOf(parsed)
        }

        private fun parseDebug(step: JsonObject, version: String): MarsNativeDebugStep {
            val required = setOf("kind", "id", "explanation", "integerInputs", "stops", "success")
            step.fields(required + "hints" + sourceField(version), required)
            val stops = step.array("stops")
            require(stops.size() in 1..32) { "A Debug observation needs 1 through 32 stops" }
            val parsed = stops.map {
                val stop = it.objectValue()
                val fields = setOf("id", "explanation", "anchor", "registers", "success")
                stop.fields(fields + "hints" + if (version == "7") setOf("vectorRegisters") else emptySet(), fields)
                val registers = stop.get("registers").objectValue()
                require(registers.size() <= 32) { "A Debug stop supports at most 32 integer register values" }
                val values = linkedMapOf<String, Long>()
                for ((spelling, value) in registers.entrySet()) {
                    require(spelling.isNotEmpty()) { "Unknown Debug integer register" }
                    val register = requireNotNull(RegisterNames.integer(spelling)) { "Unknown Debug integer register" }
                    require(register.name !in values) { "Duplicate Debug register aliases" }
                    require(value is JsonPrimitive && value.isString && Regex("0x[0-9a-fA-F]{16}").matches(value.asString)) {
                        "Debug register values must contain exactly 64 hexadecimal bits"
                    }
                    values[register.name] = value.asString.substring(2).toULong(16).toLong()
                }
                val vectors = vectorRegisters(stop)
                require(values.isNotEmpty() || vectors.isNotEmpty()) { "A Debug stop needs integer or vector register values" }
                MarsNativeDebugStop(stop.identifier("id"), stop.text("explanation", 8192),
                    parseAnchor(stop.get("anchor").objectValue()), copyMap(values), stop.text("success", 8192), hints(stop), vectors)
            }
            require(parsed.map { it.id }.distinct().size == parsed.size) { "Duplicate Debug stop ID" }
            return MarsNativeDebugStep(step.identifier("id"), step.text("explanation", 8192), integerInputs(step),
                copyOf(parsed), step.text("success", 8192), hints(step), source(step))
        }

        private fun vectorRegisters(stop: JsonObject): Map<String, VectorRegister> {
            if (!stop.has("vectorRegisters")) return emptyMap()
            val fields = stop.get("vectorRegisters").objectValue()
            require(fields.size() <= 32) { "A Debug stop supports at most 32 vector registers" }
            return copyMap(fields.entrySet().associate { (name, value) ->
                require(RegisterNames.vector(name) != null) { "Unknown Debug vector register" }
                require(value is JsonPrimitive && value.isString && Regex("0x[0-9a-fA-F]{32}").matches(value.asString)) {
                    "Debug vector values must contain exactly 128 hexadecimal bits"
                }
                name to VectorRegister(value.asString.substring(18).toULong(16).toLong(),
                    value.asString.substring(2, 18).toULong(16).toLong())
            })
        }

        private fun sourceField(version: String) = if (version in setOf("5", "6", "7")) setOf("source") else emptySet()
        private fun source(step: JsonObject) = if (step.has("source")) MarsLessonPackages.path(step.text("source", 240)) else null

        private fun anchor(step: JsonObject) = if (step.has("anchor")) parseAnchor(step.get("anchor").objectValue()) else null

        private fun hints(step: JsonObject): List<MarsLessonHint> {
            val hints = if (step.has("hints")) step.array("hints").map {
                val hint = it.objectValue()
                hint.fields(setOf("id", "text"))
                MarsLessonHint(hint.identifier("id"), hint.text("text", 8192))
            } else emptyList()
            require(hints.size <= 4 && hints.map { it.id }.distinct().size == hints.size) { "Invalid graduated hints" }
            return copyOf(hints)
        }

        private fun parseAnchor(anchor: JsonObject): MarsLessonAnchor {
            anchor.fields(setOf("label", "mnemonic", "occurrence"))
            val label = anchor.text("label", 128)
            val mnemonic = anchor.text("mnemonic", 32)
            require(Regex("[A-Za-z_.$][A-Za-z0-9_.$]*").matches(label)) { "Invalid source label anchor" }
            require(Regex("[a-z][a-z0-9._]*").matches(mnemonic)) { "Invalid instruction anchor" }
            val occurrence = anchor.get("occurrence")
            require(occurrence is JsonPrimitive && occurrence.isNumber && Regex("0|[1-9][0-9]{0,2}").matches(occurrence.asString)) {
                "Invalid anchor occurrence"
            }
            val index = occurrence.asInt
            require(index <= 127) { "Anchor occurrence exceeds lesson limit" }
            return MarsLessonAnchor(label, mnemonic, index)
        }

        private fun JsonObject.identifier(name: String) = text(name, 64).also { require(ID.matches(it)) { "Invalid lesson ID" } }
        private fun JsonElement.objectValue(): JsonObject {
            require(this is JsonObject) { "Expected a lesson object" }
            return this
        }
        private fun JsonObject.array(name: String): JsonArray {
            val value = get(name)
            require(value is JsonArray) { "Expected lesson array: $name" }
            return value
        }
        private fun JsonObject.text(name: String, maximum: Int, allowBlank: Boolean = false): String {
            val value = get(name)
            require(value is JsonPrimitive && value.isString) { "Expected lesson text: $name" }
            return value.asString.also {
                require((allowBlank || it.isNotBlank()) && it.length <= maximum) { "Invalid lesson text: $name" }
                requireDisplayText(it)
            }
        }

        /** Native Learn messages use XML; escaping markup cannot make illegal XML characters valid. */
        fun requireDisplayText(text: String) {
            require(Verifier.checkCharacterData(text) == null) { "Lesson text contains unsupported display characters" }
        }

        fun requireDisplayTitle(title: String) {
            requireDisplayText(title)
            // The public lesson host gives names directly to JLabel, which auto-detects
            // HTML. Names are plain text; body messages are separately XML-escaped.
            require(!BasicHTML.isHTMLString(title)) { "Lesson titles must be plain text" }
        }
        private fun JsonObject.fields(allowed: Set<String>, required: Set<String> = allowed) {
            require(keySet().all { it in allowed } && keySet().containsAll(required)) { "Unsupported or missing native lesson fields" }
        }

    }
}
