package com.lrh.healthvivo.fitness

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao interface FitnessDao {
    @Query("SELECT * FROM profile WHERE id = 1") fun profile(): Flow<Profile?>
    @Query("SELECT * FROM weights ORDER BY day DESC") fun weights(): Flow<List<WeightEntry>>
    @Query("SELECT * FROM meals ORDER BY day DESC, recordedAt DESC") fun meals(): Flow<List<MealEntry>>
    @Query("SELECT * FROM exercises ORDER BY day DESC, recordedAt DESC") fun exercises(): Flow<List<ExerciseEntry>>
    @Query("SELECT * FROM custom_foods ORDER BY name") fun foods(): Flow<List<CustomFood>>
    @Query("SELECT * FROM daily_steps ORDER BY day DESC") fun steps(): Flow<List<DailySteps>>
    @Query("SELECT * FROM body_fats ORDER BY day DESC") fun bodyFats(): Flow<List<BodyFatEntry>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun bodyFat(value: BodyFatEntry)
    @Query("DELETE FROM body_fats WHERE day = :day") suspend fun deleteBodyFat(day: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun profile(value: Profile)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun weight(value: WeightEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun meal(value: MealEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun exercise(value: ExerciseEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun food(value: CustomFood)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun steps(value: DailySteps)
    @Query("DELETE FROM daily_steps WHERE day = :day") suspend fun deleteSteps(day: String)
    @Query("DELETE FROM meals WHERE id = :id") suspend fun deleteMeal(id: String)
    @Query("DELETE FROM exercises WHERE id = :id") suspend fun deleteExercise(id: String)
    @Query("DELETE FROM weights WHERE day = :day") suspend fun deleteWeight(day: String)
    @Query("DELETE FROM custom_foods WHERE id = :id") suspend fun deleteFood(id: String)
}
@Database(entities = [Profile::class, WeightEntry::class, MealEntry::class, ExerciseEntry::class, CustomFood::class, DailySteps::class, BodyFatEntry::class], version = 5, exportSchema = false)
abstract class FitnessDatabase : RoomDatabase() {
    abstract fun dao(): FitnessDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE profile_new (id INTEGER NOT NULL PRIMARY KEY, age INTEGER NOT NULL, heightCm REAL NOT NULL, weightKg REAL NOT NULL, sex TEXT NOT NULL, goal TEXT NOT NULL, specialCondition INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("INSERT INTO profile_new SELECT id, age, heightCm, weightKg, sex, goal, specialCondition, updatedAt FROM profile")
                db.execSQL("DROP TABLE profile")
                db.execSQL("ALTER TABLE profile_new RENAME TO profile")
                db.execSQL("CREATE TABLE exercises_new (id TEXT NOT NULL PRIMARY KEY, day TEXT NOT NULL, name TEXT NOT NULL, minutes REAL, kcal REAL, method TEXT NOT NULL, weightKg REAL, met REAL, recordedAt INTEGER NOT NULL, category TEXT NOT NULL, inputMode TEXT NOT NULL, distanceKm REAL, sets INTEGER, reps INTEGER, loadKg REAL, includedInSteps INTEGER NOT NULL, netCalories INTEGER NOT NULL, estimateNote TEXT NOT NULL, details TEXT NOT NULL)")
                db.execSQL("INSERT INTO exercises_new SELECT id, day, name, minutes, kcal, method, weightKg, met, recordedAt, 'unspecified', 'time', NULL, NULL, NULL, NULL, 0, CASE WHEN method = 'met' THEN 1 ELSE 0 END, CASE WHEN method = 'met' THEN '旧版 MET 净消耗估算；请确认是否包含在每日步数中' ELSE '旧版用户热量，净消耗口径未确认；暂不参与消耗合计' END, '' FROM exercises")
                db.execSQL("DROP TABLE exercises")
                db.execSQL("ALTER TABLE exercises_new RENAME TO exercises")
                db.execSQL("UPDATE exercises SET includedInSteps = 1 WHERE method = 'met' AND (name LIKE '%步行%' OR name LIKE '%快走%' OR name LIKE '%跑步%' OR name LIKE '%慢跑%')")
                db.execSQL("CREATE TABLE daily_steps (day TEXT NOT NULL PRIMARY KEY, steps INTEGER NOT NULL, stepLengthCm REAL, recordedAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profile ADD COLUMN targetWeightKg REAL")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE body_fats (day TEXT NOT NULL PRIMARY KEY, percent REAL NOT NULL, recordedAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN steps INTEGER")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, FitnessDatabase::class.java, "fitness-local.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
