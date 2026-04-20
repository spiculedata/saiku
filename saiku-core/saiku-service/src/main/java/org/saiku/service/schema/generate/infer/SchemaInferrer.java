package org.saiku.service.schema.generate.infer;

import java.sql.JDBCType;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.model.DbColumn;
import org.saiku.service.schema.generate.model.DbForeignKey;
import org.saiku.service.schema.generate.model.DbModel;
import org.saiku.service.schema.generate.model.DbTable;

/**
 * Top-level orchestrator for the rule-based schema inference pipeline.
 *
 * <p>Wires {@link TableClassifier} -> ({@link DimensionBuilder}, {@link TimeDimensionBuilder},
 * {@link MeasureBuilder}) into a single {@code DbModel -> DraftSchema} transformation. The
 * output is a pure rule-layer draft — LLM suggestions and user edits are applied in later
 * stages and overwrite provenance as appropriate.
 *
 * <p>Rules:
 * <ul>
 *   <li>One {@link DraftCube} per {@code FACT}-classified table. The cube's name mirrors
 *       the fact-table name (LLM pass supplies prettier captions downstream).</li>
 *   <li>For each FK on the fact whose target is a {@code DIMENSION}-classified table, a
 *       {@link DraftDimension} is built via {@link DimensionBuilder} with
 *       {@code foreignKey} bound to the FK's local column.</li>
 *   <li>If any fact has a DATE/TIMESTAMP column, a single shared "Time" dimension is
 *       attached at schema scope and one role-playing {@link DraftDimension} usage is
 *       attached to each cube per date column.</li>
 *   <li>Measures come from {@link MeasureBuilder} — SUM over numeric non-PK, non-FK,
 *       non-date columns, plus an implicit {@code Fact Count} COUNT_STAR.</li>
 * </ul>
 */
public class SchemaInferrer {

    private static final Set<JDBCType> DATE_TYPES =
            EnumSet.of(JDBCType.DATE, JDBCType.TIMESTAMP, JDBCType.TIMESTAMP_WITH_TIMEZONE);

    private static final String DEFAULT_SCHEMA_NAME = "GeneratedSchema";

    private final TableClassifier classifier;
    private final DimensionBuilder dimensionBuilder;
    private final TimeDimensionBuilder timeBuilder;
    private final MeasureBuilder measureBuilder;

    /** Wires default builders. Suitable for production use. */
    public SchemaInferrer() {
        this(new TableClassifier(), new DimensionBuilder(), new TimeDimensionBuilder(), new MeasureBuilder());
    }

    /** Explicit-collaborators constructor — primarily for testing with mocks or spies. */
    public SchemaInferrer(
            TableClassifier classifier,
            DimensionBuilder dimensionBuilder,
            TimeDimensionBuilder timeBuilder,
            MeasureBuilder measureBuilder) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.dimensionBuilder = Objects.requireNonNull(dimensionBuilder, "dimensionBuilder");
        this.timeBuilder = Objects.requireNonNull(timeBuilder, "timeBuilder");
        this.measureBuilder = Objects.requireNonNull(measureBuilder, "measureBuilder");
    }

    /** Run the pipeline. Never returns {@code null}; may return an empty schema. */
    public DraftSchema infer(DbModel model) {
        Objects.requireNonNull(model, "model");

        DraftSchema schema = new DraftSchema(DEFAULT_SCHEMA_NAME);
        Map<DbTable, TableClassification> classifications = classifier.classify(model);

        // Build the shared Time dim once if any fact has a date-like column; otherwise skip
        // entirely so downstream stages aren't burdened with an empty shared dimension.
        DraftDimension sharedTime = null;
        for (Map.Entry<DbTable, TableClassification> entry : classifications.entrySet()) {
            if (entry.getValue().kind() == TableClassification.Kind.FACT && hasDateColumn(entry.getKey())) {
                sharedTime = timeBuilder.buildSharedTimeDimension();
                schema.sharedDimensions().add(sharedTime);
                break;
            }
        }

        for (Map.Entry<DbTable, TableClassification> entry : classifications.entrySet()) {
            if (entry.getValue().kind() != TableClassification.Kind.FACT) {
                continue;
            }
            DbTable fact = entry.getKey();
            DraftCube cube = new DraftCube(
                    fact.name(), fact.name(), new Provenance(Provenance.Source.RULE, "rule:cube-from-fact", 1.0));

            for (DbForeignKey fk : fact.foreignKeys()) {
                model.tableByName(fk.toTable()).ifPresent(dimTable -> {
                    TableClassification dimClass = classifications.get(dimTable);
                    if (dimClass != null && dimClass.kind() == TableClassification.Kind.DIMENSION) {
                        DraftDimension dim = dimensionBuilder.build(dimTable, model);
                        dim.setForeignKey(fk.fromColumn());
                        cube.dimensions().add(dim);
                    }
                });
            }

            if (sharedTime != null) {
                cube.dimensions().addAll(timeBuilder.buildCubeUsages(fact, sharedTime));
            }

            cube.measures().addAll(measureBuilder.build(fact));
            schema.cubes().add(cube);
        }

        return schema;
    }

    private static boolean hasDateColumn(DbTable table) {
        for (DbColumn c : table.columns()) {
            if (c.type() != null && DATE_TYPES.contains(c.type())) {
                return true;
            }
        }
        return false;
    }
}
