"""Execute the production 3->4 DDL with synthetic v3 rows; compare Room-generated schemas."""
import json
import re
import sqlite3
from pathlib import Path
from datetime import datetime, timezone

root = Path(__file__).resolve().parent.parent
generated = (root / "android/app/build/generated/source/kapt/debug/com/lrh/healthvivo/fitness/FitnessDatabase_Impl.java").read_text(encoding="utf-8")
source = (root / "android/app/src/main/java/com/lrh/healthvivo/fitness/FitnessDatabase.kt").read_text(encoding="utf-8")
sqls = re.findall(r'SQLite.execSQL\(connection, "(CREATE TABLE IF NOT EXISTS [^"]+)"\);', generated)
schemas = {sql.split(chr(96))[1]: sql for sql in sqls if chr(96) in sql}
assert "body_fats" in schemas
migration = source.split("val MIGRATION_3_4", 1)[1].split("fun create", 1)[0]
migration_sqls = re.findall(r'db.execSQL\("([^"]+)"\)', migration)
database = sqlite3.connect(":memory:")
expected = sqlite3.connect(":memory:")
for table, sql in schemas.items():
    expected.execute(sql)
    if table != "body_fats":
        database.execute(sql)
fixtures = {
    "profile": dict(id=1, age=30, heightCm=175, weightKg=70.3, sex="male", goal="lose", specialCondition=0, updatedAt=1, targetWeightKg=65),
    "weights": dict(day="2026-10-12", kg=70.3, recordedAt=1),
    "meals": dict(id="fixture-meal", day="2026-10-12", meal="breakfast", name="fixture-oats", grams=100, kcalPer100g=380, source="manual", sourceReference="synthetic", recordedAt=1),
    "daily_steps": dict(day="2026-10-12", steps=0, recordedAt=1),
    "custom_foods": dict(id="fixture-food", name="fixture", kcalPer100g=100),
    "exercises": dict(id="fixture-workout", day="2026-10-12", name="fixture-walk", minutes=30, kcal=120, method="manual", recordedAt=1, category="aerobic", inputMode="time", includedInSteps=1, netCalories=1, estimateNote="fixture", details="")
}
for table, values in fixtures.items():
    database.execute("INSERT INTO " + table + " (" + ",".join(values) + ") VALUES (" + ",".join("?" for _ in values) + ")", tuple(values.values()))
before = {table: database.execute("SELECT * FROM " + table).fetchall() for table in fixtures}
for sql in migration_sqls:
    database.execute(sql)
for table in schemas:
    assert database.execute("PRAGMA table_info(" + table + ")").fetchall() == expected.execute("PRAGMA table_info(" + table + ")").fetchall(), table
for table in fixtures:
    assert database.execute("SELECT * FROM " + table).fetchall() == before[table], table
database.execute("INSERT INTO body_fats VALUES('2026-10-12', 19.5, 1)")
database.execute("INSERT OR REPLACE INTO body_fats VALUES('2026-10-12', 19.8, 2)")
assert database.execute("SELECT percent FROM body_fats").fetchall() == [(19.8,)]
report = dict(syntheticOnly=True, roomRuntimeVerified=False, schemaMatchesRoomGeneratedCode=True,
    preservedTables=list(fixtures), bodyFatUpsert=True, timestamp=datetime.now(timezone.utc).isoformat())
(root / "artifacts/local-upgrade-simulation-0.10.0.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report, indent=2))
