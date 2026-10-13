package com.lrh.healthvivo.fitness

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CsvImportTest {
    private val today = LocalDate.of(2026, 10, 12)
    @Test fun stepsCsvPreservesZeroAndOptionalStride() {
        val data = CsvImport.parse("date,steps,step_length_cm\n2026-10-11,0,\n2026-10-12,6000,70", 1, today)
        assertEquals(0, data.steps[0].steps); assertNull(data.steps[0].stepLengthCm)
        assertEquals(70.0, data.steps[1].stepLengthCm!!, 0.001)
        assertEquals(100, CsvImport.parse("date,steps\n2026-10-12,100", 1, today).steps.single().steps)
        listOf("date,steps\n2026-10-12,1\n2026-10-12,2", "date,steps\n2026-10-12,1.5").forEach { csv ->
            try { CsvImport.parse(csv, 1, today); fail("Invalid steps file accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun weightsUseExplicitKilogramsAndDates() {
        val values = CsvImport.parse("date,weight_kg\n2026-10-11,70.5\n2026-10-12,70", 1000, today)
        assertEquals(2, values.weights.size); assertEquals(70.5, values.weights[0].kg, 0.001)
    }
    @Test fun sameExerciseFileKeepsStableIdsAndSeparateRepeatedRows() {
        val csv = "date,name,minutes,kcal\n2026-10-12,步行,30,100\n2026-10-12,步行,30,100"
        val first = CsvImport.parse(csv, 1000, today); val second = CsvImport.parse(csv, 2000, today)
        assertEquals(first.exercises.map { it.id }, second.exercises.map { it.id })
        assertNotEquals(first.exercises[0].id, first.exercises[1].id)
    }
    @Test fun quotedNamesCanContainCommas() {
        val values = CsvImport.parse("date,name,minutes,kcal\n2026-10-12,\"步行,慢速\",30,100", 1000, today)
        assertEquals("步行,慢速", values.exercises.single().name)
    }
    @Test fun oneBadRowRejectsWholeFile() {
        listOf("date,weight_kg\n2026-10-12,70\n2026-10-13,69", "date,weight_kg\n2026-10-12,70\n2026-10-12,69",
            "date,name,minutes,kcal\n2026-10-12,跑步,NaN,100", "date,name,minutes,kj\n2026-10-12,跑步,30,100").forEach { csv ->
            try { CsvImport.parse(csv, 1000, today); fail("Invalid file accepted") } catch (_: IllegalArgumentException) {}
        }
    }
}
